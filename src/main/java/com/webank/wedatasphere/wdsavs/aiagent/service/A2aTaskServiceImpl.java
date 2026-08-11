package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCancelRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;
import com.webank.wedatasphere.wdsavs.aiagent.model.CapabilityCode;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.model.ReactExecutionPolicy;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class A2aTaskServiceImpl implements A2aTaskService {

    private final AiTaskLifecycleService taskLifecycleService;
    private final AiTaskEventService taskEventService;
    private final AiRelayGrantService relayGrantService;
    private final AiRelayRegistryService relayRegistryService;
    private final AiAuditService auditService;
    private final AiTaskRepository taskRepository;
    private AiAgentRunService agentRunService;
    private final RestTemplate restTemplate;
    private final A2aPayloadPolicyService payloadPolicyService;
    private final RelayRequestSecurityService requestSecurityService;
    private final boolean centerForwardFallbackEnabled;
    private AiSessionContextService sessionContextService;
    private AiSessionCollaborationService sessionCollaborationService;

    @Autowired
    public A2aTaskServiceImpl(AiTaskLifecycleService taskLifecycleService,
                              AiTaskEventService taskEventService,
                              AiRelayGrantService relayGrantService,
                              AiRelayRegistryService relayRegistryService,
                              AiAuditService auditService,
                              AiTaskRepository taskRepository,
                              RestTemplate aiRestTemplate,
                              A2aPayloadPolicyService payloadPolicyService) {
        this(taskLifecycleService, taskEventService, relayGrantService, relayRegistryService, auditService, taskRepository,
                aiRestTemplate, payloadPolicyService, defaultCenterForwardFallbackEnabled());
    }

    A2aTaskServiceImpl(AiTaskLifecycleService taskLifecycleService,
                       AiTaskEventService taskEventService,
                       AiRelayGrantService relayGrantService,
                       AiRelayRegistryService relayRegistryService,
                       AiAuditService auditService,
                       RestTemplate aiRestTemplate,
                       A2aPayloadPolicyService payloadPolicyService) {
        this(taskLifecycleService, taskEventService, relayGrantService, relayRegistryService, auditService, null,
                aiRestTemplate, payloadPolicyService, defaultCenterForwardFallbackEnabled());
    }

    A2aTaskServiceImpl(AiTaskLifecycleService taskLifecycleService,
                       AiTaskEventService taskEventService,
                       AiRelayGrantService relayGrantService,
                       AiRelayRegistryService relayRegistryService,
                       AiAuditService auditService,
                       RestTemplate aiRestTemplate,
                       A2aPayloadPolicyService payloadPolicyService,
                       boolean centerForwardFallbackEnabled) {
        this(taskLifecycleService, taskEventService, relayGrantService, relayRegistryService, auditService, null,
                aiRestTemplate, payloadPolicyService, centerForwardFallbackEnabled);
    }

    A2aTaskServiceImpl(AiTaskLifecycleService taskLifecycleService,
                       AiTaskEventService taskEventService,
                       AiRelayGrantService relayGrantService,
                       AiRelayRegistryService relayRegistryService,
                       AiAuditService auditService,
                       AiTaskRepository taskRepository,
                       RestTemplate aiRestTemplate,
                       A2aPayloadPolicyService payloadPolicyService,
                       boolean centerForwardFallbackEnabled) {
        this.taskLifecycleService = taskLifecycleService;
        this.taskEventService = taskEventService;
        this.relayGrantService = relayGrantService;
        this.relayRegistryService = relayRegistryService;
        this.auditService = auditService;
        this.taskRepository = taskRepository;
        this.restTemplate = aiRestTemplate;
        this.payloadPolicyService = payloadPolicyService;
        this.requestSecurityService = new RelayRequestSecurityServiceImpl();
        this.centerForwardFallbackEnabled = centerForwardFallbackEnabled;
    }
    @Autowired(required = false)
    public void setAgentRunService(AiAgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @Autowired(required = false)
    public void setSessionContextService(AiSessionContextService sessionContextService) {
        this.sessionContextService = sessionContextService;
    }

    @Autowired(required = false)
    public void setSessionCollaborationService(AiSessionCollaborationService sessionCollaborationService) {
        this.sessionCollaborationService = sessionCollaborationService;
    }



    @Override
    public A2aTaskCreateResponse createTask(A2aTaskCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("A2aTaskCreateRequest is required");
        }
        Map<String, Object> params = enrichParamsFromParentTask(request.getParams());
        if (sessionCollaborationService != null) {
            sessionCollaborationService.enrichTaskParams(params);
        }
        normalizeExecutionPolicy(params);
        String idempotencyKey = requiredString(params, "idempotencyKey");
        payloadPolicyService.validateTaskParams(params);
        appendIncomingContext(params, idempotencyKey);
        if (shouldForwardRemote(params)) {
            validateGrant(params, CapabilityCode.A2A_TASK_CREATE);
            try {
                validateRemoteGrant(params, CapabilityCode.A2A_TASK_CREATE);
                A2aTaskCreateResponse response = remoteCreateTask(request, params, idempotencyKey);
                attachCreateTrace(response, params, stringValue(response.getResult().get("taskId")), "REMOTE_TASK_CREATE", idempotencyKey);
                syncParentTaskFromA2aCreate(params, response, "REMOTE_A2A_TASK_CREATED");
                return response;
            } catch (SecurityException e) {
                throw e;
            } catch (Exception remoteError) {
                return fallbackCreateTask(request, params, idempotencyKey, remoteError);
            }
        }
        if (requiresGrant(params)) {
            validateGrant(params, CapabilityCode.A2A_TASK_CREATE);
        }
        A2aTaskCreateResponse response = localCreateTask(request, params, idempotencyKey);
        attachCreateTrace(response, params, stringValue(response.getResult().get("taskId")), "LOCAL_TASK_CREATE", idempotencyKey);
        syncParentTaskFromA2aCreate(params, response, "LOCAL_A2A_TASK_CREATED");
        return response;
    }

    @Override
    public Object getTask(String taskId, Map<String, Object> context) {
        Map<String, Object> params = enrichParamsFromParentTask(context);
        if (shouldForwardRemote(params)) {
            validateGrant(params, CapabilityCode.A2A_TASK_GET);
            try {
                validateRemoteGrant(params, CapabilityCode.A2A_TASK_GET);
                Map<String, Object> forwardedContext = enrichForwardedParams(params, CapabilityCode.A2A_TASK_GET);
                Object result = restTemplate.getForObject(resolveTaskDetailEndpoint(forwardedContext, taskId), Object.class);
                Object traced = attachTaskTrace(normalizeTaskResult(result), params, taskId, recordAudit(params, taskId, "REMOTE_TASK_GET"));
                appendTaskOutputContext(params, taskId, traced);
                syncParentTaskFromA2aState(params, taskId, traced, "REMOTE_A2A_TASK_GET", null);
                return traced;
            } catch (SecurityException e) {
                throw e;
            } catch (Exception remoteError) {
                if (!allowCenterForwardFallback(params)) {
                    throw remoteError;
                }
                try {
                    Object result = taskLifecycleService.getTask(taskId);
                    Object traced = attachTaskTrace(normalizeTaskResult(result), params, taskId, recordAudit(params, taskId, "CENTER_FORWARD_FALLBACK_GET"));
                    appendTaskOutputContext(params, taskId, traced);
                    syncParentTaskFromA2aState(params, taskId, traced, "CENTER_FORWARD_A2A_TASK_GET", null);
                    return traced;
                } catch (Exception fallbackError) {
                    throw new IllegalStateException("Remote A2A task get failed and center fallback failed: "
                            + summarize(remoteError) + " | " + summarize(fallbackError), fallbackError);
                }
            }
        }
        if (requiresGrant(params)) {
            validateGrant(params, CapabilityCode.A2A_TASK_GET);
        }
        Object result = taskLifecycleService.getTask(taskId);
        Object traced = attachTaskTrace(normalizeTaskResult(result), params, taskId, recordAudit(params, taskId, "LOCAL_TASK_GET"));
        appendTaskOutputContext(params, taskId, traced);
        syncParentTaskFromA2aState(params, taskId, traced, "LOCAL_A2A_TASK_GET", null);
        return traced;
    }

    @Override
    public ResponseEntity<StreamingResponseBody> streamTaskEvents(String taskId, Map<String, Object> context) {
        Map<String, Object> params = enrichParamsFromParentTask(context);
        if (shouldForwardRemote(params)) {
            validateGrant(params, CapabilityCode.A2A_TASK_GET);
            try {
                validateRemoteGrant(params, CapabilityCode.A2A_TASK_GET);
                return wrapStreamTaskEvents(taskId, params, "REMOTE_A2A_TASK_STREAM_OPENED", remoteStreamTaskEvents(taskId, params));
            } catch (SecurityException e) {
                throw e;
            } catch (Exception remoteError) {
                if (!allowCenterForwardFallback(params)) {
                    throw asRuntimeException(remoteError);
                }
                return wrapStreamTaskEvents(taskId, params, "CENTER_FORWARD_A2A_TASK_STREAM_OPENED", taskEventService.streamEvents(taskId));
            }
        }
        if (requiresGrant(params)) {
            validateGrant(params, CapabilityCode.A2A_TASK_GET);
        }
        return wrapStreamTaskEvents(taskId, params, "LOCAL_A2A_TASK_STREAM_OPENED", taskEventService.streamEvents(taskId));
    }

    @Override
    public Object cancelTask(String taskId, Map<String, Object> context) {
        Map<String, Object> params = enrichParamsFromParentTask(context);
        if (shouldForwardRemote(params)) {
            validateGrant(params, CapabilityCode.A2A_TASK_CANCEL);
            try {
                validateRemoteGrant(params, CapabilityCode.A2A_TASK_CANCEL);
                Map<String, Object> forwardedContext = enrichForwardedParams(params, CapabilityCode.A2A_TASK_CANCEL);
                Object result = restTemplate.postForObject(resolveTaskCancelEndpoint(forwardedContext, taskId), forwardedContext, Object.class);
                String auditId = recordAudit(params, taskId, "REMOTE_TASK_CANCEL");
                Object traced = attachTaskTrace(normalizeTaskResult(result), params, taskId, auditId);
                syncParentTaskFromA2aState(params, taskId, traced, "REMOTE_A2A_TASK_CANCELLED", "CANCELLED");
                return traced;
            } catch (SecurityException e) {
                throw e;
            } catch (Exception remoteError) {
                if (!allowCenterForwardFallback(params)) {
                    throw remoteError;
                }
                try {
                    AiTaskCancelRequest request = new AiTaskCancelRequest();
                    request.setReason(stringValue(params.get("reason")));
                    Object result = taskLifecycleService.cancelTask(taskId, request);
                    finishAgentRun(stringValue(params.get("agentRunId")), "CANCELLED", request.getReason(), result);
                    String auditId = recordAudit(params, taskId, "CENTER_FORWARD_FALLBACK_CANCEL");
                    Object traced = attachTaskTrace(normalizeTaskResult(result), params, taskId, auditId);
                    syncParentTaskFromA2aState(params, taskId, traced, "CENTER_FORWARD_A2A_TASK_CANCELLED", "CANCELLED");
                    return traced;
                } catch (Exception fallbackError) {
                    throw new IllegalStateException("Remote A2A task cancel failed and center fallback failed: "
                            + summarize(remoteError) + " | " + summarize(fallbackError), fallbackError);
                }
            }
        }
        if (requiresGrant(params)) {
            validateGrant(params, CapabilityCode.A2A_TASK_CANCEL);
        }
        AiTaskCancelRequest request = new AiTaskCancelRequest();
        request.setReason(stringValue(params.get("reason")));
        Object result = taskLifecycleService.cancelTask(taskId, request);
        finishAgentRun(stringValue(params.get("agentRunId")), "CANCELLED", request.getReason(), result);
        String auditId = recordAudit(params, taskId, "LOCAL_TASK_CANCEL");
        Object traced = attachTaskTrace(normalizeTaskResult(result), params, taskId, auditId);
        syncParentTaskFromA2aState(params, taskId, traced, "LOCAL_A2A_TASK_CANCELLED", "CANCELLED");
        return traced;
    }

    private void appendIncomingContext(Map<String, Object> params, String requestId) {
        String sessionId = stringValue(params.get("sessionId"));
        if (sessionContextService == null || isBlank(sessionId) || booleanValue(params.get("forwardedByCenter"))
                || booleanValue(params.get("contextAlreadyAppended"))) {
            return;
        }
        if (requiresGrant(params)) {
            validateGrant(params, CapabilityCode.A2A_TASK_CREATE);
        }
        String content = latestUserMessage(params);
        if (isBlank(content)) {
            return;
        }
        AiSessionContextAppendRequest contextRequest = new AiSessionContextAppendRequest();
        contextRequest.setEventId("a2a-task-input:" + requestId);
        contextRequest.setTaskId(stringValue(params.get("taskId")));
        contextRequest.setSenderType("CODEX");
        contextRequest.setSenderId(stringValue(params.get("sourceNodeId")));
        contextRequest.setTargetNodeId(stringValue(params.get("targetNodeId")));
        contextRequest.setRole("user");
        contextRequest.setContent(content);
        contextRequest.setContentType("TEXT");
        sessionContextService.append(sessionId, contextRequest);
        params.put("contextHeadCursor", sessionContextService.headCursor(sessionId));
        putIfNotBlank(params, "centerContextDeltaEndpoint", resolveCenterContextDeltaEndpoint(params));
    }

    private void appendTaskOutputContext(Map<String, Object> params, String taskId, Object result) {
        String sessionId = stringValue(params.get("sessionId"));
        if (sessionContextService == null || isBlank(sessionId) || !isTerminalTaskResult(result)) {
            return;
        }
        String answer = taskAnswer(result);
        if (isBlank(answer)) {
            return;
        }
        AiSessionContextAppendRequest contextRequest = new AiSessionContextAppendRequest();
        contextRequest.setEventId("a2a-task-output:" + taskId);
        contextRequest.setTaskId(taskId);
        contextRequest.setSenderType("RELAY");
        contextRequest.setSenderId(firstNonBlank(stringValue(params.get("targetNodeId")), stringValue(params.get("sourceNodeId"))));
        contextRequest.setTargetNodeId(stringValue(params.get("sourceNodeId")));
        contextRequest.setRole("assistant");
        contextRequest.setContent(answer);
        contextRequest.setContentType("TEXT");
        sessionContextService.append(sessionId, contextRequest);
    }

    private String latestUserMessage(Map<String, Object> params) {
        Object messagesValue = params.get("messages");
        if (messagesValue instanceof List<?> messages) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                Map<String, Object> message = mapValue(messages.get(index));
                if ("user".equalsIgnoreCase(firstNonBlank(stringValue(message.get("role")), "user"))
                        && !isBlank(stringValue(message.get("content")))) {
                    return stringValue(message.get("content"));
                }
            }
        }
        return stringValue(mapValue(params.get("input")).get("prompt"));
    }

    private boolean isTerminalTaskResult(Object result) {
        String status = null;
        if (result instanceof AiTaskView taskView) {
            status = taskView.getStatus();
        } else if (result instanceof Map<?, ?> map) {
            Map<String, Object> value = toMap(map);
            status = stringValue(value.get("status"));
            if (isBlank(status)) {
                status = stringValue(mapValue(value.get("result")).get("status"));
            }
        }
        return status != null && List.of("SUCCESS", "FAILED", "CANCELLED", "TIMEOUT").contains(status.toUpperCase());
    }

    private String taskAnswer(Object result) {
        if (result instanceof AiTaskView taskView) {
            return stringValue(taskView.getResult().get("answer"));
        }
        if (!(result instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> value = toMap(map);
        String answer = stringValue(value.get("answer"));
        if (!isBlank(answer)) {
            return answer;
        }
        return stringValue(mapValue(value.get("result")).get("answer"));
    }

    private String resolveCenterContextDeltaEndpoint(Map<String, Object> params) {
        String endpoint = stringValue(params.get("centerContextDeltaEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        endpoint = stringValue(mapValue(params.get("metadata")).get("centerContextDeltaEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        String configured = System.getProperty("wdsavs.ai.session.context.delta-endpoint");
        if (isBlank(configured)) {
            configured = System.getenv("WDSAVS_AI_SESSION_CONTEXT_DELTA_ENDPOINT");
        }
        if (!isBlank(configured)) {
            return configured.trim();
        }
        String grantEndpoint = resolveCenterGrantValidateEndpoint(params);
        if (isBlank(grantEndpoint)) {
            return null;
        }
        try {
            URI uri = URI.create(grantEndpoint);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    "/api/skill/session/context/delta", null, null).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> enrichParamsFromParentTask(Map<String, Object> source) {
        Map<String, Object> params = new LinkedHashMap<>(source == null ? Map.of() : source);
        String parentTaskId = stringValue(params.get("parentTaskId"));
        if (isBlank(parentTaskId)) {
            return params;
        }
        AiTaskView parentTask = taskLifecycleService.getTask(parentTaskId);
        if (parentTask == null || parentTask.getResult() == null) {
            return params;
        }
        Map<String, Object> parentResult = parentTask.getResult();
        String grantId = firstNonBlank(
                stringValue(params.get("grantId")),
                stringValue(parentResult.get("accessGrantId")),
                stringValue(parentResult.get("latestChildGrantId")),
                stringValue(parentResult.get("grantId"))
        );
        if (isBlank(grantId)) {
            copyParentEndpointIfMissing(params, parentResult);
            return params;
        }
        RelayGrantView grant = relayGrantService.getGrant(grantId);
        RelayAccessDecisionResponse decision = relayGrantService.activateGrantIfReady(grantId);
        putIfNotBlank(params, "grantId", grantId);
        if (grant != null) {
            putIfAbsent(params, "sourceNodeId", grant.getSourceNodeId());
            putIfAbsent(params, "targetNodeId", grant.getTargetNodeId());
            putIfNotBlank(params, "expiresAt", grant.getExpiresAt());
        }
        if (decision != null) {
            putIfNotBlank(params, "signedToken", decision.getSignedToken());
            putIfNotBlank(params, "targetRelayEndpoint", decision.getTargetRelayEndpoint());
            putIfNotBlank(params, "grantActivationStatus", decision.getDecision());
            if (decision.getAllowedCapabilities() != null && !decision.getAllowedCapabilities().isEmpty()) {
                params.put("allowedCapabilities", decision.getAllowedCapabilities());
            }
        }
        copyParentEndpointIfMissing(params, parentResult);
        params.put("grantAutoInjectedFromParentTask", Boolean.TRUE);
        return params;
    }

    private void copyParentEndpointIfMissing(Map<String, Object> params, Map<String, Object> parentResult) {
        if (!isBlank(stringValue(params.get("targetRelayEndpoint"))) || parentResult == null) {
            return;
        }
        String endpoint = firstNonBlank(
                stringValue(parentResult.get("latestChildGrantTargetRelayEndpoint")),
                stringValue(parentResult.get("grantTargetRelayEndpoint")),
                stringValue(parentResult.get("accessTargetRelayEndpoint")),
                stringValue(parentResult.get("latestChildRelayEndpoint"))
        );
        putIfNotBlank(params, "targetRelayEndpoint", endpoint);
    }

    private void putIfAbsent(Map<String, Object> params, String key, String value) {
        if (isBlank(stringValue(params.get(key)))) {
            putIfNotBlank(params, key, value);
        }
    }

    private void syncParentTaskFromA2aCreate(Map<String, Object> params, A2aTaskCreateResponse response, String eventType) {
        if (taskRepository == null || response == null || response.getResult() == null) {
            appendParentA2aEvent(params, response, eventType);
            return;
        }
        String parentTaskId = stringValue(params.get("parentTaskId"));
        if (isBlank(parentTaskId)) {
            return;
        }
        AiTaskEntity parent = taskRepository.findByTaskId(parentTaskId).orElse(null);
        if (parent == null) {
            appendParentA2aEvent(params, response, eventType);
            return;
        }
        Map<String, Object> result = readJsonMap(parent.getResultJson());
        result.put("latestA2aTaskId", response.getResult().get("taskId"));
        result.put("latestA2aStatus", response.getResult().get("status"));
        result.put("latestA2aAccepted", response.getResult().get("accepted"));
        result.put("latestA2aEventType", eventType);
        copyIfPresent(response.getResult(), result, "agentRunId", "latestA2aAgentRunId");
        copyIfPresent(response.getResult(), result, "auditId", "latestA2aAuditId");
        copyIfPresent(params, result, "grantId", "latestA2aGrantId");
        copyIfPresent(params, result, "targetRelayEndpoint", "latestA2aTargetRelayEndpoint");
        copyIfPresent(params, result, "grantActivationStatus", "latestA2aGrantActivationStatus");
        if (!isTerminal(parent.getStatus())) {
            parent.setStatus("PARTIAL_SUCCESS");
            parent.setCurrentStage(eventType);
            parent.setErrorCode(null);
            parent.setErrorMessage(null);
        }
        parent.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        parent.setResultJson(writeJsonMap(result));
        taskRepository.save(parent);
        appendParentA2aEvent(params, response, eventType);
    }

    private void appendParentA2aEvent(Map<String, Object> params, A2aTaskCreateResponse response, String eventType) {
        String parentTaskId = stringValue(params == null ? null : params.get("parentTaskId"));
        String sessionId = stringValue(params == null ? null : params.get("sessionId"));
        if (isBlank(parentTaskId) || isBlank(sessionId) || taskEventService == null || response == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parentTaskId", parentTaskId);
        payload.put("a2aTaskId", response.getResult().get("taskId"));
        payload.put("a2aStatus", response.getResult().get("status"));
        payload.put("accepted", response.getResult().get("accepted"));
        copyIfPresent(response.getResult(), payload, "agentRunId", "agentRunId");
        copyIfPresent(response.getResult(), payload, "auditId", "auditId");
        copyIfPresent(params, payload, "grantId", "grantId");
        copyIfPresent(params, payload, "targetRelayEndpoint", "targetRelayEndpoint");
        copyIfPresent(params, payload, "grantActivationStatus", "grantActivationStatus");
        taskEventService.appendEvent(parentTaskId, sessionId, eventType, nextParentSequence(parentTaskId), payload);
    }

    private void syncParentTaskFromA2aState(Map<String, Object> params, String taskId, Object result,
                                            String eventType, String defaultStatus) {
        Map<String, Object> payload = buildParentA2aStatePayload(params, taskId, result, eventType, defaultStatus);
        if (taskRepository == null) {
            appendParentA2aStateEvent(params, payload, eventType);
            return;
        }
        String parentTaskId = stringValue(params == null ? null : params.get("parentTaskId"));
        if (isBlank(parentTaskId)) {
            return;
        }
        AiTaskEntity parent = taskRepository.findByTaskId(parentTaskId).orElse(null);
        if (parent == null) {
            appendParentA2aStateEvent(params, payload, eventType);
            return;
        }
        Map<String, Object> parentResult = readJsonMap(parent.getResultJson());
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getValue() != null) {
                parentResult.put(entry.getKey(), entry.getValue());
            }
        }
        String status = stringValue(payload.get("latestA2aStatus"));
        String errorCode = stringValue(payload.get("latestA2aErrorCode"));
        String errorMessage = stringValue(payload.get("latestA2aErrorMessage"));
        String now = String.valueOf(System.currentTimeMillis());
        if ("CANCELLED".equals(status)) {
            parent.setStatus("CANCELLED");
            parent.setCurrentStage(eventType);
            parent.setErrorCode(null);
            parent.setErrorMessage(null);
            parent.setEndTime(now);
        } else if (!isTerminal(parent.getStatus())) {
            if ("SUCCESS".equals(status)) {
                parent.setStatus("SUCCESS");
                parent.setCurrentStage(firstNonBlank(stringValue(payload.get("latestA2aStage")), eventType));
                parent.setErrorCode(null);
                parent.setErrorMessage(null);
                parent.setEndTime(now);
            } else if ("FAILED".equals(status) || "TIMEOUT".equals(status)) {
                parent.setStatus(status);
                parent.setCurrentStage(eventType);
                parent.setErrorCode(errorCode);
                parent.setErrorMessage(errorMessage);
                parent.setEndTime(now);
            } else {
                parent.setStatus("PARTIAL_SUCCESS");
                parent.setCurrentStage(eventType);
                parent.setErrorCode(null);
                parent.setErrorMessage(null);
            }
        }
        parent.setUpdateTime(now);
        parent.setResultJson(writeJsonMap(parentResult));
        taskRepository.save(parent);
        appendParentA2aStateEvent(params, payload, eventType);
    }

    private Map<String, Object> buildParentA2aStatePayload(Map<String, Object> params, String taskId, Object result,
                                                           String eventType, String defaultStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String resolvedTaskId = taskId;
        String status = defaultStatus;
        String currentStage = eventType;
        String requestId = null;
        String traceId = null;
        String auditId = null;
        String agentRunId = null;
        String errorCode = null;
        String errorMessage = null;
        if (result instanceof AiTaskView taskView) {
            resolvedTaskId = firstNonBlank(taskView.getTaskId(), taskId);
            status = firstNonBlank(taskView.getStatus(), defaultStatus);
            currentStage = firstNonBlank(taskView.getCurrentStage(), eventType);
            requestId = taskView.getRequestId();
            traceId = taskView.getTraceId();
            auditId = taskView.getAuditId();
            agentRunId = taskView.getAgentRunId();
            errorCode = taskView.getErrorCode();
            errorMessage = taskView.getErrorMessage();
            if (taskView.getResult() != null && !taskView.getResult().isEmpty()) {
                payload.put("latestA2aResult", taskView.getResult());
            }
        } else if (result instanceof Map<?, ?> map) {
            Map<String, Object> normalized = toMap(map);
            resolvedTaskId = firstNonBlank(stringValue(normalized.get("taskId")), taskId);
            status = firstNonBlank(stringValue(normalized.get("status")), defaultStatus);
            currentStage = firstNonBlank(stringValue(normalized.get("currentStage")), eventType);
            requestId = stringValue(normalized.get("requestId"));
            traceId = stringValue(normalized.get("traceId"));
            auditId = stringValue(normalized.get("auditId"));
            agentRunId = stringValue(normalized.get("agentRunId"));
            errorCode = stringValue(normalized.get("errorCode"));
            errorMessage = stringValue(normalized.get("errorMessage"));
            Object taskResult = normalized.get("result");
            if (taskResult != null) {
                payload.put("latestA2aResult", taskResult);
            }
        } else if (result instanceof Boolean bool && Boolean.TRUE.equals(bool)) {
            status = firstNonBlank(defaultStatus, "CANCELLED");
        }
        payload.put("parentTaskId", stringValue(params == null ? null : params.get("parentTaskId")));
        payload.put("latestA2aTaskId", resolvedTaskId);
        payload.put("latestA2aStatus", status);
        payload.put("latestA2aStage", currentStage);
        payload.put("latestA2aEventType", eventType);
        putIfNotBlank(payload, "latestA2aRequestId", requestId);
        putIfNotBlank(payload, "latestA2aTraceId", traceId);
        putIfNotBlank(payload, "latestA2aAuditId", auditId);
        putIfNotBlank(payload, "latestA2aAgentRunId", agentRunId);
        putIfNotBlank(payload, "latestA2aErrorCode", errorCode);
        putIfNotBlank(payload, "latestA2aErrorMessage", errorMessage);
        copyIfPresent(params, payload, "grantId", "latestA2aGrantId");
        copyIfPresent(params, payload, "targetRelayEndpoint", "latestA2aTargetRelayEndpoint");
        copyIfPresent(params, payload, "grantActivationStatus", "latestA2aGrantActivationStatus");
        return payload;
    }

    private void appendParentA2aStateEvent(Map<String, Object> params, Map<String, Object> payload, String eventType) {
        String parentTaskId = stringValue(params == null ? null : params.get("parentTaskId"));
        String sessionId = stringValue(params == null ? null : params.get("sessionId"));
        if (isBlank(parentTaskId) || isBlank(sessionId) || taskEventService == null || payload == null) {
            return;
        }
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("parentTaskId", parentTaskId);
        eventPayload.put("a2aTaskId", payload.get("latestA2aTaskId"));
        eventPayload.put("a2aStatus", payload.get("latestA2aStatus"));
        eventPayload.put("a2aStage", payload.get("latestA2aStage"));
        eventPayload.put("a2aEventType", eventType);
        copyIfPresent(payload, eventPayload, "latestA2aRequestId", "requestId");
        copyIfPresent(payload, eventPayload, "latestA2aTraceId", "traceId");
        copyIfPresent(payload, eventPayload, "latestA2aAuditId", "auditId");
        copyIfPresent(payload, eventPayload, "latestA2aAgentRunId", "agentRunId");
        copyIfPresent(payload, eventPayload, "latestA2aErrorCode", "errorCode");
        copyIfPresent(payload, eventPayload, "latestA2aErrorMessage", "errorMessage");
        copyIfPresent(params, eventPayload, "grantId", "grantId");
        copyIfPresent(params, eventPayload, "targetRelayEndpoint", "targetRelayEndpoint");
        copyIfPresent(params, eventPayload, "grantActivationStatus", "grantActivationStatus");
        taskEventService.appendEvent(parentTaskId, sessionId, eventType, nextParentSequence(parentTaskId), eventPayload);
    }

    private Long nextParentSequence(String taskId) {
        return (long) (taskEventService.listEvents(taskId).size() + 1);
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        if (source != null && target != null && source.containsKey(sourceKey) && source.get(sourceKey) != null) {
            target.put(targetKey, source.get(sourceKey));
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJsonMap(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize parent A2A task payload", e);
        }
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) || "TIMEOUT".equals(status);
    }

    private A2aTaskCreateResponse remoteCreateTask(A2aTaskCreateRequest request, Map<String, Object> params, String idempotencyKey) {
        A2aTaskCreateRequest forwarded = new A2aTaskCreateRequest();
        forwarded.setJsonrpc(request.getJsonrpc());
        forwarded.setId(request.getId());
        forwarded.setMethod(request.getMethod());
        Map<String, Object> forwardedParams = new LinkedHashMap<>(params);
        RelayGrantValidateRequest validateRequest = toValidateRequest(params, CapabilityCode.A2A_TASK_CREATE);
        String plannedTaskId = firstNonBlank(stringValue(params.get("taskId")), deterministicId("a2a-task", params, idempotencyKey));
        String plannedAgentRunId = firstNonBlank(stringValue(params.get("agentRunId")), deterministicId("a2a-run", params, idempotencyKey));
        forwardedParams.put("requestId", idempotencyKey);
        putIfNotBlank(forwardedParams, "taskId", plannedTaskId);
        putIfNotBlank(forwardedParams, "agentRunId", plannedAgentRunId);
        if (sessionContextService != null) {
            putIfNotBlank(forwardedParams, "centerContextDeltaEndpoint", resolveCenterContextDeltaEndpoint(forwardedParams));
        }
        String agentRunId = startAgentRun(plannedAgentRunId, forwardedParams, plannedTaskId);
        forwardedParams.put("forwardedByCenter", Boolean.TRUE);
        forwarded.setParams(forwardedParams);
        putIfNotBlank(forwardedParams, "expiresAt", validateRequest.getExpiresAt());
        try {
            A2aTaskCreateResponse response = restTemplate.postForObject(resolveTaskCreateEndpoint(params), forwarded, A2aTaskCreateResponse.class);
            if (response == null) {
                throw new IllegalStateException("Remote A2A task create returned empty response");
            }
            return response;
        } catch (RuntimeException e) {
            finishAgentRun(agentRunId, "FAILED", summarize(e), null);
            throw e;
        }
    }


    private A2aTaskCreateResponse localCreateTask(A2aTaskCreateRequest request, Map<String, Object> params, String idempotencyKey) {
        String plannedTaskId = firstNonBlank(stringValue(params.get("taskId")), deterministicId("a2a-task", params, idempotencyKey));
        String plannedAgentRunId = firstNonBlank(stringValue(params.get("agentRunId")), deterministicId("a2a-run", params, idempotencyKey));
        Map<String, Object> persistedParams = new LinkedHashMap<>(params);
        putIfNotBlank(persistedParams, "taskId", plannedTaskId);
        putIfNotBlank(persistedParams, "agentRunId", plannedAgentRunId);

        AiTaskCreateRequest createRequest = new AiTaskCreateRequest();
        createRequest.setTaskId(plannedTaskId);
        createRequest.setSessionId(requiredString(params, "sessionId"));
        createRequest.setRequestId(idempotencyKey);
        createRequest.setParentTaskId(stringValue(params.get("parentTaskId")));
        createRequest.setTaskType(stringValue(params.getOrDefault("taskType", "A2A_TASK")));
        createRequest.setSourceNodeId(stringValue(params.get("sourceNodeId")));
        createRequest.setTargetNodeId(stringValue(params.get("targetNodeId")));
        createRequest.setPayload(persistedParams);
        AiTaskCreateResponse created = taskLifecycleService.createTask(createRequest);
        String agentRunId = latestAgentRunId(created.getTaskId());
        if (isBlank(agentRunId)) {
            agentRunId = startAgentRun(plannedAgentRunId, persistedParams, created.getTaskId());
        }
        A2aTaskCreateResponse response = new A2aTaskCreateResponse();
        response.setId(request.getId());
        response.getResult().put("taskId", created.getTaskId());
        response.getResult().put("status", created.getStatus());
        response.getResult().put("accepted", created.getAccepted());
        putIfNotBlank(response.getResult(), "sessionId", requiredString(params, "sessionId"));
        putIfNotBlank(response.getResult(), "requestId", created.getRequestId() == null ? idempotencyKey : created.getRequestId());
        putIfNotBlank(response.getResult(), "traceId", created.getTraceId());
        putIfNotBlank(response.getResult(), "agentRunId", agentRunId);
        attachExecutionPolicyResult(response.getResult(), persistedParams);
        return response;
    }

    private A2aTaskCreateResponse fallbackCreateTask(A2aTaskCreateRequest request, Map<String, Object> params,
                                                     String idempotencyKey, Exception remoteError) {
        if (!allowCenterForwardFallback(params)) {
            throw asRuntimeException(remoteError);
        }
        try {
            A2aTaskCreateResponse response = localCreateTask(request, params, idempotencyKey);
            attachCreateTrace(response, params, stringValue(response.getResult().get("taskId")), "CENTER_FORWARD_FALLBACK_CREATE", idempotencyKey);
            syncParentTaskFromA2aCreate(params, response, "CENTER_FORWARD_A2A_TASK_CREATED");
            return response;
        } catch (Exception fallbackError) {
            throw new IllegalStateException("Remote A2A task create failed and center fallback failed: "
                    + summarize(remoteError) + " | " + summarize(fallbackError), fallbackError);
        }
    }

    private boolean shouldForwardRemote(Map<String, Object> params) {
        if (booleanValue(params.get("forwardedByCenter"))) {
            return false;
        }
        return !isBlank(stringValue(params.get("targetNodeId"))) || !isBlank(stringValue(params.get("targetTaskEndpoint")))
                || !isBlank(stringValue(params.get("targetA2aTaskBaseEndpoint")));
    }

    private boolean allowCenterForwardFallback(Map<String, Object> params) {
        Object override = params.get("allowCenterForwardFallback");
        if (override == null) {
            return centerForwardFallbackEnabled;
        }
        return booleanValue(override);
    }

    private boolean requiresGrant(Map<String, Object> params) {
        return !isBlank(stringValue(params.get("grantId"))) || !isBlank(stringValue(params.get("signedToken")))
                || !isBlank(stringValue(params.get("targetNodeId")));
    }

    private void validateGrant(Map<String, Object> params, CapabilityCode capabilityCode) {
        RelayGrantValidateResponse validation = relayGrantService.validateGrant(toValidateRequest(params, capabilityCode));
        ensureGrantValid(validation, "Local grant validation failed");
    }

    private void validateRemoteGrant(Map<String, Object> params, CapabilityCode capabilityCode) {
        RelayGrantValidateResponse validation = restTemplate.postForObject(resolveGrantValidateEndpoint(params),
                requestSecurityService.sign(toValidateRequest(params, capabilityCode)), RelayGrantValidateResponse.class);
        ensureGrantValid(validation, "Remote grant validation failed");
    }

    private RelayGrantValidateRequest toValidateRequest(Map<String, Object> params, CapabilityCode capabilityCode) {
        String grantId = requiredString(params, "grantId");
        RelayGrantView grant = relayGrantService.getGrant(grantId);
        List<String> signedCapabilities = grant == null || grant.getAllowedCapabilities() == null || grant.getAllowedCapabilities().isEmpty()
                ? List.of(capabilityCode.name())
                : grant.getAllowedCapabilities();
        if (!signedCapabilities.contains(capabilityCode.name())) {
            throw new SecurityException("Grant capability is not sufficient: " + capabilityCode.name());
        }
        RelayGrantValidateRequest validateRequest = new RelayGrantValidateRequest();
        validateRequest.setGrantId(grantId);
        validateRequest.setSessionId(requiredString(params, "sessionId"));
        validateRequest.setSourceNodeId(stringValue(params.get("sourceNodeId")));
        validateRequest.setTargetNodeId(requiredString(params, "targetNodeId"));
        validateRequest.setSignedToken(requiredString(params, "signedToken"));
        validateRequest.setExpiresAt(grant == null ? stringValue(params.get("expiresAt")) : grant.getExpiresAt());
        validateRequest.setAllowedCapabilities(signedCapabilities);
        return validateRequest;
    }

    private void ensureGrantValid(RelayGrantValidateResponse validation, String messagePrefix) {
        if (validation == null || !Boolean.TRUE.equals(validation.getValid())) {
            String detail = validation == null ? "empty validation response" : validation.getMessage();
            throw new SecurityException(messagePrefix + ": " + detail);
        }
    }

    private Map<String, Object> enrichForwardedParams(Map<String, Object> params, CapabilityCode capabilityCode) {
        Map<String, Object> forwarded = new LinkedHashMap<>(params == null ? Map.of() : params);
        RelayGrantValidateRequest validateRequest = toValidateRequest(forwarded, capabilityCode);
        putIfNotBlank(forwarded, "expiresAt", validateRequest.getExpiresAt());
        String centerGrantValidateEndpoint = resolveCenterGrantValidateEndpoint(forwarded);
        putIfNotBlank(forwarded, "centerGrantValidateEndpoint", centerGrantValidateEndpoint);
        if (sessionContextService != null) {
            putIfNotBlank(forwarded, "centerContextDeltaEndpoint", resolveCenterContextDeltaEndpoint(forwarded));
        }
        return forwarded;
    }

    private ResponseEntity<StreamingResponseBody> remoteStreamTaskEvents(String taskId, Map<String, Object> params) {
        Map<String, Object> forwardedContext = enrichForwardedParams(params, CapabilityCode.A2A_TASK_GET);
        StreamingResponseBody body = outputStream -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(resolveTaskEventsEndpoint(forwardedContext, taskId)).openConnection();
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            int status = connection.getResponseCode();
            if (status >= 400) {
                try (InputStream errorStream = connection.getErrorStream()) {
                    String error = errorStream == null ? ("HTTP " + status) : new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                    throw new IllegalStateException(error);
                }
            }
            try (InputStream inputStream = connection.getInputStream()) {
                inputStream.transferTo(outputStream);
                outputStream.flush();
            } finally {
                connection.disconnect();
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private ResponseEntity<StreamingResponseBody> wrapStreamTaskEvents(String taskId, Map<String, Object> params,
                                                                       String openedEventType,
                                                                       ResponseEntity<StreamingResponseBody> delegate) {
        syncParentTaskFromA2aState(params, taskId, null, openedEventType, "STREAMING");
        if (delegate == null || delegate.getBody() == null) {
            return delegate;
        }
        StreamingResponseBody wrapped = outputStream -> {
            delegate.getBody().writeTo(outputStream);
            try {
                getTask(taskId, params);
            } catch (Exception ignored) {
            }
        };
        return ResponseEntity.status(delegate.getStatusCode())
                .headers(delegate.getHeaders())
                .body(wrapped);
    }

    private Object normalizeTaskResult(Object result) {
        if (result instanceof AiTaskView taskView) {
            taskView.setResult(payloadPolicyService.normalizeStructuredResult(taskView.getResult()));
            return taskView;
        }
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> normalized = toMap(map);
            Object nestedResult = normalized.get("result");
            if (nestedResult instanceof Map<?, ?> nestedMap) {
                normalized.put("result", payloadPolicyService.normalizeStructuredResult(toMap(nestedMap)));
            }
            return payloadPolicyService.normalizeStructuredResult(normalized);
        }
        return result;
    }

    private Map<String, Object> toMap(Map<?, ?> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return normalized;
    }


    private void attachCreateTrace(A2aTaskCreateResponse response, Map<String, Object> params,
                                   String taskId, String decision, String defaultRequestId) {
        if (response == null) {
            return;
        }
        Map<String, Object> result = response.getResult() == null ? new LinkedHashMap<>() : response.getResult();
        putIfNotBlank(result, "sessionId", stringValue(params.get("sessionId")));
        putIfNotBlank(result, "requestId", firstNonBlank(stringValue(result.get("requestId")), stringValue(params.get("requestId")), defaultRequestId));
        putIfNotBlank(result, "agentRunId", stringValue(result.get("agentRunId")));
        putIfNotBlank(result, "auditId", recordAudit(withAgentRun(params, stringValue(result.get("agentRunId"))), taskId, decision));
        attachExecutionPolicyResult(result, params);
        response.setResult(result);
    }

    private Object attachTaskTrace(Object result, Map<String, Object> params, String taskId, String auditId) {
        String requestId = firstNonBlank(stringValue(params.get("requestId")), stringValue(params.get("idempotencyKey")));
        String agentRunId = firstNonBlank(stringValue(params.get("agentRunId")), agentRunId(result));
        if (result instanceof AiTaskView taskView) {
            if (isBlank(taskView.getRequestId())) {
                taskView.setRequestId(requestId);
            }
            if (isBlank(taskView.getSessionId())) {
                taskView.setSessionId(stringValue(params.get("sessionId")));
            }
            taskView.setAuditId(auditId);
            taskView.setAgentRunId(agentRunId);
            Map<String, Object> taskResult = taskView.getResult() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(taskView.getResult());
            attachExecutionPolicyResult(taskResult, taskResult.isEmpty() ? params : taskResult);
            taskView.setResult(taskResult);
            finishAgentRun(agentRunId, taskView.getStatus(), taskView.getErrorMessage(), taskView.getResult());
            return taskView;
        }
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> normalized = toMap(map);
            putIfNotBlank(normalized, "sessionId", stringValue(params.get("sessionId")));
            putIfNotBlank(normalized, "requestId", firstNonBlank(stringValue(normalized.get("requestId")), requestId));
            putIfNotBlank(normalized, "agentRunId", agentRunId);
            putIfNotBlank(normalized, "auditId", auditId);
            attachExecutionPolicyResult(normalized, normalized);
            finishAgentRun(agentRunId, stringValue(normalized.get("status")), stringValue(normalized.get("errorMessage")), normalized);
            return normalized;
        }
        return result;
    }

    private ReactExecutionPolicy normalizeExecutionPolicy(Map<String, Object> params) {
        ReactExecutionPolicy policy = ReactExecutionPolicy.fromParams(params);
        policy.applyToParams(params);
        return policy;
    }

    private void attachExecutionPolicyResult(Map<String, Object> result, Map<String, Object> params) {
        if (result == null) {
            return;
        }
        ReactExecutionPolicy policy = ReactExecutionPolicy.fromParams(params);
        result.put("executionMode", policy.getExecutionMode());
        result.put("react", policy.toSummaryMap());
    }

    private Map<String, Object> withAgentRun(Map<String, Object> params, String agentRunId) {
        if (isBlank(agentRunId)) {
            return params;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(params == null ? Map.of() : params);
        enriched.put("agentRunId", agentRunId);
        return enriched;
    }

    private String startAgentRun(Map<String, Object> params, String taskId) {
        return startAgentRun(stringValue(params == null ? null : params.get("agentRunId")), params, taskId);
    }

    private String startAgentRun(String agentRunId, Map<String, Object> params, String taskId) {
        if (agentRunService == null || isBlank(taskId) || params == null) {
            return null;
        }
        String sessionId = stringValue(params.get("sessionId"));
        if (isBlank(sessionId)) {
            return null;
        }
        String nodeId = firstNonBlank(stringValue(params.get("targetNodeId")), stringValue(params.get("sourceNodeId")));
        String agentRole = firstNonBlank(stringValue(params.get("agentRole")), stringValue(params.get("taskType")), "AGENT");
        if (isBlank(agentRunId)) {
            return agentRunService.startRun(taskId, sessionId, nodeId, agentRole);
        }
        return agentRunService.startRun(agentRunId, taskId, sessionId, nodeId, agentRole);
    }

    private String latestAgentRunId(String taskId) {
        if (agentRunService == null || isBlank(taskId)) {
            return null;
        }
        try {
            List<com.webank.wedatasphere.wdsavs.aiagent.entity.AiAgentRunEntity> runs = agentRunService.listRuns(taskId);
            if (runs == null || runs.isEmpty()) {
                return null;
            }
            for (int i = runs.size() - 1; i >= 0; i--) {
                String value = runs.get(i).getAgentRunId();
                if (!isBlank(value)) {
                    return value;
                }
            }
            return null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void finishAgentRun(String agentRunId, String status, String message, Object summary) {
        if (agentRunService == null || isBlank(agentRunId) || isBlank(status)) {
            return;
        }
        String normalized = status.toUpperCase();
        try {
            if ("SUCCESS".equals(normalized)) {
                agentRunService.completeRun(agentRunId, outputSummary(summary, message));
            } else if ("FAILED".equals(normalized) || "TIMEOUT".equals(normalized)) {
                agentRunService.failRun(agentRunId, outputSummary(summary, message));
            } else if ("CANCELLED".equals(normalized)) {
                agentRunService.cancelRun(agentRunId, outputSummary(summary, message));
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private String outputSummary(Object summary, String message) {
        if (!isBlank(message)) {
            return message;
        }
        if (summary == null) {
            return null;
        }
        String value = String.valueOf(summary);
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private String agentRunId(Object result) {
        if (result instanceof AiTaskView taskView) {
            return taskView.getAgentRunId();
        }
        if (result instanceof Map<?, ?> map) {
            return stringValue(map.get("agentRunId"));
        }
        return null;
    }

    private String deterministicId(String prefix, Map<String, Object> params, String idempotencyKey) {
        String seed = firstNonBlank(stringValue(params == null ? null : params.get("sessionId")), "session") + ":" + firstNonBlank(idempotencyKey, UUID.randomUUID().toString());
        return prefix + "-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
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
    private String resolveTaskEventsEndpoint(Map<String, Object> params, String taskId) {
        String endpoint = stringValue(params.get("targetTaskEventsEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint.replace("{taskId}", encode(taskId));
        }
        return appendPath(resolveTaskBaseEndpoint(params), "/" + encode(taskId) + "/events") + queryString(params);
    }

    private String resolveTaskCreateEndpoint(Map<String, Object> params) {
        String endpoint = stringValue(params.get("targetTaskCreateEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        return appendPath(resolveTaskBaseEndpoint(params), "/create");
    }

    private String resolveTaskDetailEndpoint(Map<String, Object> params, String taskId) {
        String endpoint = stringValue(params.get("targetTaskDetailEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint.replace("{taskId}", encode(taskId));
        }
        return appendPath(resolveTaskBaseEndpoint(params), "/" + encode(taskId)) + queryString(params);
    }

    private String resolveTaskCancelEndpoint(Map<String, Object> params, String taskId) {
        String endpoint = stringValue(params.get("targetTaskCancelEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint.replace("{taskId}", encode(taskId));
        }
        return appendPath(resolveTaskBaseEndpoint(params), "/" + encode(taskId) + "/cancel");
    }

    private String resolveTaskBaseEndpoint(Map<String, Object> params) {
        String endpoint = stringValue(params.get("targetA2aTaskBaseEndpoint"));
        if (!isBlank(endpoint)) {
            return trimTrailingSlash(endpoint);
        }
        String relayEndpoint = resolveRelayEndpoint(params);
        try {
            URI uri = URI.create(relayEndpoint);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/api/ai/a2a/tasks", null, null).toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid target relay endpoint: " + relayEndpoint, e);
        }
    }

    private String resolveGrantValidateEndpoint(Map<String, Object> params) {
        String endpoint = stringValue(params.get("targetGrantValidateEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        String relayEndpoint = resolveRelayEndpoint(params);
        try {
            URI uri = URI.create(relayEndpoint);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/internal/grant/validate", null, null).toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid target relay endpoint: " + relayEndpoint, e);
        }
    }

    private String resolveCenterGrantValidateEndpoint(Map<String, Object> params) {
        String endpoint = stringValue(params.get("centerGrantValidateEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        Map<String, Object> metadata = mapValue(params.get("metadata"));
        endpoint = stringValue(metadata.get("centerGrantValidateEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        return defaultCenterGrantValidateEndpoint();
    }

    private String resolveRelayEndpoint(Map<String, Object> params) {
        String endpoint = stringValue(params.get("targetRelayEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        String targetNodeId = stringValue(params.get("targetNodeId"));
        if (isBlank(targetNodeId)) {
            throw new IllegalArgumentException("targetRelayEndpoint or targetNodeId is required");
        }
        RelayNodeView node = relayRegistryService.getNode(targetNodeId);
        if (node == null || isBlank(node.getRelayEndpoint())) {
            throw new IllegalArgumentException("Target relay endpoint is unavailable for node: " + targetNodeId);
        }
        return node.getRelayEndpoint();
    }

    private String appendPath(String base, String suffix) {
        return trimTrailingSlash(base) + suffix;
    }

    private String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String queryString(Map<String, Object> params) {
        Map<String, Object> query = new LinkedHashMap<>();
        copyQueryParam(params, query, "sessionId");
        copyQueryParam(params, query, "requestId");
        copyQueryParam(params, query, "auditId");
        copyQueryParam(params, query, "agentRunId");
        copyQueryParam(params, query, "sourceNodeId");
        copyQueryParam(params, query, "targetNodeId");
        copyQueryParam(params, query, "grantId");
        copyQueryParam(params, query, "signedToken");
        copyQueryParam(params, query, "expiresAt");
        copyQueryParam(params, query, "allowedCapabilities");
        copyQueryParam(params, query, "targetRelayEndpoint");
        copyQueryParam(params, query, "targetA2aTaskBaseEndpoint");
        copyQueryParam(params, query, "centerGrantValidateEndpoint");
        String defaultCenterGrantValidateEndpoint = resolveCenterGrantValidateEndpoint(params);
        if (!isBlank(defaultCenterGrantValidateEndpoint) && !query.containsKey("centerGrantValidateEndpoint")) {
            query.put("centerGrantValidateEndpoint", defaultCenterGrantValidateEndpoint);
        }
        query.put("forwardedByCenter", "true");
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            builder.append(encode(entry.getKey())).append('=').append(encode(String.valueOf(entry.getValue())));
            first = false;
        }
        return builder.toString();
    }

    private void copyQueryParam(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof List<?> list && !list.isEmpty()) {
            target.put(key, String.join(",", list.stream().map(String::valueOf).toList()));
        } else if (value != null && !isBlank(String.valueOf(value))) {
            target.put(key, value);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String recordAudit(Map<String, Object> params, String taskId, String decision) {
        if (auditService == null) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("decision", decision);
        detail.put("targetRelayEndpoint", safeRelayEndpoint(params));
        detail.put("requestId", firstNonBlank(stringValue(params.get("requestId")), stringValue(params.get("idempotencyKey"))));
        detail.put("agentRunId", stringValue(params.get("agentRunId")));
        return auditService.record(
                stringValue(params.get("sessionId")),
                taskId,
                stringValue(params.get("sourceNodeId")),
                stringValue(params.get("targetNodeId")),
                AuditEventType.A2A_CALLED,
                decision,
                detail,
                "SYSTEM",
                "AI_AGENT_CENTER"
        );
    }

    private String safeRelayEndpoint(Map<String, Object> params) {
        try {
            return resolveRelayEndpoint(params);
        } catch (Exception ignored) {
            return stringValue(params.get("targetRelayEndpoint"));
        }
    }

    private String requiredString(Map<String, Object> params, String key) {
        String value = stringValue(params.get(key));
        if (isBlank(value)) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private RuntimeException asRuntimeException(Exception exception) {
        return exception instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(summarize(exception), exception);
    }

    private String summarize(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().trim().isEmpty()) {
            return exception == null ? "unknown error" : exception.getClass().getSimpleName();
        }
        return exception.getMessage();
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String defaultCenterGrantValidateEndpoint() {
        String property = System.getProperty("wdsavs.ai.relay.grant.validate-endpoint");
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }
        String env = System.getenv("WDSAVS_AI_RELAY_GRANT_VALIDATE_ENDPOINT");
        return env == null || env.trim().isEmpty() ? null : env.trim();
    }

    private static boolean defaultCenterForwardFallbackEnabled() {
        String property = System.getProperty("wdsavs.ai.a2a.center-forward-fallback-enabled");
        if (property != null && !property.trim().isEmpty()) {
            return Boolean.parseBoolean(property.trim());
        }
        String env = System.getenv("WDSAVS_AI_A2A_CENTER_FORWARD_FALLBACK_ENABLED");
        return env == null || env.trim().isEmpty() || Boolean.parseBoolean(env.trim());
    }
}











