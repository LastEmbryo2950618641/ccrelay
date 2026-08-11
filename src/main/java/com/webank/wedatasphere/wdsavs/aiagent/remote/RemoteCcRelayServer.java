package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiModelConfig;
import com.webank.wedatasphere.wdsavs.aiagent.model.AgentControlState;
import com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConvergencePolicy;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.ReactExecutionPolicy;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateProgress;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.service.A2aPayloadPolicyService;
import com.webank.wedatasphere.wdsavs.aiagent.service.A2aPayloadPolicyServiceImpl;
import com.webank.wedatasphere.wdsavs.aiagent.service.RelayGrantTokenService;
import com.webank.wedatasphere.wdsavs.aiagent.service.RelayGrantTokenServiceImpl;
import com.webank.wedatasphere.wdsavs.aiagent.service.RelayRequestSecurityService;
import com.webank.wedatasphere.wdsavs.aiagent.service.RelayRequestSecurityServiceImpl;
import com.webank.wedatasphere.wdsavs.aiagent.service.RelayRequestSecurityValidationResult;
import com.webank.wedatasphere.wdsavs.aiagent.service.SshDeployExecutorImpl;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;


import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class RemoteCcRelayServer {

    private static final String A2A_TASKS_BASE_PATH = "/api/ai/a2a/tasks";
    private static final String A2A_TASKS_CREATE_PATH = "/api/ai/a2a/tasks/create";

    private final RemoteCcRelayProperties properties;
    private final RemoteCcRelayService relayService;
    private final ReactAgentRunner reactAgentRunner;
    private final RemoteSelfReplicateService selfReplicateService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RelayGrantTokenService relayGrantTokenService = new RelayGrantTokenServiceImpl();
    private final RestTemplate restTemplate;
    private final A2aPayloadPolicyService payloadPolicyService;
    private final RelayRequestSecurityService requestSecurityService;
    private final ExecutorService taskExecutor = Executors.newCachedThreadPool();
    private final RemoteSessionExecutionGate sessionExecutionGate;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile long lastActivityTime = System.currentTimeMillis();
    private final ConcurrentMap<String, RemoteA2aTaskRecord> taskStore = new ConcurrentHashMap<>();
    private final RemoteSessionContextSynchronizer sessionContextSynchronizer;
    private final ConcurrentMap<String, SelfReplicateOperation> selfReplicateStore = new ConcurrentHashMap<>();
    private HttpServer server;
    private String localNodeId;

    public RemoteCcRelayServer(RemoteCcRelayProperties properties, RemoteCcRelayService relayService) {
        this(properties, relayService, new RemoteSelfReplicateService(new SshDeployExecutorImpl()));
    }

    public RemoteCcRelayServer(RemoteCcRelayProperties properties,
                               RemoteCcRelayService relayService,
                               RemoteSelfReplicateService selfReplicateService) {
        this.properties = properties;
        this.relayService = relayService;
        this.sessionExecutionGate = new RemoteSessionExecutionGate(
                properties == null ? 4 : properties.getMaxConcurrentSessions());
        this.reactAgentRunner = new ReactAgentRunner(relayService, new ReactCommandExecutor(properties));
        this.selfReplicateService = selfReplicateService;
        this.restTemplate = createRestTemplate(properties);
        this.payloadPolicyService = new A2aPayloadPolicyServiceImpl(
                properties == null ? 0L : properties.getA2aLargeFileThresholdBytes(),
                List.of(),
                properties == null ? List.of("*") : properties.getAllowedWorkRoots(),
                properties == null ? List.of("*") : properties.getAllowedLogRoots(),
                properties == null ? List.of("*") : properties.getAllowedCodeRoots());
        this.requestSecurityService = new RelayRequestSecurityServiceImpl();
        this.sessionContextSynchronizer = new RemoteSessionContextSynchronizer(
                restTemplate,
                requestSecurityService,
                new RemoteSessionContextStateStore(properties, objectMapper));
    }

    private static RestTemplate createRestTemplate(RemoteCcRelayProperties properties) {
        int timeoutMs = defaultHttpTimeoutMs(properties);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("Connection", "close");
            request.getHeaders().set("Proxy-Connection", "close");
            return execution.execute(request, body);
        });
        return restTemplate;
    }

    private static int defaultHttpTimeoutMs(RemoteCcRelayProperties properties) {
        long configured = properties == null ? 0L : properties.getTimeoutMs();
        if (configured <= 0L) {
            return 10000;
        }
        return (int) Math.max(1000L, Math.min(configured, Integer.MAX_VALUE));
    }
    public static void main(String[] args) throws Exception {
        RemoteCcRelayProperties properties = propertiesFromEnvironment();
        RemoteCcRelayServer server = new RemoteCcRelayServer(
                properties,
                new RemoteCcRelayService(properties, new LocalClaudeCodeCommandRunner())
        );
        server.start();
        System.out.println("WDSAVS remote CC relay started at http://" + properties.getHost() + ":" + properties.getPort() + properties.getPath());
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(properties.getHost(), properties.getPort()), 0);
        server.createContext(properties.getPath(), this::handleChat);
        server.createContext("/health", this::handleHealth);
        server.createContext("/ai-readiness", this::handleAiReadiness);
        server.createContext("/internal/grant/validate", this::handleGrantValidate);
        server.createContext("/internal/deploy/self-replicate", this::handleSelfReplicate);
        server.createContext(A2A_TASKS_CREATE_PATH, this::handleTaskCreate);
        server.createContext(A2A_TASKS_BASE_PATH, this::handleTaskRoutes);
        server.start();
        try {
            registerWithCenterIfConfigured();
            sendHeartbeatToCenter();
            scheduleHeartbeatIfConfigured();
        } catch (Exception e) {
            server.stop(0);
            throw new IOException("Failed to register remote relay with center", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
        }
        heartbeatExecutor.shutdownNow();
        taskExecutor.shutdownNow();
        sessionExecutionGate.shutdownNow();
    }

    public String getLocalNodeId() {
        return localNodeId;
    }

    private void registerWithCenterIfConfigured() throws IOException {
        if (properties == null || isBlank(properties.getCenterRegisterEndpoint())) {
            return;
        }
        RelayRegisterRequest request = new RelayRegisterRequest();
        request.setNodeId(readPersistedNodeId());
        request.setHost(resolveNodeHost());
        request.setPort(properties.getPort());
        request.setRelayEndpoint(resolveRelayEndpoint());
        request.setVersion(properties.getVersion());
        request.setProtocolVersion(properties.getProtocolVersion());
        request.setCapabilities(properties.getCapabilities());
        request.setWorkspaceRoot(firstNonBlank(properties.getWorkingDirectory(), System.getProperty("user.dir")));
        request.getEnvironmentSummary().put("nodeRole", firstNonBlank(properties.getNodeRole(), "RELAY"));
        request.getEnvironmentSummary().put("aiReadiness", aiReadinessStatus());
        RelayRegisterResponse response = postCenterRequest(properties.getCenterRegisterEndpoint(), request, RelayRegisterResponse.class, true);
        if (response == null || !Boolean.TRUE.equals(response.getAccepted()) || isBlank(response.getNodeId())) {
            throw new IOException("center register rejected remote relay");
        }
        localNodeId = response.getNodeId();
        properties.setNodeId(localNodeId);
        persistNodeId(localNodeId);
    }

    private String readPersistedNodeId() throws IOException {
        if (properties == null || isBlank(properties.getNodeIdFilePath())) {
            return null;
        }
        Path path = Path.of(properties.getNodeIdFilePath());
        if (!Files.exists(path)) {
            return null;
        }
        String nodeId = Files.readString(path, StandardCharsets.UTF_8);
        return isBlank(nodeId) ? null : nodeId.trim();
    }

    private void persistNodeId(String nodeId) throws IOException {
        if (properties == null || isBlank(properties.getNodeIdFilePath()) || isBlank(nodeId)) {
            return;
        }
        Path path = Path.of(properties.getNodeIdFilePath());
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, nodeId.trim(), StandardCharsets.UTF_8);
    }

    private String resolveNodeHost() {
        if (properties == null) {
            return null;
        }
        if (!isBlank(properties.getNodeHost())) {
            return properties.getNodeHost().trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return properties.getHost();
        }
    }

    private String resolveRelayEndpoint() {
        if (properties == null) {
            return null;
        }
        if (!isBlank(properties.getRelayEndpoint())) {
            return properties.getRelayEndpoint().trim();
        }
        return "http://" + properties.getHost() + ":" + properties.getPort() + properties.getPath();
    }

    private void scheduleHeartbeatIfConfigured() {
        if (isBlank(resolveCenterHeartbeatEndpoint()) || isBlank(localNodeId)) {
            return;
        }
        long intervalMs = properties == null || properties.getHeartbeatIntervalMs() <= 0L ? 30000L : properties.getHeartbeatIntervalMs();
        heartbeatFuture = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                sendHeartbeatToCenter();
            } catch (Exception e) {
                System.err.println("WDSAVS remote CC relay heartbeat failed: " + e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeatToCenter() {
        String endpoint = resolveCenterHeartbeatEndpoint();
        if (isBlank(endpoint) || isBlank(localNodeId)) {
            return;
        }
        postCenterRequest(endpoint, buildHeartbeatRequest(), Object.class, true);
    }

    private <T> T postCenterRequest(String endpoint, Object request, Class<T> responseType, boolean retryOnce) {
        RuntimeException lastFailure = null;
        int maxAttempts = retryOnce ? 3 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            RestTemplate client = attempt == 1 ? restTemplate : createRestTemplate(properties);
            try {
                return client.postForObject(endpoint, request, responseType);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (!retryOnce || !isRetryableCenterPostFailure(failure) || attempt >= maxAttempts) {
                    throw failure;
                }
                try {
                    Thread.sleep(attempt * 200L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
        throw lastFailure == null ? new IllegalStateException("Center request failed without exception detail") : lastFailure;
    }

    private boolean isRetryableCenterPostFailure(RuntimeException exception) {
        if (exception instanceof ResourceAccessException) {
            return true;
        }
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("unexpected end of file") || normalized.contains("i/o error") || normalized.contains("connection reset");
    }

    private RelayHeartbeatRequest buildHeartbeatRequest() {
        RelayHeartbeatRequest request = new RelayHeartbeatRequest();
        request.setNodeId(localNodeId);
        request.setStatus(properties != null && properties.isAiConfigReady() ? "AVAILABLE" : "AI_UNAVAILABLE");
        request.setActiveSessions(activeTaskCount());
        request.setMemoryUsage(Math.max(0L, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
        request.setLastTaskTime(String.valueOf(lastActivityTime));
        request.getDetail().put("relayEndpoint", resolveRelayEndpoint());
        request.getDetail().put("version", properties == null ? null : properties.getVersion());
        request.getDetail().put("protocolVersion", properties == null ? null : properties.getProtocolVersion());
        request.getDetail().put("nodeHost", resolveNodeHost());
        request.getDetail().put("nodeRole", properties == null ? "RELAY" : firstNonBlank(properties.getNodeRole(), "RELAY"));
        request.getDetail().put("aiReadiness", aiReadinessStatus());
        return request;
    }

    private String resolveCenterHeartbeatEndpoint() {
        if (properties == null) {
            return null;
        }
        if (!isBlank(properties.getCenterHeartbeatEndpoint())) {
            return properties.getCenterHeartbeatEndpoint().trim();
        }
        if (!isBlank(properties.getCenterRegisterEndpoint()) && properties.getCenterRegisterEndpoint().endsWith("/register")) {
            return properties.getCenterRegisterEndpoint().substring(0, properties.getCenterRegisterEndpoint().length() - "/register".length()) + "/heartbeat";
        }
        return null;
    }

    private int activeTaskCount() {
        int count = 0;
        for (RemoteA2aTaskRecord record : taskStore.values()) {
            if (record != null && record.future != null && !record.future.isDone()) {
                count++;
            }
        }
        return count;
    }

    private void touchActivity() {
        lastActivityTime = System.currentTimeMillis();
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, new AiChatResponse("Method not allowed", "FAILED", null));
            return;
        }
        try {
            AiChatRequest request = objectMapper.readValue(exchange.getRequestBody(), AiChatRequest.class);
            touchActivity();
            ensureRelayGrantIfPresent(request);
            AiChatResponse controlResponse = handleControlMessage(request);
            if (controlResponse != null) {
                write(exchange, 200, controlResponse);
                return;
            }
            String sessionId = sessionId(request);
            RemoteSessionExecutionGate.ImmediateResult<AiChatResponse> execution = sessionExecutionGate.tryExecute(
                    sessionId, () -> executeChat(request));
            if (execution.isBusy()) {
                AiChatResponse busy = new AiChatResponse(
                        "该节点正在处理同一会话中的上一条消息，请由 CC center 排队后重试。", "BUSY", null);
                busy.getMetadata().put("sessionId", sessionId);
                busy.getMetadata().put("retryable", true);
                write(exchange, 200, busy);
                return;
            }
            write(exchange, 200, execution.getValue());
        } catch (Exception e) {
            write(exchange, 500, new AiChatResponse(e.getMessage(), "FAILED", null));
        }
    }

    private void handleTaskCreate(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, errorTaskCreateResponse("Method not allowed"));
            return;
        }
        try {
            A2aTaskCreateRequest request = objectMapper.readValue(exchange.getRequestBody(), A2aTaskCreateRequest.class);
            touchActivity();
            Map<String, Object> params = request == null || request.getParams() == null ? Map.of() : new LinkedHashMap<>(request.getParams());
            ReactExecutionPolicy policy = normalizeExecutionPolicy(params);
            ensureRelayGrantForTaskParams(params, "A2A_TASK_CREATE");
            String taskId = taskIdForCreate(params);
            RemoteA2aTaskRecord record = new RemoteA2aTaskRecord();
            record.taskId = taskId;
            record.sessionId = stringValue(params.get("sessionId"));
            record.requestId = firstNonBlank(stringValue(params.get("requestId")), stringValue(params.get("idempotencyKey")));
            record.auditId = stringValue(params.get("auditId"));
            record.agentRunId = stringValue(params.get("agentRunId"));
            record.status = "PENDING";
            record.accepted = true;
            record.executionMode = policy.getExecutionMode();
            record.react = policy.toSummaryMap();
            record.controlState = initialControlState(record, params);
            record.createTime = now();
            record.updateTime = record.createTime;
            appendTaskEvent(record, "TASK_ACCEPTED", taskEventPayload(record, "accepted", true));
            taskStore.put(taskId, record);
            record.future = sessionExecutionGate.submit(record.sessionId, record.taskId,
                    () -> executeTask(record, params));

            A2aTaskCreateResponse response = new A2aTaskCreateResponse();
            response.setId(request == null ? null : request.getId());
            response.getResult().put("taskId", taskId);
            response.getResult().put("status", record.status);
            response.getResult().put("accepted", true);
            if (!isBlank(record.sessionId)) {
                response.getResult().put("sessionId", record.sessionId);
            }
            putIfNotBlank(response.getResult(), "requestId", record.requestId);
            putIfNotBlank(response.getResult(), "auditId", record.auditId);
            putIfNotBlank(response.getResult(), "agentRunId", record.agentRunId);
            response.getResult().put("executionMode", record.executionMode);
            response.getResult().put("react", record.react);
            write(exchange, 200, response);
        } catch (Exception e) {
            write(exchange, 500, errorTaskCreateResponse(e.getMessage()));
        }
    }

    private void handleTaskRoutes(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        touchActivity();
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals(A2A_TASKS_BASE_PATH) || path.equals(A2A_TASKS_BASE_PATH + "/")) {
            write(exchange, 404, Map.of("message", "Task path is required"));
            return;
        }
        if ("GET".equalsIgnoreCase(method)) {
            Map<String, Object> params = queryParams(exchange.getRequestURI().getRawQuery());
            if (path.endsWith("/events")) {
                handleTaskEvents(exchange, path, params);
            } else if (path.endsWith("/observation")) {
                handleTaskObservation(exchange, path, params);
            } else {
                handleTaskGet(exchange, path, params);
            }
            return;
        }
        if ("POST".equalsIgnoreCase(method) && path.endsWith("/cancel")) {
            Map<String, Object> params = new LinkedHashMap<>(queryParams(exchange.getRequestURI().getRawQuery()));
            params.putAll(bodyAsMap(exchange));
            handleTaskCancel(exchange, path, params);
            return;
        }
        write(exchange, 404, Map.of("message", "not found", "path", path, "method", method));
    }

    private void handleTaskEvents(HttpExchange exchange, String path, Map<String, Object> params) throws IOException {
        try {
            ensureRelayGrantForTaskParams(params, "A2A_TASK_GET");
            String taskId = taskIdFromEventsPath(path);
            RemoteA2aTaskRecord record = taskStore.get(taskId);
            if (record == null) {
                write(exchange, 404, Map.of("message", "task not found", "taskId", taskId));
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream;charset=UTF-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.getResponseHeaders().add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                long lastSequence = 0L;
                long startAt = System.currentTimeMillis();
                long lastKeepAliveAt = 0L;
                while (System.currentTimeMillis() - startAt < 10 * 60 * 1000L) {
                    try {
                        ensureRelayGrantForTaskParams(params, "A2A_TASK_GET");
                    } catch (SecurityException securityException) {
                        writeSseEvent(outputStream, "error", grantRevokedStreamEvent(taskId, lastSequence + 1, securityException.getMessage()));
                        outputStream.flush();
                        break;
                    }
                    boolean wrote = false;
                    Map<String, Object> terminalEvent = null;
                    for (Map<String, Object> event : record.events) {
                        long sequenceNo = longValue(event.get("sequenceNo"));
                        if (sequenceNo <= lastSequence) {
                            continue;
                        }
                        writeSseEvent(outputStream, "message", event);
                        wrote = true;
                        lastSequence = sequenceNo;
                        if (isTerminalEvent(event)) {
                            terminalEvent = event;
                        }
                    }
                    if (wrote) {
                        outputStream.flush();
                    }
                    if (terminalEvent != null) {
                        break;
                    }
                    long now = System.currentTimeMillis();
                    if (!wrote && now - lastKeepAliveAt >= 15000L) {
                        outputStream.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                        lastKeepAliveAt = now;
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                outputStream.write(": stream-end\n\n".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        } catch (Exception e) {
            write(exchange, 500, Map.of("message", e.getMessage(), "path", path));
        }
    }

    private void handleTaskObservation(HttpExchange exchange, String path, Map<String, Object> params) throws IOException {
        try {
            ensureRelayGrantForTaskParams(params, "A2A_TASK_OBSERVE");
            String taskId = taskIdFromObservationPath(path);
            RemoteA2aTaskRecord record = taskStore.get(taskId);
            if (record == null) {
                write(exchange, 404, Map.of("message", "task not found", "taskId", taskId));
                return;
            }
            write(exchange, 200, taskObservationView(record, params));
        } catch (Exception e) {
            write(exchange, 500, Map.of("message", e.getMessage(), "path", path));
        }
    }

    private void handleTaskGet(HttpExchange exchange, String path, Map<String, Object> params) throws IOException {
        try {
            ensureRelayGrantForTaskParams(params, "A2A_TASK_GET");
            String taskId = taskIdFromPath(path, false);
            RemoteA2aTaskRecord record = taskStore.get(taskId);
            if (record == null) {
                write(exchange, 404, Map.of("message", "task not found", "taskId", taskId));
                return;
            }
            write(exchange, 200, taskView(record));
        } catch (Exception e) {
            write(exchange, 500, Map.of("message", e.getMessage(), "path", path));
        }
    }

    private void handleTaskCancel(HttpExchange exchange, String path, Map<String, Object> params) throws IOException {
        try {
            ensureRelayGrantForTaskParams(params, "A2A_TASK_CANCEL");
            String taskId = taskIdFromPath(path, true);
            RemoteA2aTaskRecord record = taskStore.get(taskId);
            if (record == null) {
                write(exchange, 404, Map.of("message", "task not found", "taskId", taskId));
                return;
            }
            if (record.future != null && !record.future.isDone()) {
                record.future.cancel(true);
            }
            synchronized (record.controlState) {
                record.controlState.setStopRequested(true);
                record.controlState.setUpdateTime(now());
            }
            record.status = "CANCELLED";
            record.accepted = true;
            record.errorMessage = stringValue(params.get("reason"));
            record.updateTime = now();
            Map<String, Object> payload = taskEventPayload(record);
            putIfNotBlank(payload, "reason", record.errorMessage);
            appendTaskEvent(record, "TASK_CANCELLED", payload);
            write(exchange, 200, taskView(record));
        } catch (Exception e) {
            write(exchange, 500, Map.of("message", e.getMessage(), "path", path));
        }
    }

    private void executeTask(RemoteA2aTaskRecord record, Map<String, Object> params) {
        record.status = "RUNNING";
        touchActivity();
        record.updateTime = now();
        appendTaskEvent(record, "TASK_RUNNING", taskEventPayload(record));
        try {
            ReactExecutionPolicy policy = normalizeExecutionPolicy(params);
            record.executionMode = policy.getExecutionMode();
            record.react = policy.toSummaryMap();
            AiChatRequest taskRequest = toTaskChatRequest(params);
            RemoteSessionContextSynchronizer.SyncState contextSync =
                    sessionContextSynchronizer.synchronize(taskRequest, localNodeId);
            AiChatResponse response = payloadPolicyService.normalizeChatResponse(
                    reactAgentRunner.run(
                            taskRequest,
                            params,
                            policy,
                            (eventType, payload) -> appendTaskEvent(record, eventType, payload),
                            () -> controlStateSnapshot(record)
                    )
            );
            sessionContextSynchronizer.recordExecution(contextSync, response);
            record.status = isBlank(response.getStatus()) ? "SUCCESS" : response.getStatus().toUpperCase();
            record.answer = response.getAnswer();
            record.traceId = response.getTraceId();
            record.summary = response.getSummary();
            record.artifacts = response.getArtifacts() == null ? new ArrayList<>() : new ArrayList<>(response.getArtifacts());
            record.diagnostics = response.getDiagnostics() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.getDiagnostics());
            record.metadata = response.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.getMetadata());
            record.metadata.remove("executionMode");
            record.metadata.remove("react");
            record.updateTime = now();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", record.status);
            payload.put("executionMode", record.executionMode);
            payload.put("react", record.react);
            putIfNotBlank(payload, "requestId", record.requestId);
            putIfNotBlank(payload, "auditId", record.auditId);
            putIfNotBlank(payload, "agentRunId", record.agentRunId);
            putIfNotBlank(payload, "answer", record.answer);
            putIfNotBlank(payload, "traceId", record.traceId);
            putIfNotBlank(payload, "summary", record.summary);
            putIfNotEmpty(payload, "artifacts", record.artifacts);
            putIfNotEmpty(payload, "diagnostics", record.diagnostics);
            putIfNotEmpty(payload, "metadata", record.metadata);
            appendTaskEvent(record, terminalTaskEventType(record.status), payload);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || "CANCELLED".equals(record.status)) {
                record.status = "CANCELLED";
                appendTaskEvent(record, "TASK_CANCELLED", taskEventPayload(record));
            } else {
                record.status = "FAILED";
                record.errorMessage = e.getMessage();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", record.status);
                payload.put("executionMode", record.executionMode);
                payload.put("react", record.react);
                putIfNotBlank(payload, "errorMessage", record.errorMessage);
                appendTaskEvent(record, "TASK_FAILED", payload);
            }
            record.updateTime = now();
        }
    }

    private AiChatResponse executeChat(AiChatRequest request) {
        RemoteSessionContextSynchronizer.SyncState contextSync =
                sessionContextSynchronizer.synchronize(request, localNodeId);
        AiChatResponse response = payloadPolicyService.normalizeChatResponse(relayService.relay(request));
        sessionContextSynchronizer.recordExecution(contextSync, response);
        return response;
    }

    private String sessionId(AiChatRequest request) {
        if (request == null || request.getMetadata() == null) {
            return null;
        }
        return stringValue(request.getMetadata().get("sessionId"));
    }

    private String terminalTaskEventType(String status) {
        if ("SUCCESS".equals(status)) {
            return "TASK_SUCCEEDED";
        }
        if ("CANCELLED".equals(status)) {
            return "TASK_CANCELLED";
        }
        if ("TIMEOUT".equals(status)) {
            return "TASK_TIMEOUT";
        }
        return "TASK_FAILED";
    }


    private AiChatRequest toTaskChatRequest(Map<String, Object> params) {
        AiChatRequest request = new AiChatRequest();
        request.setSystemPrompt(stringValue(params.get("systemPrompt")));
        List<AiChatMessage> messages = parseMessages(params.get("messages"));
        if (messages.isEmpty()) {
            String prompt = stringValue(mapValue(params.get("input")).get("prompt"));
            if (!isBlank(prompt)) {
                messages = List.of(new AiChatMessage("user", prompt));
            }
        }
        request.setMessages(messages);
        request.setMetadata(taskMetadata(params));
        request.setModelConfig(parseModelConfig(mapValue(params.get("modelConfig"))));
        attachRelayGrantMetadata(request, params);
        return request;
    }

    private void attachRelayGrantMetadata(AiChatRequest request, Map<String, Object> params) {
        RelayGrantValidateRequest validateRequest = relayGrantFromTaskParams(params, null);
        if (request == null || validateRequest == null) {
            return;
        }
        Map<String, Object> metadata = request.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getMetadata());
        Map<String, Object> relayGrant = new LinkedHashMap<>();
        putIfNotBlank(relayGrant, "grantId", validateRequest.getGrantId());
        putIfNotBlank(relayGrant, "sessionId", validateRequest.getSessionId());
        putIfNotBlank(relayGrant, "sourceNodeId", validateRequest.getSourceNodeId());
        putIfNotBlank(relayGrant, "targetNodeId", validateRequest.getTargetNodeId());
        putIfNotBlank(relayGrant, "signedToken", validateRequest.getSignedToken());
        putIfNotBlank(relayGrant, "expiresAt", validateRequest.getExpiresAt());
        if (validateRequest.getAllowedCapabilities() != null && !validateRequest.getAllowedCapabilities().isEmpty()) {
            relayGrant.put("allowedCapabilities", validateRequest.getAllowedCapabilities());
        }
        putIfNotBlank(relayGrant, "centerGrantValidateEndpoint", resolveCenterGrantValidateEndpoint(params));
        metadata.put("relayGrant", relayGrant);
        putIfNotBlank(metadata, "centerGrantValidateEndpoint", resolveCenterGrantValidateEndpoint(params));
        request.setMetadata(metadata);
    }

    private AiChatResponse handleControlMessage(AiChatRequest request) {
        Map<String, Object> metadata = request == null ? Map.of() : mapValue(request.getMetadata());
        Map<String, Object> control = mapValue(metadata.get("control"));
        if (control.isEmpty()) {
            return null;
        }
        String taskId = firstNonBlank(stringValue(control.get("taskId")), stringValue(metadata.get("taskId")));
        if (isBlank(taskId)) {
            return new AiChatResponse("control.taskId is required", "FAILED", UUID.randomUUID().toString());
        }
        RemoteA2aTaskRecord record = taskStore.get(taskId);
        if (record == null) {
            return new AiChatResponse("task not found: " + taskId, "FAILED", UUID.randomUUID().toString());
        }
        String type = stringValue(control.get("type"));
        if ("INTERRUPT".equalsIgnoreCase(type) || "INJECT".equalsIgnoreCase(type)) {
            Map<String, Object> injected = new LinkedHashMap<>();
            injected.put("prompt", stringValue(control.get("prompt")));
            injected.put("createdTime", now());
            injected.put("type", "INTERRUPT");
            synchronized (record.controlState) {
                record.controlState.getInjectedPrompts().add(injected);
                record.controlState.setUpdateTime(now());
            }
            appendTaskEvent(record, "CONTROL_INJECTED", Map.of("taskId", taskId, "prompt", injected.get("prompt")));
            return controlAppliedResponse(record, "CONTROL_INJECTED");
        }
        if ("ADJUST".equalsIgnoreCase(type)) {
            Map<String, Object> patch = new LinkedHashMap<>();
            Map<String, Object> react = mapValue(control.get("react"));
            if (!react.isEmpty()) {
                patch.put("react", react);
            }
            synchronized (record.controlState) {
                record.controlState.setPolicyPatch(patch);
                record.controlState.setUpdateTime(now());
            }
            appendTaskEvent(record, "CONTROL_ADJUSTED", Map.of("taskId", taskId, "react", react));
            return controlAppliedResponse(record, "CONTROL_ADJUSTED");
        }
        if ("STOP".equalsIgnoreCase(type)) {
            synchronized (record.controlState) {
                record.controlState.setStopRequested(true);
                record.controlState.setUpdateTime(now());
            }
            appendTaskEvent(record, "TASK_STOP_REQUESTED", Map.of("taskId", taskId));
            return controlAppliedResponse(record, "TASK_STOP_REQUESTED");
        }
        return new AiChatResponse("Unsupported control type: " + type, "FAILED", UUID.randomUUID().toString());
    }

    private AiChatResponse controlAppliedResponse(RemoteA2aTaskRecord record, String eventType) {
        AiChatResponse response = new AiChatResponse(eventType, "SUCCESS", UUID.randomUUID().toString());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", record.taskId);
        metadata.put("status", record.status);
        metadata.put("controlEventType", eventType);
        metadata.put("controlState", controlStateSummary(record));
        response.setMetadata(metadata);
        return response;
    }

    private Map<String, Object> controlStateSummary(RemoteA2aTaskRecord record) {
        AgentControlState snapshot = controlStateSnapshot(record);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stopRequested", snapshot.getStopRequested());
        summary.put("injectedPromptCount", snapshot.getInjectedPrompts() == null ? 0 : snapshot.getInjectedPrompts().size());
        summary.put("policyPatch", snapshot.getPolicyPatch());
        summary.put("updateTime", snapshot.getUpdateTime());
        return summary;
    }

    private AgentControlState initialControlState(RemoteA2aTaskRecord record, Map<String, Object> params) {
        AgentControlState state = new AgentControlState();
        state.setSessionId(record.sessionId);
        state.setTaskId(record.taskId);
        state.setTargetNodeId(stringValue(params.get("targetNodeId")));
        state.setGrantId(stringValue(params.get("grantId")));
        state.setUpdateTime(now());
        return state;
    }

    private AgentControlState controlStateSnapshot(RemoteA2aTaskRecord record) {
        AgentControlState snapshot = new AgentControlState();
        if (record == null || record.controlState == null) {
            return snapshot;
        }
        synchronized (record.controlState) {
            snapshot.setSessionId(record.controlState.getSessionId());
            snapshot.setTaskId(record.controlState.getTaskId());
            snapshot.setTargetNodeId(record.controlState.getTargetNodeId());
            snapshot.setGrantId(record.controlState.getGrantId());
            snapshot.setStopRequested(record.controlState.getStopRequested());
            snapshot.setInjectedPrompts(record.controlState.getInjectedPrompts() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(record.controlState.getInjectedPrompts()));
            snapshot.setPolicyPatch(record.controlState.getPolicyPatch() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(record.controlState.getPolicyPatch()));
            snapshot.setUpdateTime(record.controlState.getUpdateTime());
        }
        return snapshot;
    }

    private Map<String, Object> taskMetadata(Map<String, Object> params) {
        Map<String, Object> metadata = mapValue(params.get("metadata"));
        putIfNotBlank(metadata, "sessionId", stringValue(params.get("sessionId")));
        putIfNotBlank(metadata, "sourceNodeId", stringValue(params.get("sourceNodeId")));
        putIfNotBlank(metadata, "targetNodeId", stringValue(params.get("targetNodeId")));
        putIfNotBlank(metadata, "centerContextDeltaEndpoint", stringValue(params.get("centerContextDeltaEndpoint")));
        putIfNotBlank(metadata, "contextHeadCursor", stringValue(params.get("contextHeadCursor")));
        putIfNotBlank(metadata, "agentRunId", stringValue(params.get("agentRunId")));
        ReactExecutionPolicy policy = ReactExecutionPolicy.fromParams(params);
        metadata.put("executionMode", policy.getExecutionMode());
        metadata.put("react", policy.toSummaryMap());
        return metadata;
    }

    private List<AiChatMessage> parseMessages(Object value) {
        List<AiChatMessage> messages = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return messages;
        }
        for (Object item : list) {
            Map<String, Object> messageMap = mapValue(item);
            AiChatMessage message = new AiChatMessage(
                    stringValue(messageMap.get("role")),
                    stringValue(messageMap.get("content")));
            message.setMetadata(mapValue(messageMap.get("metadata")));
            messages.add(message);
        }
        return messages;
    }

    private AiModelConfig parseModelConfig(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(source, AiModelConfig.class);
    }

    private String taskIdForCreate(Map<String, Object> params) {
        String taskId = stringValue(params.get("taskId"));
        if (!isBlank(taskId)) {
            return taskId;
        }
        taskId = stringValue(params.get("requestId"));
        if (!isBlank(taskId)) {
            return taskId;
        }
        taskId = stringValue(params.get("idempotencyKey"));
        if (!isBlank(taskId)) {
            return taskId;
        }
        return UUID.randomUUID().toString();
    }

    private String taskIdFromPath(String path, boolean cancelPath) {
        String normalized = path == null ? "" : path.trim();
        String suffix = cancelPath ? "/cancel" : "";
        if (!normalized.startsWith(A2A_TASKS_BASE_PATH + "/") || (cancelPath && !normalized.endsWith(suffix))) {
            throw new IllegalArgumentException("Invalid task path: " + path);
        }
        String taskPart = normalized.substring((A2A_TASKS_BASE_PATH + "/").length());
        if (cancelPath) {
            taskPart = taskPart.substring(0, taskPart.length() - suffix.length());
        }
        String decoded = URLDecoder.decode(taskPart, StandardCharsets.UTF_8);
        if (isBlank(decoded)) {
            throw new IllegalArgumentException("taskId is required");
        }
        return decoded;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyAsMap(HttpExchange exchange) throws IOException {
        if (exchange.getRequestBody() == null) {
            return Map.of();
        }
        Map<String, Object> body = objectMapper.readValue(exchange.getRequestBody(), Map.class);
        return body == null ? Map.of() : body;
    }

    private String taskIdFromEventsPath(String path) {
        String normalized = path == null ? "" : path.trim();
        String suffix = "/events";
        if (!normalized.startsWith(A2A_TASKS_BASE_PATH + "/") || !normalized.endsWith(suffix)) {
            throw new IllegalArgumentException("Invalid task path: " + path);
        }
        String taskPart = normalized.substring((A2A_TASKS_BASE_PATH + "/").length(), normalized.length() - suffix.length());
        String decoded = URLDecoder.decode(taskPart, StandardCharsets.UTF_8);
        if (isBlank(decoded)) {
            throw new IllegalArgumentException("taskId is required");
        }
        return decoded;
    }

    private String taskIdFromObservationPath(String path) {
        String normalized = path == null ? "" : path.trim();
        String suffix = "/observation";
        if (!normalized.startsWith(A2A_TASKS_BASE_PATH + "/") || !normalized.endsWith(suffix)) {
            throw new IllegalArgumentException("Invalid task path: " + path);
        }
        String taskPart = normalized.substring((A2A_TASKS_BASE_PATH + "/").length(), normalized.length() - suffix.length());
        String decoded = URLDecoder.decode(taskPart, StandardCharsets.UTF_8);
        if (isBlank(decoded)) {
            throw new IllegalArgumentException("taskId is required");
        }
        return decoded;
    }

    private Map<String, Object> taskObservationView(RemoteA2aTaskRecord record, Map<String, Object> params) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", record.taskId);
        view.put("targetNodeId", stringValue(params.get("targetNodeId")));
        view.put("status", record.status);
        view.put("currentStage", record.status);
        view.put("observationSource", "REMOTE_RELAY");
        view.put("task", taskView(record));
        view.put("events", observationEvents(record, params));
        view.put("controlState", controlStateSnapshot(record) == null ? Map.of() : controlStateMap(record));
        view.put("heartbeat", remoteHeartbeatSummary(record));
        view.put("window", observationWindow(params, record.events.size()));
        view.put("truncated", false);
        view.put("observationTime", now());
        view.put("authorizationScope", stringValue(params.getOrDefault("authorizationScope", "A2A_TASK_OBSERVE")));
        return view;
    }

    private List<Map<String, Object>> observationEvents(RemoteA2aTaskRecord record, Map<String, Object> params) {
        long sinceSequenceNo = longValue(params.get("sinceSequenceNo"));
        long sinceCreatedTimeMs = longValue(params.get("sinceCreatedTimeMs"));
        long lastMs = longValue(params.get("lastMs"));
        if (sinceCreatedTimeMs <= 0L && lastMs > 0L) {
            sinceCreatedTimeMs = Math.max(0L, System.currentTimeMillis() - lastMs);
        }
        int limit = intValue(params.get("limit"), 50);
        long maxBytes = longValue(params.get("maxBytes"));
        long perEventMaxBytes = longValue(params.get("perEventMaxBytes"));
        if (perEventMaxBytes <= 0L) {
            perEventMaxBytes = 8192L;
        }
        List<String> eventTypes = stringList(params.get("eventTypes"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> event : record.events) {
            long sequenceNo = longValue(event.get("sequenceNo"));
            long createdTimeMs = longValue(event.get("createdTime"));
            if (sinceSequenceNo > 0L && sequenceNo < sinceSequenceNo) {
                continue;
            }
            if (sinceCreatedTimeMs > 0L && createdTimeMs > 0L && createdTimeMs < sinceCreatedTimeMs) {
                continue;
            }
            if (!eventTypes.isEmpty() && !eventTypes.contains(stringValue(event.get("eventType")).toUpperCase())) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>(event);
            Object payload = normalized.get("payload");
            if (payload instanceof Map<?, ?> payloadMap) {
                normalized.put("payload", payloadPolicyService.normalizeStructuredResult(mapValue(payloadMap)));
            }
            String serialized = safeJson(normalized);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > perEventMaxBytes) {
                Map<String, Object> truncated = new LinkedHashMap<>();
                truncated.put("eventType", normalized.get("eventType"));
                truncated.put("sequenceNo", normalized.get("sequenceNo"));
                truncated.put("payloadTruncated", true);
                truncated.put("tail", tail(serialized, 20));
                normalized = truncated;
            }
            result.add(normalized);
            if (limit > 0 && result.size() >= limit) {
                break;
            }
            if (maxBytes > 0L && safeJson(result).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                result.remove(result.size() - 1);
                break;
            }
        }
        return result;
    }

    private Map<String, Object> controlStateMap(RemoteA2aTaskRecord record) {
        AgentControlState snapshot = controlStateSnapshot(record);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sessionId", snapshot.getSessionId());
        state.put("taskId", snapshot.getTaskId());
        state.put("targetNodeId", snapshot.getTargetNodeId());
        state.put("grantId", snapshot.getGrantId());
        state.put("stopRequested", snapshot.getStopRequested());
        state.put("injectedPrompts", snapshot.getInjectedPrompts());
        state.put("policyPatch", snapshot.getPolicyPatch());
        state.put("updateTime", snapshot.getUpdateTime());
        return state;
    }

    private Map<String, Object> remoteHeartbeatSummary(RemoteA2aTaskRecord record) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nodeId", localNodeId);
        summary.put("status", "AVAILABLE");
        summary.put("lastActivityTime", String.valueOf(lastActivityTime));
        summary.put("taskId", record == null ? null : record.taskId);
        return summary;
    }

    private Map<String, Object> observationWindow(Map<String, Object> params, int matchedCount) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("sinceSequenceNo", longValue(params.get("sinceSequenceNo")));
        window.put("sinceCreatedTimeMs", longValue(params.get("sinceCreatedTimeMs")));
        window.put("lastMs", longValue(params.get("lastMs")));
        window.put("limit", intValue(params.get("limit"), 50));
        window.put("tailLines", intValue(params.get("tailLines"), 100));
        window.put("maxBytes", longValue(params.get("maxBytes")));
        window.put("perEventMaxBytes", longValue(params.get("perEventMaxBytes")));
        window.put("matchedCount", matchedCount);
        window.put("returnedCount", matchedCount);
        window.put("truncated", false);
        return window;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String tail(String text, int lines) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split("\\R");
        if (lines <= 0 || parts.length <= lines) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = Math.max(0, parts.length - lines); i < parts.length; i++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    private void appendTaskEvent(RemoteA2aTaskRecord record, String eventType, Map<String, Object> payload) {
        if (record == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("taskId", record.taskId);
        event.put("sessionId", record.sessionId);
        putIfNotBlank(event, "requestId", record.requestId);
        putIfNotBlank(event, "auditId", record.auditId);
        putIfNotBlank(event, "agentRunId", record.agentRunId);
        putIfNotBlank(event, "traceId", record.traceId);
        event.put("eventType", eventType);
        event.put("sequenceNo", ++record.nextSequence);
        event.put("createdTime", now());
        event.put("payload", payload == null ? Map.of() : new LinkedHashMap<>(payload));
        record.events.add(event);
    }

    private void writeSseEvent(OutputStream outputStream, String eventName, Map<String, Object> event) throws IOException {
        outputStream.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write(("data: " + objectMapper.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> grantRevokedStreamEvent(String taskId, long sequenceNo, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("taskId", taskId);
        event.put("eventType", "TASK_AUTH_REVOKED");
        event.put("sequenceNo", sequenceNo);
        event.put("createdTime", now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "FAILED");
        payload.put("errorCode", "GRANT_REVOKED");
        payload.put("errorMessage", isBlank(message) ? "relayGrant center validation failed" : message);
        event.put("payload", payload);
        return event;
    }

    private boolean isTerminalEvent(Map<String, Object> event) {
        if (event == null) {
            return false;
        }
        Map<String, Object> payload = mapValue(event.get("payload"));
        String status = stringValue(payload.get("status"));
        if (isBlank(status)) {
            return false;
        }
        String normalized = status.toUpperCase();
        return "SUCCESS".equals(normalized) || "FAILED".equals(normalized) || "CANCELLED".equals(normalized) || "TIMEOUT".equals(normalized);
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private Map<String, Object> taskView(RemoteA2aTaskRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", record.taskId);
        view.put("status", record.status);
        view.put("accepted", record.accepted);
        putIfNotBlank(view, "sessionId", record.sessionId);
        putIfNotBlank(view, "requestId", record.requestId);
        putIfNotBlank(view, "auditId", record.auditId);
        putIfNotBlank(view, "agentRunId", record.agentRunId);
        view.put("executionMode", record.executionMode);
        view.put("react", record.react == null ? Map.of() : new LinkedHashMap<>(record.react));
        putIfNotBlank(view, "answer", record.answer);
        putIfNotBlank(view, "traceId", record.traceId);
        putIfNotBlank(view, "summary", record.summary);
        putIfNotEmpty(view, "artifacts", record.artifacts);
        putIfNotEmpty(view, "diagnostics", record.diagnostics);
        putIfNotEmpty(view, "metadata", record.metadata);
        putIfNotBlank(view, "errorMessage", record.errorMessage);
        putIfNotBlank(view, "createTime", record.createTime);
        putIfNotBlank(view, "updateTime", record.updateTime);
        return view;
    }

    private ReactExecutionPolicy normalizeExecutionPolicy(Map<String, Object> params) {
        ReactExecutionPolicy policy = ReactExecutionPolicy.fromParams(params);
        if (Boolean.TRUE.equals(policy.getEnabled()) && "ReAct".equalsIgnoreCase(policy.getMode())) {
            policy.setEnforcementMode(ReactExecutionPolicy.ENFORCEMENT_JAVA);
            policy.setEnforcementStatus(ReactExecutionPolicy.ENFORCEMENT_JAVA_STATUS);
        }
        policy.applyToParams(params);
        return policy;
    }

    private Map<String, Object> taskEventPayload(RemoteA2aTaskRecord record) {
        return taskEventPayload(record, null, null);
    }

    private Map<String, Object> taskEventPayload(RemoteA2aTaskRecord record, String extraKey, Object extraValue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (record == null) {
            return payload;
        }
        payload.put("status", record.status);
        payload.put("executionMode", record.executionMode);
        payload.put("react", record.react == null ? Map.of() : new LinkedHashMap<>(record.react));
        if (extraKey != null) {
            payload.put(extraKey, extraValue);
        }
        return payload;
    }

    private A2aTaskCreateResponse errorTaskCreateResponse(String message) {
        A2aTaskCreateResponse response = new A2aTaskCreateResponse();
        response.getResult().put("status", "FAILED");
        response.getResult().put("accepted", false);
        response.getResult().put("message", message);
        return response;
    }

    private void ensureRelayGrantIfPresent(AiChatRequest request) {
        RelayGrantValidateRequest validateRequest = relayGrantFromMetadata(request);
        ensureRelayGrantValid(validateRequest, resolveCenterGrantValidateEndpoint(request));
    }

    private void ensureRelayGrantForTaskParams(Map<String, Object> params, String capability) {
        ensureRelayGrantValid(relayGrantFromTaskParams(params, capability), resolveCenterGrantValidateEndpoint(params));
    }

    private void ensureRelayGrantValid(RelayGrantValidateRequest validateRequest, String centerGrantValidateEndpoint) {
        if (validateRequest == null) {
            return;
        }
        if (isBlank(validateRequest.getGrantId()) || isBlank(validateRequest.getSignedToken())) {
            throw new SecurityException("relayGrant metadata is incomplete");
        }
        boolean expired = validateRequest.getExpiresAt() != null && !validateRequest.getExpiresAt().trim().isEmpty()
                && System.currentTimeMillis() > Long.parseLong(validateRequest.getExpiresAt());
        boolean valid = !expired && relayGrantTokenService.validate(
                validateRequest.getSignedToken(),
                validateRequest.getGrantId(),
                validateRequest.getSessionId(),
                validateRequest.getSourceNodeId(),
                validateRequest.getTargetNodeId(),
                validateRequest.getAllowedCapabilities(),
                validateRequest.getExpiresAt());
        if (!valid) {
            throw new SecurityException(expired ? "relayGrant has expired" : "relayGrant validation failed");
        }
        if (!isBlank(centerGrantValidateEndpoint)) {
            RelayGrantValidateResponse validation = restTemplate.postForObject(centerGrantValidateEndpoint,
                    requestSecurityService.sign(validateRequest), RelayGrantValidateResponse.class);
            if (validation == null || !Boolean.TRUE.equals(validation.getValid())) {
                String detail = validation == null ? "empty validation response" : validation.getMessage();
                throw new SecurityException("relayGrant center validation failed: " + detail);
            }
        }
    }

    private String resolveCenterGrantValidateEndpoint(AiChatRequest request) {
        if (request == null || request.getMetadata() == null) {
            return properties.getCenterGrantValidateEndpoint();
        }
        Map<String, Object> metadata = mapValue(request.getMetadata());
        String endpoint = stringValue(metadata.get("centerGrantValidateEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        Map<String, Object> relayGrant = mapValue(metadata.get("relayGrant"));
        endpoint = stringValue(relayGrant.get("centerGrantValidateEndpoint"));
        return isBlank(endpoint) ? properties.getCenterGrantValidateEndpoint() : endpoint;
    }

    private String resolveCenterGrantValidateEndpoint(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return properties.getCenterGrantValidateEndpoint();
        }
        String endpoint = stringValue(params.get("centerGrantValidateEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        Map<String, Object> metadata = mapValue(params.get("metadata"));
        endpoint = stringValue(metadata.get("centerGrantValidateEndpoint"));
        return isBlank(endpoint) ? properties.getCenterGrantValidateEndpoint() : endpoint;
    }

    private RelayGrantValidateRequest relayGrantFromTaskParams(Map<String, Object> params, String capability) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        RelayGrantValidateRequest validateRequest = new RelayGrantValidateRequest();
        validateRequest.setGrantId(stringValue(params.get("grantId")));
        validateRequest.setSessionId(stringValue(params.get("sessionId")));
        validateRequest.setSourceNodeId(stringValue(params.get("sourceNodeId")));
        validateRequest.setTargetNodeId(stringValue(params.get("targetNodeId")));
        validateRequest.setSignedToken(stringValue(params.get("signedToken")));
        validateRequest.setExpiresAt(stringValue(params.get("expiresAt")));
        List<String> allowedCapabilities = stringList(params.get("allowedCapabilities"));
        if (!isBlank(capability)) {
            if (allowedCapabilities.isEmpty()) {
                allowedCapabilities = List.of(capability);
            } else if (!allowedCapabilities.contains(capability)) {
                throw new SecurityException("relayGrant capability is not sufficient: " + capability);
            }
        }
        validateRequest.setAllowedCapabilities(allowedCapabilities);
        return validateRequest.getGrantId() == null && validateRequest.getSignedToken() == null ? null : validateRequest;
    }

    private RelayGrantValidateRequest relayGrantFromMetadata(AiChatRequest request) {
        if (request == null || request.getMetadata() == null) {
            return null;
        }
        Map<String, Object> metadata = mapValue(request.getMetadata());
        Map<String, Object> relayGrant = mapValue(metadata.get("relayGrant"));
        if (relayGrant.isEmpty()) {
            return null;
        }
        RelayGrantValidateRequest validateRequest = new RelayGrantValidateRequest();
        validateRequest.setGrantId(stringValue(relayGrant.get("grantId")));
        validateRequest.setSessionId(stringValue(relayGrant.get("sessionId")));
        validateRequest.setSourceNodeId(stringValue(relayGrant.get("sourceNodeId")));
        validateRequest.setTargetNodeId(stringValue(relayGrant.get("targetNodeId")));
        validateRequest.setSignedToken(stringValue(relayGrant.get("signedToken")));
        validateRequest.setExpiresAt(stringValue(relayGrant.get("expiresAt")));
        validateRequest.setAllowedCapabilities(stringList(relayGrant.get("allowedCapabilities")));
        return validateRequest;
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        String text = stringValue(value);
        if (isBlank(text)) {
            return result;
        }
        String normalized = text.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        for (String item : normalized.split(",")) {
            String trimmed = item == null ? "" : item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        write(exchange, 200, Map.of(
                "status", "UP",
                "component", "REMOTE_CC_RELAY",
                "nodeId", localNodeId == null ? "" : localNodeId
        ));
    }

    private void handleAiReadiness(HttpExchange exchange) throws IOException {
        boolean ready = properties != null && properties.isAiConfigReady();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", ready ? "READY" : "RELAY_READY_AI_UNAVAILABLE");
        response.put("component", "REMOTE_CC_RELAY_AI");
        response.put("configReady", ready);
        response.put("modelConfigured", properties != null && properties.getModel() != null
                && !properties.getModel().isBlank());
        response.put("baseUrlConfigured", properties != null && properties.getBaseUrl() != null
                && !properties.getBaseUrl().isBlank());
        response.put("credentialConfigured", properties != null && properties.isApiKeyConfigured());
        response.put("canaryStatus", ready ? "CONFIGURED_CANARY_PENDING" : "NOT_RUN");
        response.put("model", properties == null ? null : properties.getModel());
        write(exchange, 200, response);
    }

    private String aiReadinessStatus() {
        return properties != null && properties.isAiConfigReady()
                ? "READY" : "RELAY_READY_AI_UNAVAILABLE";
    }

    private void handleGrantValidate(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, new RelayGrantValidateResponse(false, "FAILED", "Method not allowed"));
            return;
        }
        try {
            RelayGrantValidateRequest request = objectMapper.readValue(exchange.getRequestBody(), RelayGrantValidateRequest.class);
            RelayRequestSecurityValidationResult securityValidation = requestSecurityService.validate(request, true);
            if (!securityValidation.isValid()) {
                write(exchange, 200, new RelayGrantValidateResponse(false, securityValidation.getStatus(), securityValidation.getMessage()));
                return;
            }
            boolean expired = request.getExpiresAt() != null && !request.getExpiresAt().trim().isEmpty()
                    && System.currentTimeMillis() > Long.parseLong(request.getExpiresAt());
            boolean valid = !expired && relayGrantTokenService.validate(
                    request.getSignedToken(),
                    request.getGrantId(),
                    request.getSessionId(),
                    request.getSourceNodeId(),
                    request.getTargetNodeId(),
                    request.getAllowedCapabilities(),
                    request.getExpiresAt());
            write(exchange, 200, new RelayGrantValidateResponse(valid, valid ? "ACTIVE" : (expired ? "EXPIRED" : "INVALID"),
                    valid ? "Grant token is valid" : (expired ? "Grant token has expired" : "Grant token validation failed")));
        } catch (Exception e) {
            write(exchange, 500, new RelayGrantValidateResponse(false, "FAILED", e.getMessage()));
        }
    }
    private void handleSelfReplicate(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String basePath = "/internal/deploy/self-replicate";
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && basePath.equals(path)) {
                SelfReplicateRequest request = objectMapper.readValue(exchange.getRequestBody(), SelfReplicateRequest.class);
                touchActivity();
                SelfReplicateOperation operation = new SelfReplicateOperation(request);
                SelfReplicateOperation existing = selfReplicateStore.putIfAbsent(operation.getOperationId(), operation);
                if (existing != null) {
                    write(exchange, 200, existing.snapshot());
                    return;
                }
                taskExecutor.submit(() -> executeSelfReplicate(operation, request));
                write(exchange, 202, operation.snapshot());
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path.startsWith(basePath + "/")) {
                String operationId = path.substring((basePath + "/").length());
                SelfReplicateOperation operation = selfReplicateStore.get(operationId);
                if (operation == null) {
                    write(exchange, 404, new SelfReplicateResponse(false, false, "NOT_FOUND", "CHECK_TASK_ID",
                            null, null, "Self replicate operation not found"));
                    return;
                }
                write(exchange, 200, operation.snapshot());
                return;
            }
            write(exchange, 405, new SelfReplicateResponse(false, false, "FAILED", "METHOD_NOT_ALLOWED", null, null,
                    "Method not allowed"));
        } catch (Exception e) {
            write(exchange, 500, new SelfReplicateResponse(false, false, "FAILED", "CENTER_DEPLOY_FALLBACK", null, null,
                    e.getMessage()));
        }
    }

    private void executeSelfReplicate(SelfReplicateOperation operation, SelfReplicateRequest request) {
        try {
            SelfReplicateResponse response = selfReplicateService.execute(request, operation::updateProgress);
            operation.complete(response);
        } catch (Exception e) {
            operation.complete(new SelfReplicateResponse(true, false, "FAILED", "CENTER_DEPLOY_FALLBACK", null, "",
                    e.getMessage()));
        }
    }

    private Map<String, Object> queryParams(String rawQuery) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return params;
        }
        for (String part : rawQuery.split("&")) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            String[] entry = part.split("=", 2);
            String key = decodeQueryValue(entry[0]);
            String value = entry.length > 1 ? decodeQueryValue(entry[1]) : "";
            params.put(key, value);
        }
        return params;
    }

    private String decodeQueryValue(String value) {
        String current = value == null ? "" : value;
        for (int i = 0; i < 3; i++) {
            String decoded = URLDecoder.decode(current, StandardCharsets.UTF_8);
            if (decoded.equals(current)) {
                return decoded;
            }
            current = decoded;
        }
        return current;
    }
    private void putIfNotEmpty(Map<String, Object> target, String key, Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            target.put(key, value);
            return;
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            target.put(key, value);
        }
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value);
        }
    }

    private String now() {
        return String.valueOf(System.currentTimeMillis());
    }

    private void write(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    private static RemoteCcRelayProperties propertiesFromEnvironment() {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setHost(value("WDSAVS_CC_RELAY_HOST", properties.getHost()));
        properties.setPort(Integer.parseInt(value("WDSAVS_CC_RELAY_PORT", String.valueOf(properties.getPort()))));
        properties.setPath(value("WDSAVS_CC_RELAY_PATH", properties.getPath()));
        properties.setCommand(value("WDSAVS_CC_RELAY_COMMAND", properties.getCommand()));
        properties.setWorkingDirectory(value("WDSAVS_CC_RELAY_WORKDIR", properties.getWorkingDirectory()));
        properties.setCenterGrantValidateEndpoint(value("WDSAVS_AI_RELAY_GRANT_VALIDATE_ENDPOINT", properties.getCenterGrantValidateEndpoint()));
        properties.setCenterRegisterEndpoint(value("WDSAVS_AI_RELAY_REGISTER_ENDPOINT", properties.getCenterRegisterEndpoint()));
        properties.setCenterHeartbeatEndpoint(value("WDSAVS_AI_RELAY_HEARTBEAT_ENDPOINT", properties.getCenterHeartbeatEndpoint()));
        properties.setHeartbeatIntervalMs(Long.parseLong(value("WDSAVS_AI_RELAY_HEARTBEAT_INTERVAL_MS", String.valueOf(properties.getHeartbeatIntervalMs()))));
        properties.setNodeIdFilePath(value("WDSAVS_AI_RELAY_NODE_ID_FILE", properties.getNodeIdFilePath()));
        properties.setNodeHost(value("WDSAVS_AI_RELAY_NODE_HOST", properties.getNodeHost()));
        properties.setNodeId(value("WDSAVS_AI_RELAY_NODE_ID", properties.getNodeId()));
        properties.setNodeRole(value("WDSAVS_AI_RELAY_NODE_ROLE", properties.getNodeRole()));
        properties.setRelayEndpoint(value("WDSAVS_AI_RELAY_ENDPOINT", properties.getRelayEndpoint()));
        properties.setSystemPromptFilePath(value("WDSAVS_AI_RELAY_SYSTEM_PROMPT_FILE", properties.getSystemPromptFilePath()));
        properties.setClaudeSettingsFilePath(value("WDSAVS_CC_CLAUDE_SETTINGS_FILE", properties.getClaudeSettingsFilePath()));
        properties.setVersion(value("WDSAVS_AI_RELAY_VERSION", properties.getVersion()));
        properties.setProtocolVersion(value("WDSAVS_AI_RELAY_PROTOCOL_VERSION", properties.getProtocolVersion()));
        properties.setTimeoutMs(Long.parseLong(value("WDSAVS_CC_RELAY_TIMEOUT_MS", String.valueOf(properties.getTimeoutMs()))));
        properties.setMaxConcurrentSessions(Integer.parseInt(value("WDSAVS_CC_RELAY_MAX_CONCURRENT_SESSIONS",
                String.valueOf(properties.getMaxConcurrentSessions()))));
        properties.setModel(firstNonBlankValue(value("ANTHROPIC_MODEL", null), value("OPENAI_MODEL", null)));
        properties.setBaseUrl(firstNonBlankValue(value("ANTHROPIC_BASE_URL", null), value("OPENAI_BASE_URL", null)));
        properties.setApiKeyConfigured(!isBlankValue(firstNonBlankValue(
                value("ANTHROPIC_API_KEY", null), value("ANTHROPIC_AUTH_TOKEN", null),
                value("CLAUDE_CODE_API_KEY", null), value("OPENAI_API_KEY", null))));
        properties.setDefaultConvergencePolicy(convergencePolicyFromEnvironment(properties.getDefaultConvergencePolicy()));
        String arguments = value("WDSAVS_CC_RELAY_ARGS", String.join(" ", properties.getArguments()));
        properties.setArguments(arguments == null || arguments.trim().isEmpty()
                ? List.of()
                : Arrays.asList(arguments.trim().split("\\s+")));
        return properties;
    }

    private static ClaudeCodeConvergencePolicy convergencePolicyFromEnvironment(ClaudeCodeConvergencePolicy defaults) {
        ClaudeCodeConvergencePolicy policy = new ClaudeCodeConvergencePolicy();
        policy.setMaxRatSteps(integerValue("WDSAVS_CC_MAX_RAT_STEPS", defaults.getMaxRatSteps()));
        policy.setMaxRetries(integerValue("WDSAVS_CC_MAX_RETRIES", defaults.getMaxRetries()));
        policy.setMaxNoProgressRounds(integerValue("WDSAVS_CC_MAX_NO_PROGRESS_ROUNDS", defaults.getMaxNoProgressRounds()));
        policy.setMaxDurationMs(longValue("WDSAVS_CC_MAX_DURATION_MS", defaults.getMaxDurationMs()));
        policy.setRepeatedActionThreshold(integerValue("WDSAVS_CC_REPEATED_ACTION_THRESHOLD", defaults.getRepeatedActionThreshold()));
        policy.setRetryDelayMs(longValue("WDSAVS_CC_RETRY_DELAY_MS", defaults.getRetryDelayMs()));
        policy.setMaxPromptChars(integerValue("WDSAVS_CC_MAX_PROMPT_CHARS", defaults.getMaxPromptChars()));
        policy.setMaxResponseChars(integerValue("WDSAVS_CC_MAX_RESPONSE_CHARS", defaults.getMaxResponseChars()));
        policy.setHumanApprovalPauseTimeoutMs(longValue("WDSAVS_CC_HUMAN_APPROVAL_PAUSE_TIMEOUT_MS", defaults.getHumanApprovalPauseTimeoutMs()));
        policy.setEnableRepeatActionDetection(booleanValue("WDSAVS_CC_ENABLE_REPEAT_ACTION_DETECTION", defaults.getEnableRepeatActionDetection()));
        policy.setEnableHumanApprovalPause(booleanValue("WDSAVS_CC_ENABLE_HUMAN_APPROVAL_PAUSE", defaults.getEnableHumanApprovalPause()));
        return policy;
    }

    private static String value(String key, String defaultValue) {
        String property = System.getProperty(key);
        if (property != null) {
            return property;
        }
        String environment = System.getenv(key);
        return environment == null ? defaultValue : environment;
    }

    private static Integer integerValue(String key, Integer defaultValue) {
        String value = value(key, defaultValue == null ? null : String.valueOf(defaultValue));
        return value == null || value.trim().isEmpty() ? defaultValue : Integer.parseInt(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static Long longValue(String key, Long defaultValue) {
        String value = value(key, defaultValue == null ? null : String.valueOf(defaultValue));
        return value == null || value.trim().isEmpty() ? defaultValue : Long.parseLong(value);
    }

    private static Boolean booleanValue(String key, Boolean defaultValue) {
        String value = value(key, defaultValue == null ? null : String.valueOf(defaultValue));
        return value == null || value.trim().isEmpty() ? defaultValue : Boolean.parseBoolean(value);
    }

    private static String firstNonBlankValue(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean isBlankValue(String value) {
        return value == null || value.isBlank();
    }

    private final class SelfReplicateOperation {
        private final String operationId;
        private final long startedTime;
        private SelfReplicateProgress progress;
        private SelfReplicateResponse result;

        private SelfReplicateOperation(SelfReplicateRequest request) {
            this.operationId = isBlank(request.getTaskId()) ? UUID.randomUUID().toString() : request.getTaskId();
            this.startedTime = System.currentTimeMillis();
            SelfReplicateProgress initial = new SelfReplicateProgress();
            initial.setOperationId(operationId);
            initial.setTaskId(request.getTaskId());
            initial.setSourceNodeId(request.getSourceNodeId());
            initial.setTargetNodeId(request.getTargetNodeId());
            initial.setPhase("QUEUED");
            initial.setProgressPercent(0);
            initial.setBytesTransferred(0L);
            initial.setStartedTime(startedTime);
            initial.setUpdatedTime(startedTime);
            initial.setElapsedMs(0L);
            initial.setMessage("自复制任务已进入源 Relay 队列");
            this.progress = initial;
        }

        private String getOperationId() {
            return operationId;
        }

        private synchronized void updateProgress(SelfReplicateProgress update) {
            if (update == null) {
                return;
            }
            long updatedTime = update.getUpdatedTime() == null ? System.currentTimeMillis() : update.getUpdatedTime();
            long previousBytes = progress == null || progress.getBytesTransferred() == null ? 0L : progress.getBytesTransferred();
            boolean transferPhase = "COPYING_ARTIFACT".equalsIgnoreCase(update.getPhase());
            long currentBytes = update.getBytesTransferred() == null ? previousBytes : update.getBytesTransferred();
            if (!transferPhase && currentBytes == 0L && previousBytes > 0L) {
                currentBytes = previousBytes;
                update.setBytesTransferred(previousBytes);
            }
            long previousTime = progress == null || progress.getUpdatedTime() == null ? startedTime : progress.getUpdatedTime();
            long deltaTime = Math.max(1L, updatedTime - previousTime);
            long instantSpeed = Math.max(0L, currentBytes - previousBytes) * 1000L / deltaTime;
            Long previousSpeed = progress == null ? null : progress.getBytesPerSecond();
            Long rollingSpeed = null;
            if (transferPhase) {
                if (previousSpeed == null || previousSpeed <= 0L) {
                    rollingSpeed = instantSpeed > 0L ? Long.valueOf(instantSpeed) : null;
                } else {
                    rollingSpeed = Long.valueOf(Math.round(previousSpeed * 0.7d + instantSpeed * 0.3d));
                }
            }
            Long totalBytes = update.getTotalBytes() == null && progress != null
                    ? progress.getTotalBytes()
                    : update.getTotalBytes();
            update.setTotalBytes(totalBytes);
            Long remainingMs = totalBytes != null && totalBytes > currentBytes && rollingSpeed != null && rollingSpeed > 0L
                    ? Math.max(0L, totalBytes - currentBytes) * 1000L / rollingSpeed
                    : null;
            update.setOperationId(operationId);
            update.setStartedTime(startedTime);
            update.setUpdatedTime(updatedTime);
            update.setElapsedMs(Math.max(0L, updatedTime - startedTime));
            update.setBytesPerSecond(rollingSpeed);
            update.setEstimatedRemainingMs(remainingMs);
            this.progress = update;
        }

        private synchronized void complete(SelfReplicateResponse response) {
            this.result = response == null
                    ? new SelfReplicateResponse(true, false, "FAILED", "CENTER_DEPLOY_FALLBACK", null, "",
                    "Remote self replicate returned empty response")
                    : response;
            this.result.setOperationId(operationId);
            this.result.setProgress(progress);
        }

        private synchronized SelfReplicateResponse snapshot() {
            if (result != null) {
                result.setProgress(progress);
                return result;
            }
            SelfReplicateResponse running = new SelfReplicateResponse();
            running.setAccepted(true);
            running.setSuccess(null);
            running.setStatus("RUNNING");
            running.setNextAction("POLL_PROGRESS");
            running.setOperationId(operationId);
            running.setProgress(progress);
            return running;
        }
    }

    private static final class RemoteA2aTaskRecord {
        private String taskId;
        private String sessionId;
        private String requestId;
        private String auditId;
        private String agentRunId;
        private String executionMode;
        private Map<String, Object> react = new LinkedHashMap<>();
        private AgentControlState controlState = new AgentControlState();
        private String status;
        private Boolean accepted;
        private String answer;
        private String traceId;
        private String summary;
        private List<Map<String, Object>> artifacts = new ArrayList<>();
        private Map<String, Object> diagnostics = new LinkedHashMap<>();
        private Map<String, Object> metadata = new LinkedHashMap<>();
        private String errorMessage;
        private String createTime;
        private String updateTime;
        private List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        private long nextSequence;
        private Future<?> future;
    }
}















