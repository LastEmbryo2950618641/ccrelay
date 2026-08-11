package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.SshClusterIdentityPolicyEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.SshNodeAccessStateEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.SshNodeTrustEdgeEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.SshIdentityNodeStateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SshIdentityStateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SshIdentityTrustEdgeRequest;
import com.webank.wedatasphere.wdsavs.aiagent.repository.SshClusterIdentityPolicyRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.SshIdentityAuditEventRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.SshNodeAccessStateRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.SshNodeTrustEdgeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SshIdentityServiceImplTest {

    @Test
    void savesDedicatedPolicyAndDerivesFullMeshCapability() {
        SshClusterIdentityPolicyRepository policyRepository = mock(SshClusterIdentityPolicyRepository.class);
        SshNodeAccessStateRepository nodeRepository = mock(SshNodeAccessStateRepository.class);
        SshNodeTrustEdgeRepository edgeRepository = mock(SshNodeTrustEdgeRepository.class);
        SshIdentityAuditEventRepository auditEventRepository = mock(SshIdentityAuditEventRepository.class);
        when(policyRepository.findById("default")).thenReturn(Optional.empty());
        when(policyRepository.save(any(SshClusterIdentityPolicyEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByClusterIdAndNodeKey(any(), any())).thenReturn(Optional.empty());
        when(nodeRepository.save(any(SshNodeAccessStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(edgeRepository.findByClusterIdAndSourceNodeKeyAndTargetNodeKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(edgeRepository.save(any(SshNodeTrustEdgeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SshNodeAccessStateEntity node = new SshNodeAccessStateEntity();
        node.setClusterId("default");
        node.setNodeKey("node-a:22");
        node.setCenterAccessStatus("READY");
        SshNodeAccessStateEntity secondNode = new SshNodeAccessStateEntity();
        secondNode.setClusterId("default");
        secondNode.setNodeKey("node-b:22");
        secondNode.setCenterAccessStatus("READY");
        SshNodeTrustEdgeEntity edge = readyEdge("node-a:22", "node-b:22");
        SshNodeTrustEdgeEntity reverseEdge = readyEdge("node-b:22", "node-a:22");
        when(nodeRepository.findByClusterIdOrderByNodeKeyAsc("default")).thenReturn(List.of(node, secondNode));
        when(edgeRepository.findByClusterIdOrderBySourceNodeKeyAscTargetNodeKeyAsc("default"))
                .thenReturn(List.of(edge, reverseEdge));

        SshIdentityStateRequest request = new SshIdentityStateRequest();
        request.setAccountMode("DEDICATED_MANAGED");
        request.setDedicatedAccountCreationAllowed(true);
        request.setDedicatedUsername("ccrelay");
        request.setCenterNodeId("node-a:22");
        SshIdentityNodeStateRequest nodeRequest = new SshIdentityNodeStateRequest();
        nodeRequest.setNodeKey("node-a:22");
        nodeRequest.setCenterAccessStatus("READY");
        SshIdentityNodeStateRequest secondNodeRequest = new SshIdentityNodeStateRequest();
        secondNodeRequest.setNodeKey("node-b:22");
        secondNodeRequest.setCenterAccessStatus("READY");
        request.setNodes(List.of(nodeRequest, secondNodeRequest));
        SshIdentityTrustEdgeRequest edgeRequest = new SshIdentityTrustEdgeRequest();
        edgeRequest.setSourceNodeKey("node-a:22");
        edgeRequest.setTargetNodeKey("node-b:22");
        edgeRequest.setStatus("READY");
        SshIdentityTrustEdgeRequest reverseEdgeRequest = new SshIdentityTrustEdgeRequest();
        reverseEdgeRequest.setSourceNodeKey("node-b:22");
        reverseEdgeRequest.setTargetNodeKey("node-a:22");
        reverseEdgeRequest.setStatus("READY");
        request.setTrustEdges(List.of(edgeRequest, reverseEdgeRequest));

        Map<String, Object> result = new SshIdentityServiceImpl(
                policyRepository, nodeRepository, edgeRepository, auditEventRepository)
                .saveState(request);

        assertEquals("DEDICATED_MANAGED", result.get("accountMode"));
        assertEquals("FULL_MESH", result.get("effectiveCapability"));
        assertEquals("ACTIVE", result.get("dedicatedAccountStatus"));
        assertTrue((Boolean) result.get("canCreateAccounts"));
        assertTrue((Boolean) result.get("canDirectSelfReplicate"));
        verify(auditEventRepository).save(any());
    }

    @Test
    void existingAccountModeDerivesCenterOnlyWithoutTrustEdges() {
        SshClusterIdentityPolicyRepository policyRepository = mock(SshClusterIdentityPolicyRepository.class);
        SshNodeAccessStateRepository nodeRepository = mock(SshNodeAccessStateRepository.class);
        SshNodeTrustEdgeRepository edgeRepository = mock(SshNodeTrustEdgeRepository.class);
        SshIdentityAuditEventRepository auditEventRepository = mock(SshIdentityAuditEventRepository.class);
        when(policyRepository.findById("default")).thenReturn(Optional.empty());
        when(policyRepository.save(any(SshClusterIdentityPolicyEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByClusterIdAndNodeKey(any(), any())).thenReturn(Optional.empty());
        when(nodeRepository.save(any(SshNodeAccessStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(edgeRepository.findByClusterIdAndSourceNodeKeyAndTargetNodeKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(edgeRepository.save(any(SshNodeTrustEdgeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SshNodeAccessStateEntity node = new SshNodeAccessStateEntity();
        node.setClusterId("default");
        node.setNodeKey("node-a:22");
        node.setCenterAccessStatus("READY");
        when(nodeRepository.findByClusterIdOrderByNodeKeyAsc("default")).thenReturn(List.of(node));
        when(edgeRepository.findByClusterIdOrderBySourceNodeKeyAscTargetNodeKeyAsc("default")).thenReturn(List.of());

        SshIdentityStateRequest request = new SshIdentityStateRequest();
        request.setAccountMode("EXISTING_ACCOUNT");
        request.setDedicatedAccountCreationAllowed(false);
        request.setCenterNodeId("node-a:22");
        SshIdentityNodeStateRequest nodeRequest = new SshIdentityNodeStateRequest();
        nodeRequest.setNodeKey("node-a:22");
        nodeRequest.setCenterAccessStatus("READY");
        request.setNodes(List.of(nodeRequest));

        Map<String, Object> result = new SshIdentityServiceImpl(
                policyRepository, nodeRepository, edgeRepository, auditEventRepository)
                .saveState(request);

        assertEquals("EXISTING_ACCOUNT", result.get("accountMode"));
        assertEquals("CENTER_ONLY", result.get("effectiveCapability"));
        assertEquals("DISABLED", result.get("dedicatedAccountStatus"));
        assertTrue(!(Boolean) result.get("canCreateAccounts"));
        assertTrue(!(Boolean) result.get("canDirectSelfReplicate"));
    }

    @Test
    void incompleteTrustGraphCannotBecomeFullMesh() {
        SshClusterIdentityPolicyRepository policyRepository = mock(SshClusterIdentityPolicyRepository.class);
        SshNodeAccessStateRepository nodeRepository = mock(SshNodeAccessStateRepository.class);
        SshNodeTrustEdgeRepository edgeRepository = mock(SshNodeTrustEdgeRepository.class);
        SshIdentityAuditEventRepository auditEventRepository = mock(SshIdentityAuditEventRepository.class);
        when(policyRepository.findById("default")).thenReturn(Optional.empty());
        when(policyRepository.save(any(SshClusterIdentityPolicyEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRepository.findByClusterIdAndNodeKey(any(), any())).thenReturn(Optional.empty());
        when(nodeRepository.save(any(SshNodeAccessStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(edgeRepository.findByClusterIdAndSourceNodeKeyAndTargetNodeKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(edgeRepository.save(any(SshNodeTrustEdgeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SshNodeAccessStateEntity firstNode = readyNode("node-a:22");
        SshNodeAccessStateEntity secondNode = readyNode("node-b:22");
        when(nodeRepository.findByClusterIdOrderByNodeKeyAsc("default"))
                .thenReturn(List.of(firstNode, secondNode));
        when(edgeRepository.findByClusterIdOrderBySourceNodeKeyAscTargetNodeKeyAsc("default"))
                .thenReturn(List.of(readyEdge("node-a:22", "node-b:22")));

        SshIdentityStateRequest request = new SshIdentityStateRequest();
        request.setAccountMode("DEDICATED_MANAGED");
        request.setDedicatedAccountCreationAllowed(true);
        request.setDedicatedUsername("ccrelay");
        request.setNodes(List.of(nodeRequest("node-a:22"), nodeRequest("node-b:22")));
        SshIdentityTrustEdgeRequest edgeRequest = new SshIdentityTrustEdgeRequest();
        edgeRequest.setSourceNodeKey("node-a:22");
        edgeRequest.setTargetNodeKey("node-b:22");
        edgeRequest.setStatus("READY");
        request.setTrustEdges(List.of(edgeRequest));

        Map<String, Object> result = new SshIdentityServiceImpl(
                policyRepository, nodeRepository, edgeRepository, auditEventRepository)
                .saveState(request);

        assertEquals("CENTER_ONLY", result.get("effectiveCapability"));
        assertEquals("PARTIAL", result.get("dedicatedAccountStatus"));
        assertTrue(!(Boolean) result.get("canDirectSelfReplicate"));
    }

    @Test
    void rejectsStalePolicyRevision() {
        SshClusterIdentityPolicyRepository policyRepository = mock(SshClusterIdentityPolicyRepository.class);
        SshNodeAccessStateRepository nodeRepository = mock(SshNodeAccessStateRepository.class);
        SshNodeTrustEdgeRepository edgeRepository = mock(SshNodeTrustEdgeRepository.class);
        SshIdentityAuditEventRepository auditEventRepository = mock(SshIdentityAuditEventRepository.class);
        SshClusterIdentityPolicyEntity policy = new SshClusterIdentityPolicyEntity();
        policy.setClusterId("default");
        policy.setRevision(3L);
        when(policyRepository.findById("default")).thenReturn(Optional.of(policy));

        SshIdentityStateRequest request = new SshIdentityStateRequest();
        request.setAccountMode("EXISTING_ACCOUNT");
        request.setDedicatedAccountCreationAllowed(false);
        request.setExpectedRevision(2L);

        assertThrows(IllegalStateException.class,
                () -> new SshIdentityServiceImpl(
                        policyRepository, nodeRepository, edgeRepository, auditEventRepository)
                        .saveState(request));
    }

    private static SshNodeAccessStateEntity readyNode(String nodeKey) {
        SshNodeAccessStateEntity node = new SshNodeAccessStateEntity();
        node.setClusterId("default");
        node.setNodeKey(nodeKey);
        node.setCenterAccessStatus("READY");
        return node;
    }

    private static SshNodeTrustEdgeEntity readyEdge(String sourceNodeKey, String targetNodeKey) {
        SshNodeTrustEdgeEntity edge = new SshNodeTrustEdgeEntity();
        edge.setClusterId("default");
        edge.setSourceNodeKey(sourceNodeKey);
        edge.setTargetNodeKey(targetNodeKey);
        edge.setStatus("READY");
        return edge;
    }

    private static SshIdentityNodeStateRequest nodeRequest(String nodeKey) {
        SshIdentityNodeStateRequest request = new SshIdentityNodeStateRequest();
        request.setNodeKey(nodeKey);
        request.setCenterAccessStatus("READY");
        return request;
    }
}
