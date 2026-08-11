package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCancelRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskStatusView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class AiTaskLifecycleServiceImpl implements AiTaskLifecycleService {

    private static final long DEFAULT_TASK_TIMEOUT_MS = 600_000L;
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "FAILED", "CANCELLED", "TIMEOUT");
    private static final Set<String> TIMEOUT_PAUSED_STATUSES = Set.of("WAITING_USER_INPUT");
    private static final List<String> RECOVERABLE_STATUSES = List.of(
            "PENDING", "RUNNING", "WAITING_APPROVAL", "WAITING_DEPLOY", "WAITING_USER_INPUT", "PARTIAL_SUCCESS"
    );

    private final AiTaskRepository taskRepository;
    private final AiSessionService sessionService;
    private final AiTaskEventService taskEventService;
    private final AiRelayDeployService relayDeployService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${wdsavs.ai.task.default-timeout-ms:600000}")
    private long defaultTimeoutMs = DEFAULT_TASK_TIMEOUT_MS;

    @Value("${wdsavs.ai.task.cancel-enabled:true}")
    private boolean cancelEnabled = true;

    @Value("${wdsavs.ai.task.recover-on-startup:true}")
    private boolean recoverTasksOnStartup = true;

    public AiTaskLifecycleServiceImpl(AiTaskRepository taskRepository,
                                      AiSessionService sessionService,
                                      AiTaskEventService taskEventService,
                                      AiRelayDeployService relayDeployService) {
        this.taskRepository = taskRepository;
        this.sessionService = sessionService;
        this.taskEventService = taskEventService;
        this.relayDeployService = relayDeployService;
    }

    @PostConstruct
    public void recoverOnStartup() {
        if (recoverTasksOnStartup) {
            recoverInterruptedTasks();
        }
    }

    @Override
    public AiTaskCreateResponse createTask(AiTaskCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AiTaskCreateRequest is required");
        }
        sessionService.validateSession(request.getSessionId());
        AiTaskEntity parentTask = validateParentTask(request.getParentTaskId(), request.getSessionId());
        String requestId = normalizedRequestId(request.getSessionId(), request.getRequestId());
        if (requestId != null) {
            AiTaskEntity existing = taskRepository.findByRequestId(requestId).orElse(null);
            if (existing != null) {
                return new AiTaskCreateResponse(existing.getTaskId(), existing.getStatus(), true, existing.getRequestId(), traceId(existing), null);
            }
        }
        String taskId = normalizedTaskId(request.getTaskId());
        if (taskId != null) {
            AiTaskEntity existing = taskRepository.findByTaskId(taskId).orElse(null);
            if (existing != null) {
                return new AiTaskCreateResponse(existing.getTaskId(), existing.getStatus(), true, existing.getRequestId(), traceId(existing), null);
            }
        }
        String now = String.valueOf(System.currentTimeMillis());
        String taskType = normalizeTaskType(request.getTaskType());
        boolean deployRelay = "DEPLOY_RELAY".equalsIgnoreCase(taskType);

        AiTaskEntity entity = new AiTaskEntity();
        entity.setTaskId(taskId == null ? UUID.randomUUID().toString() : taskId);
        entity.setSessionId(request.getSessionId());
        entity.setParentTaskId(parentTask == null ? normalizedParentTaskId(request.getParentTaskId()) : parentTask.getTaskId());
        entity.setRequestId(requestId);
        entity.setTaskType(taskType);
        entity.setSourceNodeId(request.getSourceNodeId());
        entity.setTargetNodeId(request.getTargetNodeId());
        entity.setStatus(deployRelay ? "WAITING_DEPLOY" : "PENDING");
        entity.setCurrentStage(deployRelay ? "WAITING_DEPLOY" : "CREATED");
        entity.setTimeoutMs(effectiveTimeoutMs(request.getTimeoutMs()));
        entity.setRetryCount(0);
        entity.setMaxRetries(0);
        entity.setRequestPayloadJson(writeJson(request.getPayload()));
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        taskRepository.saveAndFlush(entity);

        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("taskType", entity.getTaskType());
        eventPayload.put("status", entity.getStatus());
        eventPayload.put("currentStage", entity.getCurrentStage());
        eventPayload.put("requestId", entity.getRequestId());
        eventPayload.put("parentTaskId", entity.getParentTaskId());
        eventPayload.put("traceId", traceId(request.getPayload()));
        eventPayload.put("agentRunId", agentRunId(request.getPayload()));
        if (request.getPayload() != null) {
            Object executionMode = request.getPayload().get("executionMode");
            Object react = request.getPayload().get("react");
            if (executionMode != null) {
                eventPayload.put("executionMode", executionMode);
            }
            if (react != null) {
                eventPayload.put("react", react);
            }
        }
        taskEventService.appendEvent(entity.getTaskId(), entity.getSessionId(), "TASK_CREATED", 1L, eventPayload);
        syncParentOnChildTaskCreated(parentTask, entity, request, now);

        if (deployRelay) {
            relayDeployService.triggerDeployAsync(entity.getTaskId(), request);
        }
        return new AiTaskCreateResponse(entity.getTaskId(), entity.getStatus(), true, entity.getRequestId(), traceId(request.getPayload()), null);
    }

    @Override
    public AiTaskView getTask(String taskId) {
        return toView(markTimedOutIfNeeded(findTask(taskId)));
    }

    @Override
    public AiTaskStatusView getTaskStatus(String taskId) {
        AiTaskEntity entity = markTimedOutIfNeeded(findTask(taskId));
        AiTaskStatusView view = new AiTaskStatusView();
        view.setTaskId(entity.getTaskId());
        view.setStatus(entity.getStatus());
        view.setCurrentStage(entity.getCurrentStage());
        view.setProgress(progress(entity.getStatus()));
        view.setLastEventTime(entity.getUpdateTime());
        return view;
    }

    @Override
    public Boolean cancelTask(String taskId, AiTaskCancelRequest request) {
        if (!cancelEnabled) {
            throw new IllegalStateException("Task cancellation is disabled");
        }
        AiTaskEntity entity = markTimedOutIfNeeded(findTask(taskId));
        if (isTerminal(entity.getStatus())) {
            return Boolean.TRUE;
        }
        entity.setStatus("CANCELLED");
        entity.setCurrentStage("CANCELLED");
        entity.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        taskRepository.save(entity);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", request == null ? null : request.getReason());
        payload.put("status", entity.getStatus());
        taskEventService.appendEvent(entity.getTaskId(), entity.getSessionId(), "TASK_CANCELLED", nextSequence(entity.getTaskId()), payload);
        return Boolean.TRUE;
    }

    @Override
    public int recoverInterruptedTasks() {
        int recovered = 0;
        Set<String> processedTaskIds = new HashSet<>();
        for (String status : RECOVERABLE_STATUSES) {
            for (AiTaskEntity entity : tasksByStatus(status)) {
                if (entity == null || entity.getTaskId() == null || !processedTaskIds.add(entity.getTaskId())) {
                    continue;
                }
                AiTaskEntity current = markTimedOutIfNeeded(entity);
                if (isTerminal(current.getStatus())) {
                    recovered++;
                    continue;
                }
                String previousStatus = current.getStatus();
                String previousStage = current.getCurrentStage();
                boolean deployRelayTask = "DEPLOY_RELAY".equalsIgnoreCase(current.getTaskType());
                if (deployRelayTask && "SSH_CREDENTIAL_REQUIRED".equalsIgnoreCase(previousStage)) {
                    current.setUpdateTime(String.valueOf(System.currentTimeMillis()));
                    taskRepository.save(current);
                    Map<String, Object> recoveredPayload = recoveredPayload(current, previousStatus, previousStage, "WAIT_USER_INPUT");
                    appendTaskEvent(current, "TASK_RECOVERED", recoveredPayload);
                    recovered++;
                    continue;
                }
                if (deployRelayTask && isInterruptedDeployExecution(previousStatus, previousStage)) {
                    current.setStatus("WAITING_DEPLOY");
                    current.setCurrentStage("RECOVERED_DEPLOY_RETRY");
                    current.setUpdateTime(String.valueOf(System.currentTimeMillis()));
                    taskRepository.save(current);
                    Map<String, Object> recoveredPayload = recoveredPayload(current, previousStatus, previousStage, "RETRIGGER_DEPLOY");
                    appendTaskEvent(current, "TASK_RECOVERED", recoveredPayload);
                    relayDeployService.triggerDeployAsync(current.getTaskId(), toRecoveryCreateRequest(current));
                    recovered++;
                    continue;
                }
                if (deployRelayTask && isAwaitingDeployReport(previousStatus, previousStage)) {
                    current.setUpdateTime(String.valueOf(System.currentTimeMillis()));
                    taskRepository.save(current);
                    Map<String, Object> recoveredPayload = recoveredPayload(current, previousStatus, previousStage, "WAIT_RELAY_REPORT");
                    appendTaskEvent(current, "TASK_RECOVERED", recoveredPayload);
                    recovered++;
                    continue;
                }
                if ("RUNNING".equals(current.getStatus()) || "PARTIAL_SUCCESS".equals(current.getStatus())) {
                    current.setStatus("PENDING");
                    current.setCurrentStage("RECOVERED_PENDING");
                    current.setUpdateTime(String.valueOf(System.currentTimeMillis()));
                    taskRepository.save(current);
                } else if (current.getCurrentStage() == null || current.getCurrentStage().trim().isEmpty()) {
                    current.setCurrentStage("RECOVERED_WAITING");
                    current.setUpdateTime(String.valueOf(System.currentTimeMillis()));
                    taskRepository.save(current);
                }
                Map<String, Object> recoveredPayload = recoveredPayload(current, previousStatus, previousStage,
                        "DEPLOY_RELAY".equalsIgnoreCase(current.getTaskType()) && "WAITING_DEPLOY".equals(current.getStatus())
                                ? "TRIGGER_DEPLOY" : "MARK_RECOVERED");
                appendTaskEvent(current, "TASK_RECOVERED", recoveredPayload);
                if ("DEPLOY_RELAY".equalsIgnoreCase(current.getTaskType()) && "WAITING_DEPLOY".equals(current.getStatus())) {
                    relayDeployService.triggerDeployAsync(current.getTaskId(), toCreateRequest(current));
                }
                recovered++;
            }
        }
        return recovered;
    }

    private AiTaskEntity findTask(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private AiTaskEntity markTimedOutIfNeeded(AiTaskEntity entity) {
        if (entity == null || isTerminal(entity.getStatus()) || isTimeoutPaused(entity.getStatus())) {
            return entity;
        }
        long timeoutMs = effectiveTimeoutMs(entity.getTimeoutMs());
        long startAt = parseTime(entity.getStartTime(), parseTime(entity.getCreateTime(), 0L));
        if (startAt <= 0L || System.currentTimeMillis() - startAt <= timeoutMs) {
            return entity;
        }
        entity.setStatus("TIMEOUT");
        entity.setCurrentStage("TIMEOUT");
        entity.setErrorCode("TASK_TIMEOUT");
        entity.setErrorMessage("Task exceeded timeoutMs=" + timeoutMs);
        String now = String.valueOf(System.currentTimeMillis());
        entity.setEndTime(now);
        entity.setUpdateTime(now);
        taskRepository.save(entity);
        Map<String, Object> timeoutPayload = new LinkedHashMap<>();
        timeoutPayload.put("status", entity.getStatus());
        timeoutPayload.put("currentStage", entity.getCurrentStage());
        timeoutPayload.put("errorCode", entity.getErrorCode());
        timeoutPayload.put("errorMessage", entity.getErrorMessage());
        timeoutPayload.put("requestId", entity.getRequestId());
        appendTaskEvent(entity, "TASK_TIMEOUT", timeoutPayload);
        return entity;
    }

    private void syncParentOnChildTaskCreated(AiTaskEntity parentTask, AiTaskEntity childTask, AiTaskCreateRequest request, String now) {
        if (parentTask == null || childTask == null) {
            return;
        }
        Map<String, Object> parentResult = readJson(parentTask.getResultJson());
        parentResult.put("latestChildTaskId", childTask.getTaskId());
        parentResult.put("latestChildTaskType", childTask.getTaskType());
        parentResult.put("latestChildStatus", childTask.getStatus());
        parentResult.put("latestChildStage", childTask.getCurrentStage());
        parentResult.put("latestChildRequestId", childTask.getRequestId());
        if (request != null && request.getPayload() != null) {
            Object grantId = request.getPayload().get("grantId");
            if (grantId != null) {
                parentResult.put("latestChildGrantId", String.valueOf(grantId));
            }
        }
        if (!isTerminal(parentTask.getStatus())) {
            if ("DEPLOY_RELAY".equalsIgnoreCase(childTask.getTaskType())) {
                parentTask.setStatus("WAITING_DEPLOY");
                parentTask.setCurrentStage("CHILD_DEPLOY_CREATED");
                parentTask.setErrorCode(null);
                parentTask.setErrorMessage(null);
            } else {
                parentTask.setCurrentStage("CHILD_TASK_CREATED");
            }
        }
        parentTask.setUpdateTime(now);
        parentTask.setResultJson(writeJson(parentResult));
        taskRepository.save(parentTask);

        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("parentTaskId", parentTask.getTaskId());
        eventPayload.put("childTaskId", childTask.getTaskId());
        eventPayload.put("childTaskType", childTask.getTaskType());
        eventPayload.put("childStatus", childTask.getStatus());
        eventPayload.put("childStage", childTask.getCurrentStage());
        eventPayload.put("childRequestId", childTask.getRequestId());
        eventPayload.put("traceId", traceId(request == null ? null : request.getPayload()));
        taskEventService.appendEvent(parentTask.getTaskId(), parentTask.getSessionId(), "CHILD_TASK_CREATED",
                nextSequence(parentTask.getTaskId()), eventPayload);
    }

    private AiTaskEntity validateParentTask(String parentTaskId, String sessionId) {
        if (parentTaskId == null || parentTaskId.trim().isEmpty()) {
            return null;
        }
        AiTaskEntity parent = findTask(parentTaskId.trim());
        if (!parent.getSessionId().equals(sessionId)) {
            throw new IllegalArgumentException("parentTaskId does not belong to sessionId: " + sessionId);
        }
        return parent;
    }

    private AiTaskView toView(AiTaskEntity entity) {
        Map<String, Object> result = readJson(entity.getResultJson());
        Map<String, Object> requestPayload = readJson(entity.getRequestPayloadJson());
        AiTaskView view = new AiTaskView();
        view.setTaskId(entity.getTaskId());
        view.setSessionId(entity.getSessionId());
        view.setParentTaskId(entity.getParentTaskId());
        view.setRequestId(entity.getRequestId());
        view.setTraceId(traceId(entity));
        view.setAgentRunId(firstNonBlank(agentRunId(result), agentRunId(requestPayload)));
        view.setTaskType(entity.getTaskType());
        view.setStatus(entity.getStatus());
        view.setCurrentStage(entity.getCurrentStage());
        view.setSourceNodeId(entity.getSourceNodeId());
        view.setTargetNodeId(entity.getTargetNodeId());
        view.setResult(result);
        view.setErrorCode(entity.getErrorCode());
        view.setErrorMessage(entity.getErrorMessage());
        view.setStartTime(entity.getStartTime());
        view.setEndTime(entity.getEndTime());
        return view;
    }

    private String normalizedTaskId(String taskId) {
        return taskId == null || taskId.trim().isEmpty() ? null : taskId.trim();
    }

    private String normalizeTaskType(String taskType) {
        if (taskType == null || taskType.trim().isEmpty()) {
            return "GENERIC";
        }
        return taskType.trim().toUpperCase();
    }

    private String normalizedRequestId(String sessionId, String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return null;
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return requestId.trim();
        }
        return sessionId.trim() + ":" + requestId.trim();
    }

    private String normalizedParentTaskId(String parentTaskId) {
        return parentTaskId == null || parentTaskId.trim().isEmpty() ? null : parentTaskId.trim();
    }

    private AiTaskCreateRequest toCreateRequest(AiTaskEntity entity) {
        AiTaskCreateRequest request = new AiTaskCreateRequest();
        request.setSessionId(entity.getSessionId());
        request.setRequestId(entity.getRequestId());
        request.setParentTaskId(entity.getParentTaskId());
        request.setTaskType(entity.getTaskType());
        request.setSourceNodeId(entity.getSourceNodeId());
        request.setTargetNodeId(entity.getTargetNodeId());
        request.setPayload(readJson(entity.getRequestPayloadJson()));
        request.setTimeoutMs(entity.getTimeoutMs());
        return request;
    }

    private AiTaskCreateRequest toRecoveryCreateRequest(AiTaskEntity entity) {
        AiTaskCreateRequest request = toCreateRequest(entity);
        Map<String, Object> payload = request.getPayload() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getPayload());
        payload.put("forceRecoverDeploy", Boolean.TRUE);
        payload.put("recoverReason", "CENTER_RESTART_INTERRUPTED_DEPLOY");
        request.setPayload(payload);
        return request;
    }

    private Map<String, Object> recoveredPayload(AiTaskEntity entity, String previousStatus,
                                                 String previousStage, String recoveryAction) {
        Map<String, Object> recoveredPayload = new LinkedHashMap<>();
        recoveredPayload.put("status", entity.getStatus());
        recoveredPayload.put("currentStage", entity.getCurrentStage());
        recoveredPayload.put("previousStatus", previousStatus);
        recoveredPayload.put("previousStage", previousStage);
        recoveredPayload.put("recoveryAction", recoveryAction);
        recoveredPayload.put("requestId", entity.getRequestId());
        return recoveredPayload;
    }

    private boolean isInterruptedDeployExecution(String status, String currentStage) {
        if (!"RUNNING".equalsIgnoreCase(valueOrEmpty(status))) {
            return false;
        }
        String stage = valueOrEmpty(currentStage).toUpperCase();
        return "SELF_REPLICATING".equals(stage) || "SSH_DISTRIBUTING".equals(stage);
    }

    private boolean isAwaitingDeployReport(String status, String currentStage) {
        String normalizedStatus = valueOrEmpty(status).toUpperCase();
        String stage = valueOrEmpty(currentStage).toUpperCase();
        return ("PARTIAL_SUCCESS".equals(normalizedStatus) || "WAITING_DEPLOY".equals(normalizedStatus))
                && ("WAIT_REGISTER".equals(stage) || "WAIT_HEALTH".equals(stage)
                || "REGISTERED_PENDING_HEALTH".equals(stage));
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private List<AiTaskEntity> tasksByStatus(String status) {
        List<AiTaskEntity> tasks = taskRepository.findByStatusOrderByCreateTimeAsc(status);
        return tasks == null ? List.of() : tasks;
    }

    private void appendTaskEvent(AiTaskEntity entity, String eventType, Map<String, Object> payload) {
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        if (payload != null) {
            eventPayload.putAll(payload);
        }
        eventPayload.put("traceId", traceId(entity));
        taskEventService.appendEvent(entity.getTaskId(), entity.getSessionId(), eventType, nextSequence(entity.getTaskId()), eventPayload);
    }

    private boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status);
    }

    private boolean isTimeoutPaused(String status) {
        return status != null && TIMEOUT_PAUSED_STATUSES.contains(status);
    }

    private long effectiveTimeoutMs(Long timeoutMs) {
        if (timeoutMs != null && timeoutMs > 0L) {
            return timeoutMs;
        }
        return defaultTimeoutMs > 0L ? defaultTimeoutMs : DEFAULT_TASK_TIMEOUT_MS;
    }

    private long parseTime(String value, long fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    void setCancelEnabled(boolean cancelEnabled) {
        this.cancelEnabled = cancelEnabled;
    }

    void setRecoverTasksOnStartup(boolean recoverTasksOnStartup) {
        this.recoverTasksOnStartup = recoverTasksOnStartup;
    }

    private String traceId(AiTaskEntity entity) {
        if (entity == null) {
            return null;
        }
        String value = traceId(readJson(entity.getResultJson()));
        return value != null ? value : traceId(readJson(entity.getRequestPayloadJson()));
    }

    private String traceId(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get("traceId");
        return value == null ? null : String.valueOf(value);
    }

    private String agentRunId(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get("agentRunId");
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize task payload", e);
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Integer progress(String status) {
        return switch (status) {
            case "SUCCESS" -> 100;
            case "FAILED", "CANCELLED", "TIMEOUT" -> 100;
            case "RUNNING", "PARTIAL_SUCCESS" -> 50;
            case "WAITING_DEPLOY", "WAITING_APPROVAL" -> 25;
            default -> 0;
        };
    }

    private Long nextSequence(String taskId) {
        return (long) (taskEventService.listEvents(taskId).size() + 1);
    }
}


