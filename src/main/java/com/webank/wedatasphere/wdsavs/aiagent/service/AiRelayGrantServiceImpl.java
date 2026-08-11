package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayGrantEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantRenewRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantRevokeRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayGrantRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AiRelayGrantServiceImpl implements AiRelayGrantService {

    private static final long DEFAULT_TTL_MS = 30L * 60L * 1000L;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    private static final String STATUS_WAITING_DEPLOY = "WAITING_DEPLOY";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String STATUS_REVOKE_SCHEDULED = "REVOKE_SCHEDULED";

    private final AiRelayGrantRepository grantRepository;
    private final AiRelayHeartbeatService heartbeatService;
    private final RelayGrantTokenService relayGrantTokenService;
    private final AiRelayRegistryService relayRegistryService;
    private final AiAuditService auditService;
    private final AiTaskRepository taskRepository;
    private final AiTaskEventService taskEventService;
    private final AiSessionService sessionService;
    private final RuntimeConfigService runtimeConfigService;
    private final long defaultTtlMs;
    private final boolean autoApproveEnabled;
    private final boolean renewEnabled;
    private final boolean revokeImmediate;
    private final boolean nodeWhitelistEnabled;
    private final List<String> allowedNodeIds;
    private final RelayRequestSecurityService requestSecurityService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    public AiRelayGrantServiceImpl(AiRelayGrantRepository grantRepository,
                                   AiRelayHeartbeatService heartbeatService,
                                   RelayGrantTokenService relayGrantTokenService,
                                   AiRelayRegistryService relayRegistryService,
                                   AiAuditService auditService,
                                   AiTaskRepository taskRepository,
                                   AiTaskEventService taskEventService,
                                   AiSessionService sessionService,
                                   RuntimeConfigService runtimeConfigService) {
        this(grantRepository,
                heartbeatService,
                relayGrantTokenService,
                relayRegistryService,
                auditService,
                taskRepository,
                taskEventService,
                sessionService,
                longConfig("wdsavs.ai.relay.grant.default-ttl-ms", "WDSAVS_AI_RELAY_GRANT_DEFAULT_TTL_MS", DEFAULT_TTL_MS),
                booleanConfig("wdsavs.ai.relay.grant.auto-approve-enabled", "WDSAVS_AI_RELAY_GRANT_AUTO_APPROVE_ENABLED", true),
                booleanConfig("wdsavs.ai.relay.grant.renew-enabled", "WDSAVS_AI_RELAY_GRANT_RENEW_ENABLED", true),
                booleanConfig("wdsavs.ai.relay.grant.revoke-immediate", "WDSAVS_AI_RELAY_GRANT_REVOKE_IMMEDIATE", true),
                booleanConfig("wdsavs.ai.relay.node-whitelist-enabled", "WDSAVS_AI_RELAY_NODE_WHITELIST_ENABLED", true),
                listConfig("wdsavs.ai.relay.allowed-node-ids", "WDSAVS_AI_RELAY_ALLOWED_NODE_IDS", List.of("*")),
                runtimeConfigService);
    }

    AiRelayGrantServiceImpl(AiRelayGrantRepository grantRepository,
                            AiRelayHeartbeatService heartbeatService,
                            RelayGrantTokenService relayGrantTokenService,
                            AiRelayRegistryService relayRegistryService,
                            long defaultTtlMs,
                            boolean autoApproveEnabled,
                            boolean renewEnabled,
                            boolean revokeImmediate,
                            boolean nodeWhitelistEnabled,
                            List<String> allowedNodeIds) {
        this(grantRepository, heartbeatService, relayGrantTokenService, relayRegistryService, null, null, null, null,
                defaultTtlMs, autoApproveEnabled, renewEnabled, revokeImmediate, nodeWhitelistEnabled, allowedNodeIds, null);
    }

    AiRelayGrantServiceImpl(AiRelayGrantRepository grantRepository,
                            AiRelayHeartbeatService heartbeatService,
                            RelayGrantTokenService relayGrantTokenService,
                            AiRelayRegistryService relayRegistryService,
                            AiAuditService auditService,
                            long defaultTtlMs,
                            boolean autoApproveEnabled,
                            boolean renewEnabled,
                            boolean revokeImmediate,
                            boolean nodeWhitelistEnabled,
                            List<String> allowedNodeIds) {
        this(grantRepository, heartbeatService, relayGrantTokenService, relayRegistryService, auditService, null, null, null,
                defaultTtlMs, autoApproveEnabled, renewEnabled, revokeImmediate, nodeWhitelistEnabled, allowedNodeIds, null);
    }

    AiRelayGrantServiceImpl(AiRelayGrantRepository grantRepository,
                            AiRelayHeartbeatService heartbeatService,
                            RelayGrantTokenService relayGrantTokenService,
                            AiRelayRegistryService relayRegistryService,
                            AiAuditService auditService,
                            AiTaskRepository taskRepository,
                            AiTaskEventService taskEventService,
                            AiSessionService sessionService,
                            long defaultTtlMs,
                            boolean autoApproveEnabled,
                            boolean renewEnabled,
                            boolean revokeImmediate,
                            boolean nodeWhitelistEnabled,
                            List<String> allowedNodeIds) {
        this(grantRepository, heartbeatService, relayGrantTokenService, relayRegistryService, auditService, taskRepository, taskEventService,
                sessionService, defaultTtlMs, autoApproveEnabled, renewEnabled, revokeImmediate, nodeWhitelistEnabled, allowedNodeIds, null);
    }

    AiRelayGrantServiceImpl(AiRelayGrantRepository grantRepository,
                            AiRelayHeartbeatService heartbeatService,
                            RelayGrantTokenService relayGrantTokenService,
                            AiRelayRegistryService relayRegistryService,
                            AiAuditService auditService,
                            AiTaskRepository taskRepository,
                            AiTaskEventService taskEventService,
                            AiSessionService sessionService,
                            long defaultTtlMs,
                            boolean autoApproveEnabled,
                            boolean renewEnabled,
                            boolean revokeImmediate,
                            boolean nodeWhitelistEnabled,
                            List<String> allowedNodeIds,
                            RuntimeConfigService runtimeConfigService) {
        this.grantRepository = grantRepository;
        this.heartbeatService = heartbeatService;
        this.relayGrantTokenService = relayGrantTokenService;
        this.relayRegistryService = relayRegistryService;
        this.auditService = auditService;
        this.taskRepository = taskRepository;
        this.taskEventService = taskEventService;
        this.sessionService = sessionService;
        this.runtimeConfigService = runtimeConfigService;
        this.defaultTtlMs = defaultTtlMs <= 0 ? DEFAULT_TTL_MS : defaultTtlMs;
        this.autoApproveEnabled = autoApproveEnabled;
        this.renewEnabled = renewEnabled;
        this.revokeImmediate = revokeImmediate;
        this.nodeWhitelistEnabled = nodeWhitelistEnabled;
        this.allowedNodeIds = normalizeAllowedNodeIds(allowedNodeIds);
        this.requestSecurityService = new RelayRequestSecurityServiceImpl();
    }
    @Override
        public RelayAccessDecisionResponse requestAccess(RelayAccessRequest request) {
        validateAccessRequest(request);
        AiTaskEntity parentTask = findParentTask(request.getParentTaskId(), request.getSessionId());
        RelayNodeView requestedNode = resolveNode(request.getTargetNodeId());
        Optional<AiRelayGrantEntity> existing = findByRequestId(request.getRequestId());
        if (existing.isPresent()) {
            RelayAccessDecisionResponse response = existingDecisionResponse(existing.get(), requestedNode);
            syncParentTaskFromAccess(parentTask, request, response, existing.get());
            return response;
        }

        List<String> capabilities = normalizedCapabilities(request.getRequiredCapabilities());
        long ttlMs = request.getTtlMs() == null || request.getTtlMs() <= 0 ? resolveDefaultTtlMs() : request.getTtlMs();
        String requestAuditId = recordGrantAudit(request.getSessionId(), null, request.getSourceNodeId(), request.getTargetNodeId(),
                AuditEventType.ACCESS_REQUESTED, "REQUESTED", buildAccessRequestDetail(request, null, capabilities, ttlMs, null),
                "NODE", request.getSourceNodeId());
        if (!isNodeAllowed(request.getTargetNodeId())) {
            RelayAccessDecisionResponse response = new RelayAccessDecisionResponse();
            response.setAuditId(recordGrantAudit(request.getSessionId(), null, request.getSourceNodeId(), request.getTargetNodeId(),
                    AuditEventType.ACCESS_DENIED, "TARGET_NODE_NOT_ALLOWED",
                    buildAccessRequestDetail(request, null, capabilities, ttlMs, "TARGET_NODE_NOT_ALLOWED"),
                    "SYSTEM", "WDSAVS_CC"));
            response.setDecision("DENY");
            response.setErrorCode("TARGET_NODE_NOT_ALLOWED");
            response.setMessage("Target node is not in whitelist: " + request.getTargetNodeId());
            syncParentTaskFromAccess(parentTask, request, response, null);
            return response;
        }

        String now = String.valueOf(System.currentTimeMillis());
        String grantId = UUID.randomUUID().toString();
        String requestId = isBlank(request.getRequestId()) ? UUID.randomUUID().toString() : request.getRequestId();
        String expiresAt = String.valueOf(System.currentTimeMillis() + ttlMs);
        boolean nodeAvailable = isNodeReady(requestedNode, request.getTargetNodeId());
        boolean approved = resolveAutoApproveEnabled();
        String status = !approved ? STATUS_PENDING_APPROVAL : (nodeAvailable ? STATUS_ACTIVE : STATUS_WAITING_DEPLOY);
        String signedToken = approved && nodeAvailable
                ? relayGrantTokenService.sign(grantId, request.getSessionId(), request.getSourceNodeId(), request.getTargetNodeId(), capabilities, expiresAt)
                : null;

        AiRelayGrantEntity entity = new AiRelayGrantEntity();
        entity.setGrantId(grantId);
        entity.setSessionId(request.getSessionId());
        entity.setRequestId(requestId);
        entity.setSourceNodeId(request.getSourceNodeId());
        entity.setTargetNodeId(request.getTargetNodeId());
        entity.setAllowedCapabilitiesJson(writeJsonList(capabilities));
        entity.setSignedToken(signedToken);
        entity.setStatus(status);
        entity.setExpireAt(expiresAt);
        entity.setReason(request.getReason());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        grantRepository.save(entity);

        RelayAccessDecisionResponse response = toDecisionResponse(entity, requestedNode);
        if (!approved) {
            response.setErrorCode("APPROVAL_REQUIRED");
            response.setMessage("Relay access is pending manual approval");
            response.setAuditId(requestAuditId);
        } else if (nodeAvailable) {
            response.setAuditId(recordGrantAudit(entity.getSessionId(), null, entity.getSourceNodeId(), entity.getTargetNodeId(),
                    AuditEventType.ACCESS_GRANTED, response.getDecision(),
                    buildAccessRequestDetail(request, entity, capabilities, ttlMs, null),
                    "SYSTEM", "WDSAVS_CC"));
        } else {
            response.setAuditId(requestAuditId);
            response.setMessage("Target node is not yet available; self replication or center deployment is required before grant issuance");
        }
        syncParentTaskFromAccess(parentTask, request, response, entity);
        return response;
    }
    @Override
        public RelayGrantView renewGrant(RelayGrantRenewRequest request) {
        if (request == null || isBlank(request.getGrantId())) {
            throw new IllegalArgumentException("grantId is required");
        }
        if (!resolveRenewEnabled()) {
            throw new IllegalStateException("Grant renew is disabled");
        }
        AiRelayGrantEntity entity = grantRepository.findByGrantId(request.getGrantId())
                .orElseThrow(() -> new IllegalArgumentException("Grant not found: " + request.getGrantId()));
        if (!isRenewableStatus(entity.getStatus())) {
            throw new IllegalStateException("Only active, pending approval, waiting deploy, or revoke scheduled grants can be renewed");
        }
        long ttlMs = request.getTtlMs() == null || request.getTtlMs() <= 0 ? resolveDefaultTtlMs() : request.getTtlMs();
        long renewBaseTime = Math.max(System.currentTimeMillis(), parseLong(entity.getExpireAt()));
        String expiresAt = String.valueOf(renewBaseTime + ttlMs);
        List<String> capabilities = readJsonList(entity.getAllowedCapabilitiesJson());
        entity.setExpireAt(expiresAt);
        RelayNodeView node = resolveNode(entity.getTargetNodeId());
        if (STATUS_ACTIVE.equalsIgnoreCase(entity.getStatus()) && isNodeReady(node, entity.getTargetNodeId())) {
            entity.setSignedToken(relayGrantTokenService.sign(entity.getGrantId(), entity.getSessionId(), entity.getSourceNodeId(), entity.getTargetNodeId(),
                    capabilities, expiresAt));
        }
        entity.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        grantRepository.save(entity);
        String auditId = recordGrantAudit(entity.getSessionId(), null, entity.getSourceNodeId(), entity.getTargetNodeId(),
                AuditEventType.GRANT_RENEWED, "RENEWED", buildGrantStateDetail(entity, capabilities), "SYSTEM", "WDSAVS_CC");
        return toView(entity, auditId);
    }
    @Override
        public Boolean revokeGrant(RelayGrantRevokeRequest request) {
        if (request == null || isBlank(request.getGrantId())) {
            throw new IllegalArgumentException("grantId is required");
        }
        AiRelayGrantEntity entity = grantRepository.findByGrantId(request.getGrantId())
                .orElseThrow(() -> new IllegalArgumentException("Grant not found: " + request.getGrantId()));
        String now = String.valueOf(System.currentTimeMillis());
        entity.setStatus(resolveRevokeImmediate() ? STATUS_REVOKED : STATUS_REVOKE_SCHEDULED);
        entity.setRevokedAt(now);
        entity.setReason(request.getReason());
        entity.setUpdateTime(now);
        grantRepository.save(entity);
        recordGrantAudit(entity.getSessionId(), null, entity.getSourceNodeId(), entity.getTargetNodeId(),
                AuditEventType.GRANT_REVOKED, entity.getStatus(), buildGrantStateDetail(entity, readJsonList(entity.getAllowedCapabilitiesJson())),
                "SYSTEM", "WDSAVS_CC");
        return Boolean.TRUE;
    }
    @Override
    public RelayGrantView getGrant(String grantId) {
        return toView(grantRepository.findByGrantId(grantId)
                .orElseThrow(() -> new IllegalArgumentException("Grant not found: " + grantId)), null);
    }

    @Override
    public RelayAccessDecisionResponse activateGrantIfReady(String grantId) {
        if (isBlank(grantId)) {
            throw new IllegalArgumentException("grantId is required");
        }
        AiRelayGrantEntity entity = grantRepository.findByGrantId(grantId)
                .orElseThrow(() -> new IllegalArgumentException("Grant not found: " + grantId));
        return existingDecisionResponse(entity, resolveNode(entity.getTargetNodeId()));
    }

    @Override
    public RelayGrantValidateResponse validateGrant(RelayGrantValidateRequest request) {
        if (request == null || isBlank(request.getGrantId())) {
            throw new IllegalArgumentException("grantId is required");
        }
        AiRelayGrantEntity entity = grantRepository.findByGrantId(request.getGrantId())
                .orElseThrow(() -> new IllegalArgumentException("Grant not found: " + request.getGrantId()));
        if (!isValidatableStatus(entity.getStatus())) {
            return new RelayGrantValidateResponse(false, entity.getStatus(), "Grant is not active");
        }
        long expiresAt = parseLong(entity.getExpireAt());
        if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
            return new RelayGrantValidateResponse(false, "EXPIRED", "Grant has expired");
        }
        List<String> capabilities = readJsonList(entity.getAllowedCapabilitiesJson());
        List<String> normalizedStoredCapabilities = normalizedCapabilities(capabilities);
        List<String> requiredCapabilities = normalizedCapabilities(request.getAllowedCapabilities());
        if (!normalizedStoredCapabilities.containsAll(requiredCapabilities)) {
            return new RelayGrantValidateResponse(false, "CAPABILITY_DENIED", "Grant capability is not sufficient");
        }
        if (!isBlank(request.getRequestSignature()) || !isBlank(request.getRequestTimestamp()) || !isBlank(request.getRequestNonce())) {
            RelayRequestSecurityValidationResult securityValidation = requestSecurityService.validate(request, true);
            if (!securityValidation.isValid()) {
                return new RelayGrantValidateResponse(false, securityValidation.getStatus(), securityValidation.getMessage());
            }
        }
        if (isBlank(request.getSignedToken()) || isBlank(entity.getSignedToken())) {
            return new RelayGrantValidateResponse(false, "INVALID", "Grant token is missing");
        }
        boolean valid = relayGrantTokenService.validate(
                request.getSignedToken(),
                entity.getGrantId(),
                entity.getSessionId(),
                entity.getSourceNodeId(),
                entity.getTargetNodeId(),
                capabilities,
                entity.getExpireAt());
        return new RelayGrantValidateResponse(valid, valid ? entity.getStatus() : "INVALID",
                valid ? "Grant token is valid" : "Grant token validation failed");
    }

    private AiTaskEntity findParentTask(String parentTaskId, String sessionId) {
        if (taskRepository == null || isBlank(parentTaskId)) {
            return null;
        }
        AiTaskEntity parent = taskRepository.findByTaskId(parentTaskId.trim()).orElse(null);
        if (parent == null) {
            throw new IllegalArgumentException("parentTaskId not found: " + parentTaskId);
        }
        if (!isBlank(sessionId) && !sessionId.equals(parent.getSessionId())) {
            throw new IllegalArgumentException("parentTaskId does not belong to sessionId: " + sessionId);
        }
        return parent;
    }

    private void syncParentTaskFromAccess(AiTaskEntity parentTask, RelayAccessRequest request,
                                          RelayAccessDecisionResponse response, AiRelayGrantEntity entity) {
        if (parentTask == null || taskRepository == null) {
            return;
        }
        String now = String.valueOf(System.currentTimeMillis());
        Map<String, Object> result = readJsonMap(parentTask.getResultJson());
        putIfNotBlank(result, "accessParentTaskId", parentTask.getTaskId());
        putIfNotBlank(result, "accessRequestId", request == null ? null : request.getRequestId());
        putIfNotBlank(result, "accessGrantId", response == null ? null : response.getGrantId());
        putIfNotBlank(result, "accessDecision", response == null ? null : response.getDecision());
        putIfNotBlank(result, "accessAuditId", response == null ? null : response.getAuditId());
        putIfNotBlank(result, "accessTargetNodeId", request == null ? null : request.getTargetNodeId());
        putIfNotBlank(result, "accessSourceNodeId", request == null ? null : request.getSourceNodeId());
        putIfNotBlank(result, "accessTargetRelayEndpoint", response == null ? null : response.getTargetRelayEndpoint());
        putIfNotBlank(result, "accessErrorCode", response == null ? null : response.getErrorCode());
        putIfNotBlank(result, "accessMessage", response == null ? null : response.getMessage());
        if (response != null && response.getAllowedCapabilities() != null && !response.getAllowedCapabilities().isEmpty()) {
            result.put("accessAllowedCapabilities", response.getAllowedCapabilities());
        }
        if (entity != null) {
            putIfNotBlank(result, "accessGrantStatus", entity.getStatus());
            putIfNotBlank(result, "accessGrantExpiresAt", entity.getExpireAt());
        }
        if (!isTerminal(parentTask.getStatus())) {
            if (response != null) {
                switch (response.getDecision()) {
                    case "ALLOW_WITH_DEPLOY" -> {
                        parentTask.setStatus("WAITING_DEPLOY");
                        parentTask.setCurrentStage("ACCESS_WAITING_DEPLOY");
                        parentTask.setErrorCode(null);
                        parentTask.setErrorMessage(null);
                    }
                    case "WAIT_APPROVAL" -> {
                        parentTask.setStatus("WAITING_APPROVAL");
                        parentTask.setCurrentStage("ACCESS_WAITING_APPROVAL");
                    }
                    case "DENY" -> {
                        parentTask.setStatus("FAILED");
                        parentTask.setCurrentStage("ACCESS_DENIED");
                        parentTask.setErrorCode(firstNonBlank(response.getErrorCode(), "ACCESS_DENIED"));
                        parentTask.setErrorMessage(response.getMessage());
                        parentTask.setEndTime(now);
                    }
                    case "ALLOW" -> {
                        parentTask.setStatus("PARTIAL_SUCCESS");
                        parentTask.setCurrentStage("ACCESS_GRANTED");
                        parentTask.setErrorCode(null);
                        parentTask.setErrorMessage(null);
                    }
                    default -> {
                    }
                }
            }
        }
        parentTask.setUpdateTime(now);
        parentTask.setResultJson(writeJsonMap(result));
        taskRepository.save(parentTask);
        appendParentEvent(parentTask, accessEventType(response), buildParentAccessEvent(parentTask, request, response, entity));
    }

    private String accessEventType(RelayAccessDecisionResponse response) {
        if (response == null || isBlank(response.getDecision())) {
            return "ACCESS_UPDATED";
        }
        return switch (response.getDecision()) {
            case "ALLOW_WITH_DEPLOY" -> "ACCESS_WAITING_DEPLOY";
            case "WAIT_APPROVAL" -> "ACCESS_WAITING_APPROVAL";
            case "DENY" -> "ACCESS_DENIED";
            case "ALLOW" -> "ACCESS_GRANTED";
            default -> "ACCESS_UPDATED";
        };
    }

    private Map<String, Object> buildParentAccessEvent(AiTaskEntity parentTask, RelayAccessRequest request,
                                                       RelayAccessDecisionResponse response, AiRelayGrantEntity entity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parentTaskId", parentTask.getTaskId());
        payload.put("sessionId", parentTask.getSessionId());
        putIfNotBlank(payload, "requestId", request == null ? null : request.getRequestId());
        putIfNotBlank(payload, "grantId", response == null ? null : response.getGrantId());
        putIfNotBlank(payload, "decision", response == null ? null : response.getDecision());
        putIfNotBlank(payload, "auditId", response == null ? null : response.getAuditId());
        putIfNotBlank(payload, "targetNodeId", request == null ? null : request.getTargetNodeId());
        putIfNotBlank(payload, "sourceNodeId", request == null ? null : request.getSourceNodeId());
        putIfNotBlank(payload, "targetRelayEndpoint", response == null ? null : response.getTargetRelayEndpoint());
        putIfNotBlank(payload, "message", response == null ? null : response.getMessage());
        putIfNotBlank(payload, "errorCode", response == null ? null : response.getErrorCode());
        if (response != null && response.getAllowedCapabilities() != null && !response.getAllowedCapabilities().isEmpty()) {
            payload.put("allowedCapabilities", response.getAllowedCapabilities());
        }
        if (entity != null) {
            putIfNotBlank(payload, "grantStatus", entity.getStatus());
            putIfNotBlank(payload, "expiresAt", entity.getExpireAt());
        }
        return payload;
    }

    private void appendParentEvent(AiTaskEntity parentTask, String eventType, Map<String, Object> payload) {
        if (parentTask == null || taskEventService == null) {
            return;
        }
        taskEventService.appendEvent(parentTask.getTaskId(), parentTask.getSessionId(), eventType,
                nextSequence(parentTask.getTaskId()), payload == null ? Map.of() : payload);
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) || "TIMEOUT".equals(status);
    }

    private void validateAccessRequest(RelayAccessRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("RelayAccessRequest is required");
        }
        if (isBlank(request.getSessionId())) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (sessionService != null) {
            sessionService.validateSession(request.getSessionId());
        }
        if (isBlank(request.getTargetNodeId())) {
            throw new IllegalArgumentException("targetNodeId is required");
        }
    }

    private Optional<AiRelayGrantEntity> findByRequestId(String requestId) {
        if (isBlank(requestId)) {
            return Optional.empty();
        }
        return grantRepository.findByRequestId(requestId);
    }

    private RelayAccessDecisionResponse existingDecisionResponse(AiRelayGrantEntity entity, RelayNodeView node) {
        String auditId = activateGrantIfReady(entity, node);
        RelayAccessDecisionResponse response = toDecisionResponse(entity, node);
        if (!isBlank(auditId)) {
            response.setAuditId(auditId);
        }
        if ("ALLOW_WITH_DEPLOY".equals(response.getDecision())) {
            response.setMessage("Target node is not yet available; self replication or center deployment is required before grant issuance");
        }
        return response;
    }

    private String activateGrantIfReady(AiRelayGrantEntity entity, RelayNodeView node) {
        if (entity == null || !STATUS_WAITING_DEPLOY.equalsIgnoreCase(entity.getStatus()) || !isNodeReady(node, entity.getTargetNodeId())) {
            return null;
        }
        List<String> capabilities = readJsonList(entity.getAllowedCapabilitiesJson());
        if (parseLong(entity.getExpireAt()) <= System.currentTimeMillis()) {
            entity.setExpireAt(String.valueOf(System.currentTimeMillis() + resolveDefaultTtlMs()));
        }
        entity.setStatus(STATUS_ACTIVE);
        entity.setSignedToken(relayGrantTokenService.sign(entity.getGrantId(), entity.getSessionId(), entity.getSourceNodeId(), entity.getTargetNodeId(),
                capabilities, entity.getExpireAt()));
        entity.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        grantRepository.save(entity);
        return recordGrantAudit(entity.getSessionId(), null, entity.getSourceNodeId(), entity.getTargetNodeId(),
                AuditEventType.ACCESS_GRANTED, "ALLOW", buildGrantStateDetail(entity, capabilities), "SYSTEM", "WDSAVS_CC");
    }

    private RelayAccessDecisionResponse toDecisionResponse(AiRelayGrantEntity entity, RelayNodeView node) {
        RelayAccessDecisionResponse response = new RelayAccessDecisionResponse();
        response.setGrantId(entity.getGrantId());
        response.setAllowedCapabilities(readJsonList(entity.getAllowedCapabilitiesJson()));
        response.setExpiresAt(entity.getExpireAt());
        String decision = decisionFor(entity, node);
        response.setDecision(decision);
        if ("ALLOW".equals(decision)) {
            response.setSignedToken(entity.getSignedToken());
            response.setTargetRelayEndpoint(node == null ? null : node.getRelayEndpoint());
        }
        return response;
    }

    private String decisionFor(AiRelayGrantEntity entity, RelayNodeView node) {
        String status = entity.getStatus();
        if (STATUS_PENDING_APPROVAL.equalsIgnoreCase(status)) {
            return "WAIT_APPROVAL";
        }
        if (STATUS_REVOKED.equalsIgnoreCase(status)) {
            return "DENY";
        }
        if (STATUS_WAITING_DEPLOY.equalsIgnoreCase(status)) {
            return "ALLOW_WITH_DEPLOY";
        }
        return isNodeReady(node, entity.getTargetNodeId()) && !isBlank(entity.getSignedToken()) ? "ALLOW" : "ALLOW_WITH_DEPLOY";
    }

    private boolean isNodeReady(RelayNodeView node, String targetNodeId) {
        return node != null && heartbeatService.isNodeAvailable(targetNodeId);
    }

    private boolean isValidatableStatus(String status) {
        return STATUS_ACTIVE.equalsIgnoreCase(status) || STATUS_REVOKE_SCHEDULED.equalsIgnoreCase(status);
    }

    private boolean isRenewableStatus(String status) {
        return STATUS_ACTIVE.equalsIgnoreCase(status)
                || STATUS_PENDING_APPROVAL.equalsIgnoreCase(status)
                || STATUS_WAITING_DEPLOY.equalsIgnoreCase(status)
                || STATUS_REVOKE_SCHEDULED.equalsIgnoreCase(status);
    }

    private RelayNodeView resolveNode(String targetNodeId) {
        if (relayRegistryService == null || isBlank(targetNodeId)) {
            return null;
        }
        try {
            return relayRegistryService.getNode(targetNodeId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isNodeAllowed(String targetNodeId) {
        if (!resolveNodeWhitelistEnabled()) {
            return true;
        }
        List<String> currentAllowedNodeIds = resolveAllowedNodeIds();
        if (currentAllowedNodeIds.isEmpty()) {
            return false;
        }
        if (currentAllowedNodeIds.contains("*")) {
            return true;
        }
        return currentAllowedNodeIds.contains(targetNodeId);
    }

    private long resolveDefaultTtlMs() {
        return runtimeConfigService == null ? defaultTtlMs
                : runtimeConfigService.getLong("wdsavs.ai.relay.grant.default-ttl-ms", defaultTtlMs);
    }

    private boolean resolveAutoApproveEnabled() {
        return runtimeConfigService == null ? autoApproveEnabled
                : runtimeConfigService.getBoolean("wdsavs.ai.relay.grant.auto-approve-enabled", autoApproveEnabled);
    }

    private boolean resolveRenewEnabled() {
        return runtimeConfigService == null ? renewEnabled
                : runtimeConfigService.getBoolean("wdsavs.ai.relay.grant.renew-enabled", renewEnabled);
    }

    private boolean resolveRevokeImmediate() {
        return runtimeConfigService == null ? revokeImmediate
                : runtimeConfigService.getBoolean("wdsavs.ai.relay.grant.revoke-immediate", revokeImmediate);
    }

    private boolean resolveNodeWhitelistEnabled() {
        return runtimeConfigService == null ? nodeWhitelistEnabled
                : runtimeConfigService.getBoolean("wdsavs.ai.relay.node-whitelist-enabled", nodeWhitelistEnabled);
    }

    private List<String> resolveAllowedNodeIds() {
        return runtimeConfigService == null ? allowedNodeIds
                : runtimeConfigService.getList("wdsavs.ai.relay.allowed-node-ids", allowedNodeIds);
    }

    private List<String> normalizedCapabilities(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalizeCapability(value);
            if (!isBlank(normalized)) {
                unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    private String normalizeCapability(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase();
        return switch (normalized) {
            case "LOG_READ", "READ_LOG" -> "READ_LOG";
            case "FILE_READ", "CODE_READ", "READ_CODE" -> "READ_CODE";
            case "CONFIG_READ", "READ_CONFIG" -> "READ_CONFIG";
            case "RAT_SUMMARY", "RUNTIME_SUMMARY", "READ_RUNTIME_STATUS" -> "READ_RUNTIME_STATUS";
            case "A2A_MESSAGE_SEND" -> "A2A_MESSAGE_SEND";
            case "A2A_TASK_CREATE" -> "A2A_TASK_CREATE";
            case "A2A_TASK_GET" -> "A2A_TASK_GET";
            case "A2A_TASK_CANCEL" -> "A2A_TASK_CANCEL";
            case "DEPLOY_RELAY" -> "DEPLOY_RELAY";
            default -> normalized;
        };
    }

    private List<String> normalizeAllowedNodeIds(List<String> configured) {
        List<String> defaults = configured == null || configured.isEmpty() ? List.of("*") : configured;
        List<String> result = new ArrayList<>();
        for (String value : defaults) {
            if (!isBlank(value)) {
                result.add(value.trim());
            }
        }
        return result.isEmpty() ? List.of("*") : result;
    }

    private RelayGrantView toView(AiRelayGrantEntity entity, String auditId) {
        RelayGrantView view = new RelayGrantView();
        view.setAuditId(auditId);
        view.setGrantId(entity.getGrantId());
        view.setSessionId(entity.getSessionId());
        view.setRequestId(entity.getRequestId());
        view.setSourceNodeId(entity.getSourceNodeId());
        view.setTargetNodeId(entity.getTargetNodeId());
        view.setAllowedCapabilities(readJsonList(entity.getAllowedCapabilitiesJson()));
        view.setStatus(entity.getStatus());
        view.setExpiresAt(entity.getExpireAt());
        view.setRevokedAt(entity.getRevokedAt());
        view.setReason(entity.getReason());
        return view;
    }

    private String recordGrantAudit(String sessionId, String taskId, String sourceNodeId, String targetNodeId,
                                    AuditEventType eventType, String decision, Map<String, Object> detail,
                                    String operatorType, String operatorId) {
        if (auditService == null) {
            return null;
        }
        return auditService.record(sessionId, taskId, sourceNodeId, targetNodeId,
                eventType, decision, detail, operatorType, operatorId);
    }

    private Map<String, Object> buildAccessRequestDetail(RelayAccessRequest request,
                                                         AiRelayGrantEntity entity,
                                                         List<String> capabilities,
                                                         long ttlMs,
                                                         String errorCode) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", request.getRequestId());
        detail.put("grantId", entity == null ? null : entity.getGrantId());
        detail.put("sessionId", request.getSessionId());
        detail.put("sourceNodeId", request.getSourceNodeId());
        detail.put("targetNodeId", request.getTargetNodeId());
        detail.put("requiredCapabilities", capabilities == null ? List.of() : capabilities);
        detail.put("ttlMs", ttlMs);
        detail.put("reason", request.getReason());
        detail.put("status", entity == null ? null : entity.getStatus());
        detail.put("errorCode", errorCode);
        return detail;
    }

    private Map<String, Object> buildGrantStateDetail(AiRelayGrantEntity entity, List<String> capabilities) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("grantId", entity.getGrantId());
        detail.put("requestId", entity.getRequestId());
        detail.put("sessionId", entity.getSessionId());
        detail.put("sourceNodeId", entity.getSourceNodeId());
        detail.put("targetNodeId", entity.getTargetNodeId());
        detail.put("status", entity.getStatus());
        detail.put("expiresAt", entity.getExpireAt());
        detail.put("revokedAt", entity.getRevokedAt());
        detail.put("reason", entity.getReason());
        detail.put("allowedCapabilities", capabilities == null ? List.of() : capabilities);
        return detail;
    }

    private long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return -1L;
        }
    }

    private String writeJsonList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize grant capabilities", e);
        }
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Long nextSequence(String taskId) {
        if (taskEventService == null) {
            return 1L;
        }
        return (long) (taskEventService.listEvents(taskId).size() + 1);
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJsonMap(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize parent task payload", e);
        }
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value);
        }
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static long longConfig(String propertyKey, String envKey, long defaultValue) {
        String value = textConfig(propertyKey, envKey);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean booleanConfig(String propertyKey, String envKey, boolean defaultValue) {
        String value = textConfig(propertyKey, envKey);
        return value == null || value.trim().isEmpty() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    private static List<String> listConfig(String propertyKey, String envKey, List<String> defaultValue) {
        String value = textConfig(propertyKey, envKey);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            if (item != null && !item.trim().isEmpty()) {
                result.add(item.trim());
            }
        }
        return result.isEmpty() ? defaultValue : result;
    }

    private static String textConfig(String propertyKey, String envKey) {
        String property = System.getProperty(propertyKey);
        if (property != null) {
            return property;
        }
        return System.getenv(envKey);
    }
}




