package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.SshClusterIdentityPolicyEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.SshIdentityAuditEventEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.SshNodeAccessStateEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.SshNodeTrustEdgeEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.SshIdentityNodeStateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SshIdentityStateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SshIdentityTrustEdgeRequest;
import com.webank.wedatasphere.wdsavs.aiagent.repository.SshClusterIdentityPolicyRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.SshIdentityAuditEventRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.SshNodeAccessStateRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.SshNodeTrustEdgeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SshIdentityServiceImpl implements SshIdentityService {

    private final SshClusterIdentityPolicyRepository policyRepository;
    private final SshNodeAccessStateRepository nodeRepository;
    private final SshNodeTrustEdgeRepository edgeRepository;
    private final SshIdentityAuditEventRepository auditEventRepository;

    public SshIdentityServiceImpl(SshClusterIdentityPolicyRepository policyRepository,
                                  SshNodeAccessStateRepository nodeRepository,
                                  SshNodeTrustEdgeRepository edgeRepository,
                                  SshIdentityAuditEventRepository auditEventRepository) {
        this.policyRepository = policyRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.auditEventRepository = auditEventRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getState(String clusterId) {
        String normalizedClusterId = normalizeClusterId(clusterId);
        SshClusterIdentityPolicyEntity policy = policyRepository.findById(normalizedClusterId).orElse(null);
        List<SshNodeAccessStateEntity> nodes = nodeRepository.findByClusterIdOrderByNodeKeyAsc(normalizedClusterId);
        List<SshNodeTrustEdgeEntity> edges = edgeRepository.findByClusterIdOrderBySourceNodeKeyAscTargetNodeKeyAsc(normalizedClusterId);
        return stateView(policy, nodes, edges);
    }

    @Override
    @Transactional
    public Map<String, Object> saveState(SshIdentityStateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("SSH identity state is required");
        }
        String clusterId = normalizeClusterId(request.getClusterId());
        String accountMode = normalizeMode(request.getAccountMode());
        boolean creationAllowed = Boolean.TRUE.equals(request.getDedicatedAccountCreationAllowed());
        if ("DEDICATED_MANAGED".equals(accountMode) != creationAllowed) {
            throw new IllegalArgumentException("accountMode and dedicatedAccountCreationAllowed are inconsistent");
        }
        if (creationAllowed && isBlank(request.getDedicatedUsername())) {
            throw new IllegalArgumentException("dedicatedUsername is required when dedicated account creation is enabled");
        }

        Instant now = Instant.now();
        SshClusterIdentityPolicyEntity policy = policyRepository.findById(clusterId)
                .orElseGet(SshClusterIdentityPolicyEntity::new);
        long currentRevision = policy.getRevision() == null ? 0L : policy.getRevision();
        if (request.getExpectedRevision() != null && request.getExpectedRevision() != currentRevision) {
            throw new IllegalStateException("SSH identity policy revision conflict");
        }
        if (policy.getCreatedTime() == null) {
            policy.setCreatedTime(now.toString());
        }
        policy.setClusterId(clusterId);
        policy.setAccountMode(accountMode);
        policy.setDedicatedCreationAllowed(creationAllowed);
        policy.setDedicatedUsername(normalizeNullable(request.getDedicatedUsername()));
        policy.setClusterKeyMode(normalizeNullable(request.getClusterKeyMode()));
        policy.setClusterKeyFingerprint(normalizeNullable(request.getClusterKeyFingerprint()));
        policy.setSecretConfigRef(normalizeNullable(request.getSecretConfigRef()));
        policy.setCenterNodeId(normalizeNullable(request.getCenterNodeId()));
        policy.setRevision(currentRevision + 1L);
        policy.setUpdatedTime(now.toString());
        policy.setLastVerifiedTime(now.toString());

        List<SshIdentityNodeStateRequest> nodeRequests = request.getNodes() == null ? List.of() : request.getNodes();
        List<SshIdentityTrustEdgeRequest> edgeRequests = request.getTrustEdges() == null ? List.of() : request.getTrustEdges();
        if (Boolean.TRUE.equals(request.getReplaceSnapshot())) {
            edgeRepository.deleteByClusterId(clusterId);
            nodeRepository.deleteByClusterId(clusterId);
        }
        for (SshIdentityNodeStateRequest nodeRequest : nodeRequests) {
            if (nodeRequest == null || isBlank(nodeRequest.getNodeKey())) {
                throw new IllegalArgumentException("nodeKey is required");
            }
            SshNodeAccessStateEntity node = nodeRepository
                    .findByClusterIdAndNodeKey(clusterId, nodeRequest.getNodeKey().trim())
                    .orElseGet(SshNodeAccessStateEntity::new);
            node.setClusterId(clusterId);
            node.setNodeKey(nodeRequest.getNodeKey().trim());
            node.setBootstrapCredentialScope(normalizeNullable(nodeRequest.getBootstrapCredentialScope()));
            node.setBootstrapUsername(normalizeNullable(nodeRequest.getBootstrapUsername()));
            node.setRuntimeUsername(normalizeNullable(nodeRequest.getRuntimeUsername()));
            node.setOsType(normalizeNullable(nodeRequest.getOsType()));
            node.setAccountStatus(normalizeNullable(nodeRequest.getAccountStatus()));
            node.setKeyInstallStatus(normalizeNullable(nodeRequest.getKeyInstallStatus()));
            node.setCenterAccessStatus(normalizeNullable(nodeRequest.getCenterAccessStatus()));
            node.setMutualAccessStatus(normalizeNullable(nodeRequest.getMutualAccessStatus()));
            node.setPrivilegeSummaryJson(normalizeNullable(nodeRequest.getPrivilegeSummaryJson()));
            node.setLastErrorCode(normalizeNullable(nodeRequest.getLastErrorCode()));
            node.setLastErrorSummary(normalizeNullable(nodeRequest.getLastErrorSummary()));
            node.setLastVerifiedTime(normalizeNullable(nodeRequest.getLastVerifiedTime()));
            node.setUpdatedTime(now.toString());
            nodeRepository.save(node);
        }

        for (SshIdentityTrustEdgeRequest edgeRequest : edgeRequests) {
            if (edgeRequest == null || isBlank(edgeRequest.getSourceNodeKey()) || isBlank(edgeRequest.getTargetNodeKey())) {
                throw new IllegalArgumentException("sourceNodeKey and targetNodeKey are required");
            }
            SshNodeTrustEdgeEntity edge = edgeRepository
                    .findByClusterIdAndSourceNodeKeyAndTargetNodeKey(
                            clusterId, edgeRequest.getSourceNodeKey().trim(), edgeRequest.getTargetNodeKey().trim())
                    .orElseGet(SshNodeTrustEdgeEntity::new);
            edge.setClusterId(clusterId);
            edge.setSourceNodeKey(edgeRequest.getSourceNodeKey().trim());
            edge.setTargetNodeKey(edgeRequest.getTargetNodeKey().trim());
            edge.setRuntimeUsername(normalizeNullable(edgeRequest.getRuntimeUsername()));
            edge.setKeyFingerprint(normalizeNullable(edgeRequest.getKeyFingerprint()));
            edge.setStatus(normalizeNullable(edgeRequest.getStatus()));
            edge.setLatencyMs(edgeRequest.getLatencyMs());
            edge.setLastErrorCode(normalizeNullable(edgeRequest.getLastErrorCode()));
            edge.setLastErrorSummary(normalizeNullable(edgeRequest.getLastErrorSummary()));
            edge.setLastVerifiedTime(normalizeNullable(edgeRequest.getLastVerifiedTime()));
            edgeRepository.save(edge);
        }

        List<SshNodeAccessStateEntity> savedNodes = nodeRepository.findByClusterIdOrderByNodeKeyAsc(clusterId);
        List<SshNodeTrustEdgeEntity> savedEdges = edgeRepository.findByClusterIdOrderBySourceNodeKeyAscTargetNodeKeyAsc(clusterId);
        Capability capability = deriveCapability(accountMode, policy.getClusterKeyMode(), savedNodes, savedEdges);
        policy.setCenterToNodeStatus(capability.centerStatus());
        policy.setNodeToNodeStatus(capability.meshStatus());
        policy.setEffectiveCapability(capability.value());
        policy.setDedicatedAccountStatus(deriveDedicatedAccountStatus(
                accountMode, request.getDedicatedAccountStatus(), savedNodes, capability));
        policyRepository.save(policy);
        auditEventRepository.save(auditEvent(request, policy, savedNodes.size(), savedEdges.size(), now));
        return stateView(policy, savedNodes, savedEdges);
    }

    private Map<String, Object> stateView(SshClusterIdentityPolicyEntity policy,
                                          List<SshNodeAccessStateEntity> nodes,
                                          List<SshNodeTrustEdgeEntity> edges) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("configured", policy != null);
        if (policy == null) {
            view.put("accountMode", "UNKNOWN");
            view.put("effectiveCapability", "UNKNOWN");
            view.put("nodes", nodes);
            view.put("trustEdges", edges);
            return view;
        }
        view.put("clusterId", policy.getClusterId());
        view.put("accountMode", policy.getAccountMode());
        view.put("dedicatedAccountCreationAllowed", policy.getDedicatedCreationAllowed());
        view.put("dedicatedUsername", policy.getDedicatedUsername());
        view.put("dedicatedAccountStatus", policy.getDedicatedAccountStatus());
        view.put("clusterKeyMode", policy.getClusterKeyMode());
        view.put("clusterKeyFingerprint", policy.getClusterKeyFingerprint());
        view.put("centerNodeId", policy.getCenterNodeId());
        view.put("centerToNodeStatus", policy.getCenterToNodeStatus());
        view.put("nodeToNodeStatus", policy.getNodeToNodeStatus());
        view.put("effectiveCapability", policy.getEffectiveCapability());
        view.put("canCreateAccounts", Boolean.TRUE.equals(policy.getDedicatedCreationAllowed()));
        view.put("canDirectSelfReplicate", "FULL_MESH".equals(policy.getEffectiveCapability()));
        view.put("revision", policy.getRevision());
        view.put("lastVerifiedTime", policy.getLastVerifiedTime());
        view.put("nodes", nodes);
        view.put("trustEdges", edges);
        return view;
    }

    private Capability deriveCapability(String accountMode,
                                        String clusterKeyMode,
                                        List<SshNodeAccessStateEntity> nodes,
                                        List<SshNodeTrustEdgeEntity> edges) {
        boolean allCenterReady = !nodes.isEmpty() && nodes.stream()
                .allMatch(node -> "READY".equalsIgnoreCase(node.getCenterAccessStatus()));
        Set<String> nodeKeys = new HashSet<>();
        nodes.forEach(node -> nodeKeys.add(node.getNodeKey()));
        Set<String> readyEdges = new HashSet<>();
        for (SshNodeTrustEdgeEntity edge : edges) {
            if ("READY".equalsIgnoreCase(edge.getStatus())
                    && nodeKeys.contains(edge.getSourceNodeKey())
                    && nodeKeys.contains(edge.getTargetNodeKey())
                    && !edge.getSourceNodeKey().equals(edge.getTargetNodeKey())) {
                readyEdges.add(edge.getSourceNodeKey() + "\u0000" + edge.getTargetNodeKey());
            }
        }
        int requiredEdgeCount = nodes.size() * Math.max(0, nodes.size() - 1);
        boolean singleNodeDedicated = nodes.size() == 1 && "DEDICATED_MANAGED".equals(accountMode);
        boolean fullMeshReady = requiredEdgeCount > 0 && readyEdges.size() == requiredEdgeCount;
        boolean centerOnlyTopology = clusterKeyMode != null
                && clusterKeyMode.toUpperCase().startsWith("CENTER_ONLY");
        if (allCenterReady && !centerOnlyTopology && (fullMeshReady || singleNodeDedicated)) {
            return new Capability("READY", "READY", "FULL_MESH");
        }
        if (allCenterReady) {
            String meshStatus = (centerOnlyTopology || "EXISTING_ACCOUNT".equals(accountMode)) && edges.isEmpty()
                    ? "NOT_REQUIRED" : "PARTIAL";
            return new Capability("READY", meshStatus, "CENTER_ONLY");
        }
        boolean hasProgress = nodes.stream().anyMatch(node -> "READY".equalsIgnoreCase(node.getCenterAccessStatus()))
                || edges.stream().anyMatch(edge -> "READY".equalsIgnoreCase(edge.getStatus()));
        return new Capability(hasProgress ? "PARTIAL" : "FAILED", hasProgress ? "PARTIAL" : "UNKNOWN",
                hasProgress ? "DEGRADED" : "UNKNOWN");
    }

    private String deriveDedicatedAccountStatus(String accountMode,
                                                String requestedStatus,
                                                List<SshNodeAccessStateEntity> nodes,
                                                Capability capability) {
        if ("EXISTING_ACCOUNT".equals(accountMode)) {
            return "DISABLED";
        }
        if ("FULL_MESH".equals(capability.value())) {
            return "ACTIVE";
        }
        if ("CENTER_ONLY".equals(capability.value())
                && nodes.stream().allMatch(node -> "ACTIVE".equalsIgnoreCase(node.getAccountStatus()))) {
            return "ACTIVE";
        }
        if (nodes.isEmpty()) {
            return isBlank(requestedStatus) ? "PLANNED" : requestedStatus.trim().toUpperCase();
        }
        long failedNodes = nodes.stream()
                .filter(node -> "FAILED".equalsIgnoreCase(node.getAccountStatus()))
                .count();
        return failedNodes == nodes.size() ? "FAILED" : "PARTIAL";
    }

    private SshIdentityAuditEventEntity auditEvent(SshIdentityStateRequest request,
                                                    SshClusterIdentityPolicyEntity policy,
                                                    int nodeCount,
                                                    int trustEdgeCount,
                                                    Instant createdTime) {
        SshIdentityAuditEventEntity event = new SshIdentityAuditEventEntity();
        event.setEventId(UUID.randomUUID().toString());
        event.setClusterId(policy.getClusterId());
        event.setOperatorId(normalizeNullable(request.getOperatorId()));
        event.setAction(isBlank(request.getOperation())
                ? "STATE_SNAPSHOT_SAVED" : request.getOperation().trim().toUpperCase());
        event.setResult("SUCCESS");
        event.setAccountMode(policy.getAccountMode());
        event.setEffectiveCapability(policy.getEffectiveCapability());
        event.setKeyFingerprint(policy.getClusterKeyFingerprint());
        event.setTargetSummaryJson("{\"nodeCount\":" + nodeCount
                + ",\"trustEdgeCount\":" + trustEdgeCount + "}");
        event.setRevision(policy.getRevision());
        event.setCreatedTime(createdTime.toString());
        return event;
    }

    private String normalizeMode(String value) {
        String normalized = isBlank(value) ? "EXISTING_ACCOUNT" : value.trim().toUpperCase();
        if (!"DEDICATED_MANAGED".equals(normalized) && !"EXISTING_ACCOUNT".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported SSH account mode: " + value);
        }
        return normalized;
    }

    private String normalizeClusterId(String value) {
        return isBlank(value) ? "default" : value.trim();
    }

    private String normalizeNullable(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record Capability(String centerStatus, String meshStatus, String value) {
    }
}
