package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayHeartbeatEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayNodeEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatScanResponse;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayHeartbeatRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRelayHeartbeatServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void scanNodeAvailabilityMarksDegradedAndUnavailableByHeartbeatAge() {
        AiRelayHeartbeatRepository heartbeatRepository = mock(AiRelayHeartbeatRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        long now = System.currentTimeMillis();

        AiRelayNodeEntity availableNode = node("available-node:19091", "AVAILABLE", now - 5_000L);
        AiRelayNodeEntity degradedNode = node("degraded-node:19091", "AVAILABLE", now - 31_000L);
        AiRelayNodeEntity unavailableNode = node("unavailable-node:19091", "AVAILABLE", now - 95_000L);

        when(relayNodeRepository.findAll()).thenReturn(List.of(availableNode, degradedNode, unavailableNode));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiRelayHeartbeatServiceImpl service = new AiRelayHeartbeatServiceImpl(
                heartbeatRepository,
                relayNodeRepository,
                30_000L,
                3);

        RelayHeartbeatScanResponse response = service.scanNodeAvailability();

        assertEquals(3, response.getScannedCount());
        assertEquals(1, response.getAvailableCount());
        assertEquals(1, response.getDegradedCount());
        assertEquals(1, response.getUnavailableCount());
        assertEquals("AVAILABLE", response.getNodeStatuses().get("available-node:19091"));
        assertEquals("DEGRADED", response.getNodeStatuses().get("degraded-node:19091"));
        assertEquals("UNAVAILABLE", response.getNodeStatuses().get("unavailable-node:19091"));
        assertEquals("DEGRADED", degradedNode.getStatus());
        assertEquals("UNAVAILABLE", unavailableNode.getStatus());
    }

    @Test
    void heartbeatMergesEnvironmentSummaryWithoutDroppingExistingFields() throws Exception {
        AiRelayHeartbeatRepository heartbeatRepository = mock(AiRelayHeartbeatRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);

        AiRelayNodeEntity node = new AiRelayNodeEntity();
        node.setNodeId("node-env:19091");
        node.setStatus("AVAILABLE");
        node.setEnvironmentSummaryJson(objectMapper.writeValueAsString(Map.of(
                "shell", "/bin/bash",
                "availableCommands", Map.of("python3", true, "git", true)
        )));

        when(relayNodeRepository.findByNodeId("node-env:19091")).thenReturn(java.util.Optional.of(node));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(heartbeatRepository.save(any(AiRelayHeartbeatEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiRelayHeartbeatServiceImpl service = new AiRelayHeartbeatServiceImpl(
                heartbeatRepository,
                relayNodeRepository,
                30_000L,
                3);

        RelayHeartbeatRequest request = new RelayHeartbeatRequest();
        request.setNodeId("node-env:19091");
        request.setStatus("AVAILABLE");
        request.setActiveSessions(2);
        request.setEnvironmentSummary(Map.of(
                "path", "/usr/local/bin:/usr/bin:/bin",
                "availableCommands", Map.of("rg", true, "python3", false),
                "probedAt", "1785326400000"
        ));

        RelayHeartbeatResponse response = service.heartbeat(request);

        assertEquals("AVAILABLE", response.getNodeStatus());
        Map<String, Object> merged = objectMapper.readValue(node.getEnvironmentSummaryJson(), new TypeReference<Map<String, Object>>() {});
        assertEquals("/bin/bash", merged.get("shell"));
        assertEquals("/usr/local/bin:/usr/bin:/bin", merged.get("path"));
        assertEquals("1785326400000", merged.get("probedAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) merged.get("availableCommands");
        assertEquals(true, commands.get("git"));
        assertEquals(true, commands.get("rg"));
        assertEquals(false, commands.get("python3"));
        assertTrue(node.getLastHeartbeatTime() != null && !node.getLastHeartbeatTime().isBlank());
    }

    private AiRelayNodeEntity node(String nodeId, String status, long lastHeartbeatTime) {
        AiRelayNodeEntity entity = new AiRelayNodeEntity();
        entity.setNodeId(nodeId);
        entity.setStatus(status);
        entity.setLastHeartbeatTime(String.valueOf(lastHeartbeatTime));
        entity.setUpdateTime(String.valueOf(lastHeartbeatTime));
        return entity;
    }


    @Test
    void heartbeatAvailableTriggersDeployAutoComplete() {
        AiRelayHeartbeatRepository heartbeatRepository = mock(AiRelayHeartbeatRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiRelayDeployService relayDeployService = mock(AiRelayDeployService.class);

        AiRelayNodeEntity node = new AiRelayNodeEntity();
        node.setNodeId("node-ready:19091");
        node.setStatus("REGISTERING");

        when(relayNodeRepository.findByNodeId("node-ready:19091")).thenReturn(java.util.Optional.of(node));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(heartbeatRepository.save(any(AiRelayHeartbeatEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayDeployService.autoCompleteReadyDeployments("node-ready:19091")).thenReturn(true);

        AiRelayHeartbeatServiceImpl service = new AiRelayHeartbeatServiceImpl(
                heartbeatRepository,
                relayNodeRepository,
                null,
                relayDeployService,
                30_000L,
                3);

        RelayHeartbeatRequest request = new RelayHeartbeatRequest();
        request.setNodeId("node-ready:19091");
        request.setStatus("AVAILABLE");

        RelayHeartbeatResponse response = service.heartbeat(request);

        assertEquals("AVAILABLE", response.getNodeStatus());
        verify(relayDeployService).autoCompleteReadyDeployments("node-ready:19091");
    }

}
