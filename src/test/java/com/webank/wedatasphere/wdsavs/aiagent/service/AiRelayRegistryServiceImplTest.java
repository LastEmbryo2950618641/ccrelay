package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayNodeEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterResponse;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRelayRegistryServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerUsesHostAndPortAsConfirmedNodeId() throws Exception {
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AtomicReference<AiRelayNodeEntity> savedNode = new AtomicReference<>();

        when(relayNodeRepository.findByNodeId("relay-host:19091")).thenAnswer(invocation -> {
            AiRelayNodeEntity entity = savedNode.get();
            return entity == null ? Optional.empty() : Optional.of(entity);
        });
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> {
            AiRelayNodeEntity entity = invocation.getArgument(0);
            savedNode.set(entity);
            return entity;
        });

        AiRelayRegistryServiceImpl service = new AiRelayRegistryServiceImpl(relayNodeRepository);
        RelayRegisterRequest request = new RelayRegisterRequest();
        request.setNodeId("custom-node-id");
        request.setHost("relay-host");
        request.setPort(19091);
        request.setRelayEndpoint("http://relay-host:19091");
        request.setVersion("1.0.0");
        request.setProtocolVersion("2026-07");
        request.setEnvironmentSummary(Map.of(
                "path", "/usr/bin:/bin",
                "javaBundled", true,
                "availableCommands", Map.of("rg", true, "python3", false)
        ));

        RelayRegisterResponse response = service.register(request);

        assertEquals("relay-host:19091", response.getNodeId());
        assertEquals("REGISTERING", response.getStatus());
        assertEquals(Boolean.TRUE, response.getAccepted());
        assertNotNull(response.getRegisterTime());
        assertEquals("relay-host:19091", savedNode.get().getNodeId());
        assertEquals("REGISTERING", savedNode.get().getStatus());

        Map<String, Object> persistedSummary = objectMapper.readValue(savedNode.get().getEnvironmentSummaryJson(), new TypeReference<Map<String, Object>>() {});
        assertEquals("/usr/bin:/bin", persistedSummary.get("path"));
        assertEquals(true, persistedSummary.get("javaBundled"));

        RelayNodeView nodeView = service.getNode("relay-host:19091");
        assertEquals("/usr/bin:/bin", nodeView.getEnvironmentSummary().get("path"));
        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) nodeView.getEnvironmentSummary().get("availableCommands");
        assertEquals(true, commands.get("rg"));
    }

    @Test
    void registerRejectsMissingNodeIdentity() {
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiRelayRegistryServiceImpl service = new AiRelayRegistryServiceImpl(relayNodeRepository);
        RelayRegisterRequest request = new RelayRegisterRequest();
        request.setHost(" ");
        request.setNodeId(null);
        request.setPort(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.register(request));
        assertEquals("nodeId or host+port is required", error.getMessage());
    }
}
