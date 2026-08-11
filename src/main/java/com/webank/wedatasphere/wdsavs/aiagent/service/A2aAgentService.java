package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.A2aAgentCard;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiModelConfig;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionMessageCompletion;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionMessageDispatchResult;
import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;
import com.webank.wedatasphere.wdsavs.aiagent.model.CapabilityCode;
import com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConfig;
import com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConvergencePolicy;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.model.ReactExecutionPolicy;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class A2aAgentService {

    private final AiRelayService relayService;
    private final AiCapabilityCatalogService capabilityCatalogService;
    private final AiRelayGrantService relayGrantService;
    private final AiRelayRegistryService relayRegistryService;
    private final AiAuditService auditService;
    private final RestTemplate restTemplate;
    private final A2aPayloadPolicyService payloadPolicyService;
    private final RelayRequestSecurityService requestSecurityService;
    private final boolean centerForwardFallbackEnabled;
    private AiSessionContextService sessionContextService;
    private AiSessionCollaborationService sessionCollaborationService;
    private AiSessionMessageQueueService sessionMessageQueueService;

    public A2aAgentService(AiRelayService relayService,
                           AiCapabilityCatalogService capabilityCatalogService) {
        this(relayService, capabilityCatalogService, null, null, null, new RestTemplate(), new A2aPayloadPolicyServiceImpl(),
                defaultCenterForwardFallbackEnabled());
    }

    public A2aAgentService(AiRelayService relayService,
                           AiCapabilityCatalogService capabilityCatalogService,
                           AiRelayGrantService relayGrantService,
                           AiRelayRegistryService relayRegistryService,
                           AiAuditService auditService,
                           RestTemplate restTemplate) {
        this(relayService, capabilityCatalogService, relayGrantService, relayRegistryService, auditService, restTemplate,
                new A2aPayloadPolicyServiceImpl(), defaultCenterForwardFallbackEnabled());
    }

    public A2aAgentService(AiRelayService relayService,
                           AiCapabilityCatalogService capabilityCatalogService,
                           AiRelayGrantService relayGrantService,
                           AiRelayRegistryService relayRegistryService,
                           AiAuditService auditService,
                           RestTemplate restTemplate,
                           A2aPayloadPolicyService payloadPolicyService) {
        this(relayService, capabilityCatalogService, relayGrantService, relayRegistryService, auditService, restTemplate,
                payloadPolicyService, defaultCenterForwardFallbackEnabled());
    }

    A2aAgentService(AiRelayService relayService,
                    AiCapabilityCatalogService capabilityCatalogService,
                    AiRelayGrantService relayGrantService,
                    AiRelayRegistryService relayRegistryService,
                    AiAuditService auditService,
                    RestTemplate restTemplate,
                    A2aPayloadPolicyService payloadPolicyService,
                    boolean centerForwardFallbackEnabled) {
        this.relayService = relayService;
        this.capabilityCatalogService = capabilityCatalogService;
        this.relayGrantService = relayGrantService;
        this.relayRegistryService = relayRegistryService;
        this.auditService = auditService;
        this.restTemplate = restTemplate;
        this.payloadPolicyService = payloadPolicyService;
        this.requestSecurityService = new RelayRequestSecurityServiceImpl();
        this.centerForwardFallbackEnabled = centerForwardFallbackEnabled;
    }

    public A2aAgentCard agentCard() {
        return capabilityCatalogService.agentCard();
    }

    public A2aJsonRpcResponse handle(A2aJsonRpcRequest request) {
        if (request == null) {
            return A2aJsonRpcResponse.error(null, -32600, "Request is required");
        }
        if (!"message/send".equals(request.getMethod())) {
            return A2aJsonRpcResponse.error(request.getId(), -32601, "Method not found");
        }
        Map<String, Object> params = request.getParams() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getParams());
        String sessionId = stringValue(params.get("sessionId"));
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return A2aJsonRpcResponse.error(request.getId(), -32602, "sessionId is required");
        }
        try {
            if (sessionCollaborationService != null) {
                sessionCollaborationService.enrichTaskParams(params);
            }
            payloadPolicyService.validateMessageParams(params);
            String requestId = firstNonBlank(stringValue(params.get("requestId")), request.getId());
            if (!isBlank(requestId)) {
                params.putIfAbsent("requestId", requestId);
                params.putIfAbsent("idempotencyKey", requestId);
            }
            appendIncomingContext(params, requestId);
            AiSessionMessageDispatchResult dispatchResult = null;
            AiChatResponse response;
            if (shouldQueueMessage(params)) {
                dispatchResult = sessionMessageQueueService.submit(params, requestId, autoWakeWhenDeferred(params));
                response = dispatchResult.getResponse();
            } else {
                response = payloadPolicyService.normalizeChatResponse(invokeWithRouting(params));
                appendOutgoingContext(params, requestId, response);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", response.getAnswer());
            result.put("status", response.getStatus());
            result.put("traceId", response.getTraceId());
            result.put("sessionId", sessionId);
            putIfNotBlank(result, "requestId", requestId);
            putIfNotBlank(result, "auditId", metadataValue(response, "auditId"));
            putIfNotBlank(result, "summary", response.getSummary());
            putIfNotEmpty(result, "artifacts", response.getArtifacts());
            putIfNotEmpty(result, "diagnostics", response.getDiagnostics());
            putIfNotEmpty(result, "metadata", response.getMetadata());
            putIfNotBlank(result, "targetNodeId", stringValue(params.get("targetNodeId")));
            putIfNotBlank(result, "targetRelayEndpoint", resolveRelayEndpoint(params));
            if (dispatchResult != null) {
                putIfNotBlank(result, "queueId", dispatchResult.getQueueId());
                result.put("queued", dispatchResult.isQueued());
            }
            attachExecutionPolicyResult(result, params);
            return A2aJsonRpcResponse.ok(request.getId(), result);
        } catch (IllegalArgumentException e) {
            return A2aJsonRpcResponse.error(request.getId(), -32602, e.getMessage());
        } catch (SecurityException e) {
            return A2aJsonRpcResponse.error(request.getId(), -32003, e.getMessage());
        } catch (Exception e) {
            return A2aJsonRpcResponse.error(request.getId(), -32000, e.getMessage());
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSessionContextService(AiSessionContextService sessionContextService) {
        this.sessionContextService = sessionContextService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSessionCollaborationService(AiSessionCollaborationService sessionCollaborationService) {
        this.sessionCollaborationService = sessionCollaborationService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSessionMessageQueueService(AiSessionMessageQueueService sessionMessageQueueService) {
        this.sessionMessageQueueService = sessionMessageQueueService;
        if (sessionMessageQueueService != null) {
            sessionMessageQueueService.registerDispatcher(this::dispatchQueuedMessage);
            sessionMessageQueueService.registerCompletionListener(this::wakeSourceAfterDeferredCompletion);
        }
    }

    private AiChatResponse invokeWithRouting(Map<String, Object> params) {
        if (!shouldForwardRemote(params)) {
            return localChat(params, "LOCAL_RELAY");
        }
        try {
            return remoteChat(params);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception remoteError) {
            if (!allowCenterForwardFallback(params)) {
                throw remoteError;
            }
            try {
                return localChat(params, "CENTER_FORWARD_FALLBACK");
            } catch (Exception fallbackError) {
                throw new IllegalStateException("Remote relay failed and center forward fallback failed: "
                        + summarize(remoteError) + " | " + summarize(fallbackError), fallbackError);
            }
        }
    }

    private AiChatResponse localChat(Map<String, Object> params, String decision) {
        AiChatRequest request = convertToChatRequest(params);
        AiChatResponse response = relayService.chat(request);
        attachTraceMetadata(response, params, recordAudit(params, response, decision));
        return response;
    }

    private AiChatResponse remoteChat(Map<String, Object> params) {
        String grantId = requiredString(params, "grantId");
        String signedToken = requiredString(params, "signedToken");
        String sessionId = requiredString(params, "sessionId");
        String sourceNodeId = stringValue(params.get("sourceNodeId"));
        String targetNodeId = requiredString(params, "targetNodeId");
        String relayEndpoint = resolveRelayEndpoint(params);
        RelayGrantView grant = relayGrantService.getGrant(grantId);

        RelayGrantValidateRequest validateRequest = new RelayGrantValidateRequest();
        validateRequest.setGrantId(grantId);
        validateRequest.setSessionId(sessionId);
        validateRequest.setSourceNodeId(sourceNodeId);
        validateRequest.setTargetNodeId(targetNodeId);
        validateRequest.setSignedToken(signedToken);
        validateRequest.setExpiresAt(grant.getExpiresAt());
        validateRequest.setAllowedCapabilities(List.of(CapabilityCode.A2A_MESSAGE_SEND.name()));

        RelayGrantValidateResponse localValidation = relayGrantService.validateGrant(validateRequest);
        ensureGrantValid(localValidation, "Local grant validation failed");

        RelayGrantValidateResponse remoteValidation = restTemplate.postForObject(resolveGrantValidateEndpoint(relayEndpoint),
                requestSecurityService.sign(validateRequest), RelayGrantValidateResponse.class);
        ensureGrantValid(remoteValidation, "Remote grant validation failed");

        AiChatRequest request = convertToChatRequest(params);
        attachRelayGrantMetadata(request, validateRequest, resolveCenterGrantValidateEndpoint(params));
        AiChatResponse response = restTemplate.postForObject(relayEndpoint, request, AiChatResponse.class);
        if (response == null) {
            throw new IllegalStateException("Remote relay returned empty response");
        }
        attachTraceMetadata(response, params, recordAudit(params, response, "REMOTE_RELAY"));
        return response;
    }

    private void ensureGrantValid(RelayGrantValidateResponse validation, String messagePrefix) {
        if (validation == null || !Boolean.TRUE.equals(validation.getValid())) {
            String detail = validation == null ? "empty validation response" : validation.getMessage();
            throw new SecurityException(messagePrefix + ": " + detail);
        }
    }

    private AiChatResponse dispatchQueuedMessage(Map<String, Object> params) {
        String requestId = firstNonBlank(stringValue(params.get("requestId")), stringValue(params.get("idempotencyKey")));
        AiChatResponse response = payloadPolicyService.normalizeChatResponse(invokeWithRouting(params));
        if (!isBusyResponse(response)) {
            appendOutgoingContext(params, requestId, response);
        }
        return response;
    }

    private boolean wakeSourceAfterDeferredCompletion(AiSessionMessageCompletion completion) {
        if (completion == null || sessionMessageQueueService == null || relayGrantService == null
                || isBlank(completion.getSourceNodeId()) || isBlank(completion.getTargetNodeId())
                || completion.getSourceNodeId().equals(completion.getTargetNodeId())) {
            return true;
        }
        Map<String, Object> originalMetadata = mapValue(completion.getRequestParams().get("metadata"));
        int wakeDepth = integerValue(originalMetadata.get("wakeDepth")) == null
                ? 0 : integerValue(originalMetadata.get("wakeDepth"));
        int maxWakeDepth = integerValue(mapValue(originalMetadata.get("collaborationPolicy")).get("maxWakeDepth")) == null
                ? 8 : integerValue(mapValue(originalMetadata.get("collaborationPolicy")).get("maxWakeDepth"));
        if (wakeDepth >= Math.max(1, maxWakeDepth)) {
            return true;
        }

        RelayAccessRequest accessRequest = new RelayAccessRequest();
        accessRequest.setSessionId(completion.getSessionId());
        accessRequest.setRequestId("wake-grant:" + completion.getQueueId());
        accessRequest.setSourceNodeId(completion.getTargetNodeId());
        accessRequest.setTargetNodeId(completion.getSourceNodeId());
        accessRequest.setReason("Resume a deferred multi-agent collaboration turn");
        accessRequest.setRequiredCapabilities(List.of(CapabilityCode.A2A_MESSAGE_SEND.name()));
        RelayAccessDecisionResponse grant = relayGrantService.requestAccess(accessRequest);
        if (grant == null || !"ALLOW".equalsIgnoreCase(grant.getDecision())) {
            return false;
        }

        String wakeRequestId = "wake:" + completion.getQueueId();
        Map<String, Object> wakeParams = new LinkedHashMap<>();
        wakeParams.put("sessionId", completion.getSessionId());
        wakeParams.put("requestId", wakeRequestId);
        wakeParams.put("idempotencyKey", wakeRequestId);
        wakeParams.put("sourceNodeId", completion.getTargetNodeId());
        wakeParams.put("targetNodeId", completion.getSourceNodeId());
        wakeParams.put("targetRelayEndpoint", grant.getTargetRelayEndpoint());
        wakeParams.put("grantId", grant.getGrantId());
        wakeParams.put("signedToken", grant.getSignedToken());
        wakeParams.put("expiresAt", grant.getExpiresAt());
        wakeParams.put("allowCenterForwardFallback", Boolean.FALSE);
        wakeParams.put("contextAlreadyAppended", Boolean.TRUE);
        wakeParams.put("messages", List.of(Map.of(
                "role", "user",
                "content", "节点 " + completion.getTargetNodeId()
                        + " 已完成此前排队的协作请求。请同步共享上下文中的最新回复，继续推进当前用户问题；"
                        + "如仍需其他节点参与，请通过标准 ccrelay-cli 协作命令联系，达到收敛条件后再给出结论。")));
        Map<String, Object> wakeMetadata = new LinkedHashMap<>(originalMetadata);
        wakeMetadata.put("wakeDepth", wakeDepth + 1);
        wakeMetadata.put("wakeReason", "DEFERRED_MESSAGE_COMPLETED");
        wakeMetadata.put("completedQueueId", completion.getQueueId());
        wakeParams.put("metadata", wakeMetadata);
        if (sessionCollaborationService != null) {
            sessionCollaborationService.enrichTaskParams(wakeParams);
        }
        AiSessionMessageDispatchResult result = sessionMessageQueueService.submit(wakeParams, wakeRequestId, false);
        return result != null && result.getResponse() != null
                && !"FAILED".equalsIgnoreCase(result.getResponse().getStatus());
    }

    private boolean shouldQueueMessage(Map<String, Object> params) {
        return sessionMessageQueueService != null && shouldForwardRemote(params) && !isControlMessage(params);
    }

    private boolean autoWakeWhenDeferred(Map<String, Object> params) {
        String sourceNodeId = stringValue(params.get("sourceNodeId"));
        String collaborationMode = stringValue(mapValue(params.get("metadata")).get("collaborationMode"));
        return ("DISCUSSION".equalsIgnoreCase(collaborationMode)
                || "SESSION_FOLLOW_UP".equalsIgnoreCase(collaborationMode))
                && !isBlank(sourceNodeId)
                && !sourceNodeId.toLowerCase().contains("center-ui");
    }

    private boolean isControlMessage(Map<String, Object> params) {
        return !mapValue(params.get("control")).isEmpty()
                || !mapValue(mapValue(params.get("metadata")).get("control")).isEmpty();
    }

    private boolean isBusyResponse(AiChatResponse response) {
        return response != null && ("BUSY".equalsIgnoreCase(response.getStatus())
                || "QUEUED".equalsIgnoreCase(response.getStatus()));
    }

    private void validateGrant(Map<String, Object> params, CapabilityCode capabilityCode) {
        String grantId = requiredString(params, "grantId");
        RelayGrantView grant = relayGrantService.getGrant(grantId);
        List<String> capabilities = grant == null || grant.getAllowedCapabilities() == null
                ? List.of(capabilityCode.name())
                : grant.getAllowedCapabilities();
        if (!capabilities.contains(capabilityCode.name())) {
            throw new SecurityException("Grant capability is not sufficient: " + capabilityCode.name());
        }
        RelayGrantValidateRequest request = new RelayGrantValidateRequest();
        request.setGrantId(grantId);
        request.setSessionId(requiredString(params, "sessionId"));
        request.setSourceNodeId(stringValue(params.get("sourceNodeId")));
        request.setTargetNodeId(requiredString(params, "targetNodeId"));
        request.setSignedToken(requiredString(params, "signedToken"));
        request.setExpiresAt(grant == null ? stringValue(params.get("expiresAt")) : grant.getExpiresAt());
        request.setAllowedCapabilities(capabilities);
        ensureGrantValid(relayGrantService.validateGrant(request), "Local grant validation failed");
    }

    private boolean shouldForwardRemote(Map<String, Object> params) {
        return !isBlank(stringValue(params.get("targetNodeId"))) || !isBlank(stringValue(params.get("targetRelayEndpoint")));
    }

    private boolean allowCenterForwardFallback(Map<String, Object> params) {
        Object override = params.get("allowCenterForwardFallback");
        if (override == null) {
            return centerForwardFallbackEnabled;
        }
        return booleanValue(override);
    }

    private String resolveRelayEndpoint(Map<String, Object> params) {
        String endpoint = stringValue(params.get("targetRelayEndpoint"));
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        String targetNodeId = stringValue(params.get("targetNodeId"));
        if (isBlank(targetNodeId)) {
            return null;
        }
        RelayNodeView node = relayRegistryService.getNode(targetNodeId);
        if (node == null || isBlank(node.getRelayEndpoint())) {
            throw new IllegalArgumentException("Target relay endpoint is unavailable for node: " + targetNodeId);
        }
        return node.getRelayEndpoint();
    }

    private String resolveGrantValidateEndpoint(String relayEndpoint) {
        if (isBlank(relayEndpoint)) {
            throw new IllegalArgumentException("targetRelayEndpoint is required");
        }
        try {
            URI uri = URI.create(relayEndpoint);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/internal/grant/validate", null, null).toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid targetRelayEndpoint: " + relayEndpoint, e);
        }
    }

    private String recordAudit(Map<String, Object> params, AiChatResponse response, String decision) {
        if (auditService == null) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", response == null ? null : response.getStatus());
        detail.put("traceId", response == null ? UUID.randomUUID().toString() : response.getTraceId());
        detail.put("targetRelayEndpoint", resolveRelayEndpointSafely(params));
        return auditService.record(
                stringValue(params.get("sessionId")),
                stringValue(params.get("taskId")),
                stringValue(params.get("sourceNodeId")),
                stringValue(params.get("targetNodeId")),
                AuditEventType.A2A_CALLED,
                decision,
                detail,
                "SYSTEM",
                "AI_AGENT_CENTER"
        );
    }


    private void attachTraceMetadata(AiChatResponse response, Map<String, Object> params, String auditId) {
        if (response == null) {
            return;
        }
        Map<String, Object> metadata = response.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.getMetadata());
        putIfNotBlank(metadata, "sessionId", stringValue(params.get("sessionId")));
        putIfNotBlank(metadata, "requestId", firstNonBlank(stringValue(params.get("requestId")), stringValue(params.get("idempotencyKey"))));
        putIfNotBlank(metadata, "auditId", auditId);
        response.setMetadata(metadata);
    }

    private String metadataValue(AiChatResponse response, String key) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return stringValue(response.getMetadata().get(key));
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
    private void attachRelayGrantMetadata(AiChatRequest request, RelayGrantValidateRequest validateRequest,
                                         String centerGrantValidateEndpoint) {
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
        putIfNotEmpty(relayGrant, "allowedCapabilities", validateRequest.getAllowedCapabilities());
        putIfNotBlank(relayGrant, "centerGrantValidateEndpoint", centerGrantValidateEndpoint);
        metadata.put("relayGrant", relayGrant);
        putIfNotBlank(metadata, "centerGrantValidateEndpoint", centerGrantValidateEndpoint);
        request.setMetadata(metadata);
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



    private String resolveRelayEndpointSafely(Map<String, Object> params) {
        try {
            return resolveRelayEndpoint(params);
        } catch (Exception ignored) {
            return stringValue(params.get("targetRelayEndpoint"));
        }
    }

    private AiChatRequest convertToChatRequest(Map<String, Object> params) {
        Map<String, Object> source = params == null ? Map.of() : params;
        AiChatRequest request = new AiChatRequest();
        request.setSystemPrompt(stringValue(source.get("systemPrompt")));
        request.setMessages(parseMessages(source.get("messages")));
        Map<String, Object> metadata = mapValue(source.get("metadata"));
        putIfNotBlank(metadata, "sessionId", stringValue(source.get("sessionId")));
        putIfNotBlank(metadata, "requestId", firstNonBlank(stringValue(source.get("requestId")), stringValue(source.get("idempotencyKey"))));
        putIfNotBlank(metadata, "sourceNodeId", stringValue(source.get("sourceNodeId")));
        putIfNotBlank(metadata, "targetNodeId", stringValue(source.get("targetNodeId")));
        if (sessionContextService != null) {
            putIfNotBlank(metadata, "centerContextDeltaEndpoint", resolveCenterContextDeltaEndpoint(source));
        }
        putIfNotBlank(metadata, "contextHeadCursor", stringValue(source.get("contextHeadCursor")));
        ReactExecutionPolicy policy = ReactExecutionPolicy.fromParams(source);
        metadata.put("executionMode", policy.getExecutionMode());
        metadata.put("react", policy.toSummaryMap());
        Map<String, Object> control = mapValue(source.get("control"));
        if (!control.isEmpty()) {
            metadata.put("control", control);
        }
        request.setMetadata(metadata);
        request.setModelConfig(parseModelConfig(mapValue(source.get("modelConfig"))));
        return request;
    }

    private void appendIncomingContext(Map<String, Object> params, String requestId) {
        String sessionId = stringValue(params.get("sessionId"));
        if (sessionContextService == null || isBlank(sessionId)) {
            return;
        }
        if (shouldForwardRemote(params) && relayGrantService != null) {
            validateGrant(params, CapabilityCode.A2A_MESSAGE_SEND);
        }
        String content = latestUserMessage(params);
        if (isBlank(content)) {
            return;
        }
        AiSessionContextAppendRequest contextRequest = new AiSessionContextAppendRequest();
        contextRequest.setEventId("a2a-input:" + firstNonBlank(requestId, UUID.randomUUID().toString()));
        contextRequest.setTaskId(stringValue(params.get("taskId")));
        contextRequest.setSenderType(firstNonBlank(stringValue(params.get("senderType")), "COORDINATOR"));
        contextRequest.setSenderId(stringValue(params.get("sourceNodeId")));
        contextRequest.setTargetNodeId(stringValue(params.get("targetNodeId")));
        contextRequest.setRole("user");
        contextRequest.setContent(content);
        contextRequest.setContentType("TEXT");
        sessionContextService.append(sessionId, contextRequest);
        params.put("contextHeadCursor", sessionContextService.headCursor(sessionId));
    }

    private void appendOutgoingContext(Map<String, Object> params, String requestId, AiChatResponse response) {
        String sessionId = stringValue(params.get("sessionId"));
        if (sessionContextService == null || response == null || isBlank(response.getAnswer()) || isBlank(sessionId)) {
            return;
        }
        AiSessionContextAppendRequest contextRequest = new AiSessionContextAppendRequest();
        contextRequest.setEventId("a2a-output:"
                + firstNonBlank(requestId, response.getTraceId(), UUID.randomUUID().toString()));
        contextRequest.setTaskId(stringValue(params.get("taskId")));
        contextRequest.setSenderType("RELAY");
        contextRequest.setSenderId(firstNonBlank(
                stringValue(params.get("targetNodeId")), stringValue(params.get("sourceNodeId"))));
        contextRequest.setTargetNodeId(stringValue(params.get("sourceNodeId")));
        contextRequest.setRole("assistant");
        contextRequest.setContent(response.getAnswer());
        contextRequest.setContentType("TEXT");
        sessionContextService.append(sessionId, contextRequest);
    }

    private String latestUserMessage(Map<String, Object> params) {
        List<AiChatMessage> messages = parseMessages(params.get("messages"));
        for (int index = messages.size() - 1; index >= 0; index--) {
            AiChatMessage message = messages.get(index);
            if (message != null && "user".equalsIgnoreCase(firstNonBlank(message.getRole(), "user"))
                    && !isBlank(message.getContent())) {
                return message.getContent();
            }
        }
        return stringValue(mapValue(params.get("input")).get("prompt"));
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

    private void attachExecutionPolicyResult(Map<String, Object> result, Map<String, Object> params) {
        ReactExecutionPolicy policy = ReactExecutionPolicy.fromParams(params);
        result.put("executionMode", policy.getExecutionMode());
        result.put("react", policy.toSummaryMap());
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
        AiModelConfig config = new AiModelConfig();
        config.setProvider(stringValue(source.getOrDefault("provider", "CLAUDE_CODE")));
        config.setEndpoint(stringValue(source.get("endpoint")));
        config.setRemoteEndpoint(stringValue(source.get("remoteEndpoint")));
        config.setModel(stringValue(source.get("model")));
        Object claudeCodeValue = source.get("claudeCode");
        if (claudeCodeValue != null) {
            Map<String, Object> claudeCodeMap = mapValue(claudeCodeValue);
            ClaudeCodeConfig claudeCodeConfig = new ClaudeCodeConfig();
            claudeCodeConfig.setCommand(stringValue(claudeCodeMap.get("command")));
            claudeCodeConfig.setWorkingDirectory(stringValue(claudeCodeMap.get("workingDirectory")));
            claudeCodeConfig.setEnvironment(mapValue(claudeCodeMap.get("environment")));
            config.setClaudeCode(claudeCodeConfig);
        }
        Object convergencePolicyValue = source.get("convergencePolicy");
        if (convergencePolicyValue != null) {
            config.setConvergencePolicy(parseConvergencePolicy(mapValue(convergencePolicyValue)));
        }
        return config;
    }

    private ClaudeCodeConvergencePolicy parseConvergencePolicy(Map<String, Object> source) {
        ClaudeCodeConvergencePolicy policy = new ClaudeCodeConvergencePolicy();
        policy.setMaxRatSteps(integerValue(source.get("maxRatSteps")));
        policy.setMaxRetries(integerValue(source.get("maxRetries")));
        policy.setMaxNoProgressRounds(integerValue(source.get("maxNoProgressRounds")));
        policy.setMaxDurationMs(longValue(source.get("maxDurationMs")));
        policy.setRepeatedActionThreshold(integerValue(source.get("repeatedActionThreshold")));
        policy.setRetryDelayMs(longValue(source.get("retryDelayMs")));
        policy.setMaxPromptChars(integerValue(source.get("maxPromptChars")));
        policy.setMaxResponseChars(integerValue(source.get("maxResponseChars")));
        policy.setHumanApprovalPauseTimeoutMs(longValue(source.get("humanApprovalPauseTimeoutMs")));
        policy.setEnableRepeatActionDetection(booleanValue(source.get("enableRepeatActionDetection")));
        policy.setEnableHumanApprovalPause(booleanValue(source.get("enableHumanApprovalPause")));
        return policy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
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

    private void putIfNotEmpty(Map<String, Object> target, String key, Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            target.put(key, value);
            return;
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            target.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String summarize(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().trim().isEmpty()) {
            return exception == null ? "unknown error" : exception.getClass().getSimpleName();
        }
        return exception.getMessage();
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String defaultCenterGrantValidateEndpoint() {
        String property = System.getProperty("wdsavs.ai.relay.grant.validate-endpoint");
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }
        String env = System.getenv("WDSAVS_AI_RELAY_GRANT_VALIDATE_ENDPOINT");
        return env == null || env.trim().isEmpty() ? null : env.trim();
    }


    private Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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




