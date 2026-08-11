package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionMessageQueueEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionMessageCompletion;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionMessageDispatchResult;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionMessageQueueRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Service
public class AiSessionMessageQueueServiceImpl implements AiSessionMessageQueueService {

    static final String PENDING = "PENDING";
    static final String RUNNING = "RUNNING";
    static final String RETRY_WAIT = "RETRY_WAIT";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED_FINAL = "FAILED_FINAL";
    static final String CANCELED = "CANCELED";

    private static final List<String> ACTIVE_STATUSES = List.of(PENDING, RUNNING, RETRY_WAIT);
    private final AiSessionMessageQueueRepository repository;
    private final ObjectMapper objectMapper;
    private final Object monitor = new Object();
    private final Set<String> activeQueueKeys = new LinkedHashSet<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService scanner;
    private final int maxAttempts;
    private final long leaseMs;
    private final long baseRetryMs;
    private volatile Function<Map<String, Object>, AiChatResponse> dispatcher;
    private volatile Function<AiSessionMessageCompletion, Boolean> completionListener;

    @Autowired
    public AiSessionMessageQueueServiceImpl(AiSessionMessageQueueRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper,
                intProperty("wdsavs.ai.session.message-queue.worker-count", 8),
                intProperty("wdsavs.ai.session.message-queue.max-attempts", 6),
                longProperty("wdsavs.ai.session.message-queue.lease-ms", 900000L),
                longProperty("wdsavs.ai.session.message-queue.base-retry-ms", 500L));
    }

    AiSessionMessageQueueServiceImpl(AiSessionMessageQueueRepository repository, ObjectMapper objectMapper,
                                     int workerCount, int maxAttempts, long leaseMs, long baseRetryMs) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.workers = Executors.newFixedThreadPool(Math.max(1, workerCount));
        this.scanner = Executors.newSingleThreadScheduledExecutor();
        this.maxAttempts = Math.max(1, maxAttempts);
        this.leaseMs = Math.max(1000L, leaseMs);
        this.baseRetryMs = Math.max(50L, baseRetryMs);
    }

    @PostConstruct
    void start() {
        recoverExpiredLeases();
        scanner.scheduleWithFixedDelay(this::scanSafely, 100L, 250L, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        scanner.shutdownNow();
        workers.shutdownNow();
    }

    @Override
    public AiSessionMessageDispatchResult submit(Map<String, Object> params, String requestId,
                                                  boolean autoWakeWhenDeferred) {
        String sessionId = required(params, "sessionId");
        String targetNodeId = required(params, "targetNodeId");
        String effectiveRequestId = isBlank(requestId) ? UUID.randomUUID().toString() : requestId.trim();
        AiSessionMessageQueueEntity claimed = null;
        synchronized (monitor) {
            AiSessionMessageQueueEntity existing = repository
                    .findBySessionIdAndTargetNodeIdAndRequestId(sessionId, targetNodeId, effectiveRequestId)
                    .orElse(null);
            if (existing != null) {
                return existingResult(existing);
            }
            AiSessionMessageQueueEntity entity = newEntity(params, effectiveRequestId);
            repository.saveAndFlush(entity);
            claimed = claimSpecific(entity);
            if (claimed == null) {
                entity.setDeferred(true);
                entity.setWakeRequired(autoWakeWhenDeferred);
                entity.setUpdateTime(now());
                repository.save(entity);
                return queuedResult(entity);
            }
        }
        return executeClaimed(claimed, true, autoWakeWhenDeferred);
    }

    @Override
    public void cancelSession(String sessionId) {
        if (isBlank(sessionId)) {
            return;
        }
        synchronized (monitor) {
            String now = now();
            for (AiSessionMessageQueueEntity entity : repository.findBySessionIdAndStatusIn(sessionId, ACTIVE_STATUSES)) {
                entity.setStatus(CANCELED);
                entity.setLeaseUntil(null);
                entity.setUpdateTime(now);
                repository.save(entity);
            }
        }
    }

    @Override
    public void registerDispatcher(Function<Map<String, Object>, AiChatResponse> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void registerCompletionListener(Function<AiSessionMessageCompletion, Boolean> completionListener) {
        this.completionListener = completionListener;
    }

    private AiSessionMessageDispatchResult executeClaimed(AiSessionMessageQueueEntity entity, boolean callerThread,
                                                           boolean autoWakeWhenDeferred) {
        String queueKey = queueKey(entity);
        try {
            Function<Map<String, Object>, AiChatResponse> currentDispatcher = dispatcher;
            if (currentDispatcher == null) {
                return retry(entity, new IllegalStateException("Message dispatcher is not ready"), callerThread,
                        autoWakeWhenDeferred);
            }
            AiChatResponse response = currentDispatcher.apply(readParams(entity));
            if (response == null) {
                return retry(entity, new IllegalStateException("Relay returned empty response"), callerThread,
                        autoWakeWhenDeferred);
            }
            if (isBusy(response)) {
                return retry(entity, null, callerThread, autoWakeWhenDeferred);
            }
            if ("FAILED".equalsIgnoreCase(response.getStatus())) {
                return failFinal(entity, response, response.getAnswer());
            }
            synchronized (monitor) {
                entity.setStatus(SUCCEEDED);
                entity.setResponsePayloadJson(writeJson(response));
                entity.setErrorMessage(null);
                entity.setLeaseUntil(null);
                entity.setUpdateTime(now());
                repository.save(entity);
            }
            notifyCompletion(entity, response);
            return completedResult(entity, response);
        } catch (RuntimeException error) {
            if (isFinalFailure(error)) {
                return failFinal(entity, failedResponse(error), error.getMessage());
            }
            return retry(entity, error, callerThread, autoWakeWhenDeferred);
        } finally {
            synchronized (monitor) {
                activeQueueKeys.remove(queueKey);
            }
        }
    }

    private AiSessionMessageDispatchResult retry(AiSessionMessageQueueEntity entity, RuntimeException error,
                                                  boolean callerThread, boolean autoWakeWhenDeferred) {
        synchronized (monitor) {
            int attempts = entity.getAttemptCount() == null ? 1 : entity.getAttemptCount();
            if (error != null && attempts >= entity.getMaxAttempts()) {
                return failFinal(entity, failedResponse(error), error == null ? "Relay remained busy" : error.getMessage());
            }
            entity.setStatus(RETRY_WAIT);
            entity.setDeferred(true);
            entity.setWakeRequired(Boolean.TRUE.equals(entity.getWakeRequired()) || autoWakeWhenDeferred);
            entity.setErrorMessage(error == null ? "Relay is busy" : error.getMessage());
            entity.setNextAttemptTime(System.currentTimeMillis() + retryDelay(attempts));
            entity.setLeaseUntil(null);
            entity.setUpdateTime(now());
            repository.save(entity);
        }
        return queuedResult(entity);
    }

    private AiSessionMessageDispatchResult failFinal(AiSessionMessageQueueEntity entity, AiChatResponse response,
                                                      String message) {
        synchronized (monitor) {
            entity.setStatus(FAILED_FINAL);
            entity.setResponsePayloadJson(writeJson(response));
            entity.setErrorMessage(message);
            entity.setLeaseUntil(null);
            entity.setUpdateTime(now());
            repository.save(entity);
        }
        return completedResult(entity, response);
    }

    private AiSessionMessageQueueEntity claimSpecific(AiSessionMessageQueueEntity entity) {
        String key = queueKey(entity);
        if (activeQueueKeys.contains(key)) {
            return null;
        }
        List<AiSessionMessageQueueEntity> ordered = repository
                .findBySessionIdAndTargetNodeIdAndStatusInOrderByCreateTimeAscIdAsc(
                        entity.getSessionId(), entity.getTargetNodeId(), ACTIVE_STATUSES);
        if (ordered.isEmpty() || !entity.getId().equals(ordered.get(0).getId())) {
            return null;
        }
        return claim(entity, key);
    }

    private AiSessionMessageQueueEntity claim(AiSessionMessageQueueEntity entity, String key) {
        activeQueueKeys.add(key);
        entity.setStatus(RUNNING);
        entity.setAttemptCount((entity.getAttemptCount() == null ? 0 : entity.getAttemptCount()) + 1);
        entity.setLeaseUntil(System.currentTimeMillis() + leaseMs);
        entity.setUpdateTime(now());
        return repository.saveAndFlush(entity);
    }

    private void scanSafely() {
        try {
            dispatchDueMessages();
            retryPendingWakeups();
        } catch (Exception ignored) {
        }
    }

    private void dispatchDueMessages() {
        if (dispatcher == null) {
            return;
        }
        List<AiSessionMessageQueueEntity> claimed = new ArrayList<>();
        synchronized (monitor) {
            long currentTime = System.currentTimeMillis();
            Set<String> seenKeys = new LinkedHashSet<>();
            for (AiSessionMessageQueueEntity entity : repository.findByStatusInOrderByCreateTimeAscIdAsc(ACTIVE_STATUSES)) {
                String key = queueKey(entity);
                if (!seenKeys.add(key) || activeQueueKeys.contains(key)) {
                    continue;
                }
                if (RUNNING.equals(entity.getStatus())) {
                    continue;
                }
                if (entity.getNextAttemptTime() != null && entity.getNextAttemptTime() > currentTime) {
                    continue;
                }
                claimed.add(claim(entity, key));
            }
        }
        for (AiSessionMessageQueueEntity entity : claimed) {
            workers.submit(() -> executeClaimed(entity, false, Boolean.TRUE.equals(entity.getWakeRequired())));
        }
    }

    private void retryPendingWakeups() {
        if (completionListener == null) {
            return;
        }
        for (AiSessionMessageQueueEntity entity : repository
                .findByStatusAndWakeRequiredTrueAndWakeDispatchedFalseOrderByUpdateTimeAsc(SUCCEEDED)) {
            AiChatResponse response = readResponse(entity);
            if (response != null) {
                notifyCompletion(entity, response);
            }
        }
    }

    private void notifyCompletion(AiSessionMessageQueueEntity entity, AiChatResponse response) {
        if (!Boolean.TRUE.equals(entity.getWakeRequired()) || Boolean.TRUE.equals(entity.getWakeDispatched())
                || completionListener == null) {
            return;
        }
        AiSessionMessageCompletion completion = new AiSessionMessageCompletion();
        completion.setQueueId(entity.getQueueId());
        completion.setSessionId(entity.getSessionId());
        completion.setRequestId(entity.getRequestId());
        completion.setSourceNodeId(entity.getSourceNodeId());
        completion.setTargetNodeId(entity.getTargetNodeId());
        completion.setRequestParams(readParams(entity));
        completion.setResponse(response);
        try {
            if (Boolean.TRUE.equals(completionListener.apply(completion))) {
                synchronized (monitor) {
                    entity.setWakeDispatched(true);
                    entity.setUpdateTime(now());
                    repository.save(entity);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void recoverExpiredLeases() {
        synchronized (monitor) {
            long currentTime = System.currentTimeMillis();
            for (AiSessionMessageQueueEntity entity : repository.findByStatusInOrderByCreateTimeAscIdAsc(List.of(RUNNING))) {
                if (entity.getLeaseUntil() == null || entity.getLeaseUntil() <= currentTime) {
                    entity.setStatus(PENDING);
                    entity.setLeaseUntil(null);
                    entity.setNextAttemptTime(currentTime);
                    entity.setUpdateTime(now());
                    repository.save(entity);
                }
            }
        }
    }

    private AiSessionMessageQueueEntity newEntity(Map<String, Object> params, String requestId) {
        String timestamp = now();
        AiSessionMessageQueueEntity entity = new AiSessionMessageQueueEntity();
        entity.setQueueId(UUID.randomUUID().toString());
        entity.setSessionId(required(params, "sessionId"));
        entity.setSourceNodeId(stringValue(params.get("sourceNodeId")));
        entity.setTargetNodeId(required(params, "targetNodeId"));
        entity.setRequestId(requestId);
        entity.setStatus(PENDING);
        entity.setRequestPayloadJson(writeJson(params));
        entity.setAttemptCount(0);
        entity.setMaxAttempts(maxAttempts);
        entity.setNextAttemptTime(System.currentTimeMillis());
        entity.setDeferred(false);
        entity.setWakeRequired(false);
        entity.setWakeDispatched(false);
        entity.setCreateTime(timestamp);
        entity.setUpdateTime(timestamp);
        return entity;
    }

    private AiSessionMessageDispatchResult existingResult(AiSessionMessageQueueEntity entity) {
        if (SUCCEEDED.equals(entity.getStatus()) || FAILED_FINAL.equals(entity.getStatus())) {
            return completedResult(entity, readResponse(entity));
        }
        return queuedResult(entity);
    }

    private AiSessionMessageDispatchResult queuedResult(AiSessionMessageQueueEntity entity) {
        AiChatResponse response = new AiChatResponse();
        response.setStatus("QUEUED");
        response.setAnswer("目标节点当前正忙，消息已进入会话队列，将按顺序自动处理。");
        response.setTraceId(entity.getQueueId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("queueId", entity.getQueueId());
        metadata.put("sessionId", entity.getSessionId());
        metadata.put("targetNodeId", entity.getTargetNodeId());
        metadata.put("attemptCount", entity.getAttemptCount());
        response.setMetadata(metadata);
        AiSessionMessageDispatchResult result = new AiSessionMessageDispatchResult();
        result.setQueueId(entity.getQueueId());
        result.setStatus("QUEUED");
        result.setQueued(true);
        result.setResponse(response);
        return result;
    }

    private AiSessionMessageDispatchResult completedResult(AiSessionMessageQueueEntity entity, AiChatResponse response) {
        AiSessionMessageDispatchResult result = new AiSessionMessageDispatchResult();
        result.setQueueId(entity.getQueueId());
        result.setStatus(entity.getStatus());
        result.setQueued(false);
        result.setResponse(response == null ? failedResponse(new IllegalStateException(entity.getErrorMessage())) : response);
        return result;
    }

    private AiChatResponse failedResponse(RuntimeException error) {
        String message = error == null || isBlank(error.getMessage()) ? "Message dispatch failed" : error.getMessage();
        return new AiChatResponse(message, "FAILED", null);
    }

    private boolean isBusy(AiChatResponse response) {
        return "BUSY".equalsIgnoreCase(response.getStatus()) || "QUEUED".equalsIgnoreCase(response.getStatus());
    }

    private boolean isFinalFailure(RuntimeException error) {
        return error instanceof IllegalArgumentException
                || error instanceof SecurityException
                || error instanceof HttpClientErrorException;
    }

    private long retryDelay(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 6);
        return Math.min(baseRetryMs * (1L << exponent), 30000L);
    }

    private String queueKey(AiSessionMessageQueueEntity entity) {
        return entity.getSessionId() + "\u0000" + entity.getTargetNodeId();
    }

    private Map<String, Object> readParams(AiSessionMessageQueueEntity entity) {
        try {
            return objectMapper.readValue(entity.getRequestPayloadJson(), new TypeReference<>() { });
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to deserialize queued message", error);
        }
    }

    private AiChatResponse readResponse(AiSessionMessageQueueEntity entity) {
        if (isBlank(entity.getResponsePayloadJson())) {
            return null;
        }
        try {
            return objectMapper.readValue(entity.getResponsePayloadJson(), AiChatResponse.class);
        } catch (Exception error) {
            return failedResponse(new IllegalStateException("Failed to deserialize queued response", error));
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize queued message", error);
        }
    }

    private String required(Map<String, Object> params, String key) {
        String value = params == null ? null : stringValue(params.get(key));
        if (isBlank(value)) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String now() {
        return String.valueOf(System.currentTimeMillis());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int intProperty(String key, int defaultValue) {
        try {
            return Integer.parseInt(System.getProperty(key, String.valueOf(defaultValue)));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static long longProperty(String key, long defaultValue) {
        try {
            return Long.parseLong(System.getProperty(key, String.valueOf(defaultValue)));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
