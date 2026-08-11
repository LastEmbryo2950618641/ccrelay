package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionParticipantEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionCollaborationView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionParticipantRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSessionCollaborationServiceImplTest {

    @Test
    void initializesOneCoordinatorFromAllAvailableInitialParticipants() {
        Fixture fixture = fixture(1);

        AiSessionCollaborationView state = fixture.service.initialize("session-1", "DISCUSSION",
                List.of("node-a:18192", "node-b:18192", "node-c:18192"),
                Map.of("roundBudget", 4));

        assertEquals("DISCUSSION", state.getCollaborationMode());
        assertEquals("node-b:18192", state.getCoordinatorNodeId());
        assertEquals(1L, state.getCoordinatorEpoch());
        assertEquals(List.of("node-a:18192", "node-b:18192", "node-c:18192"), state.getParticipantNodeIds());
        ArgumentCaptor<AiSessionContextAppendRequest> event = ArgumentCaptor.forClass(AiSessionContextAppendRequest.class);
        verify(fixture.contextService).append(eq("session-1"), event.capture());
        assertEquals("SESSION_CONTROL", event.getValue().getContentType());
        assertTrue(event.getValue().getContent().contains("SESSION_COLLABORATION_INITIALIZED"));
        assertTrue(event.getValue().getContent().contains("node-b:18192"));
    }

    @Test
    void replacesUnavailableCoordinatorAndIncrementsEpoch() {
        Fixture fixture = fixture(0);
        fixture.session.setCollaborationMode("DISCUSSION");
        fixture.session.setCoordinatorNodeId("node-a:18192");
        fixture.session.setCoordinatorEpoch(2L);
        fixture.nodes.get("node-a:18192").setStatus("UNAVAILABLE");
        fixture.participants.add(participant("session-1", "node-a:18192"));
        fixture.participants.add(participant("session-1", "node-b:18192"));

        AiSessionCollaborationView state = fixture.service.ensureCoordinator("session-1");

        assertEquals("node-b:18192", state.getCoordinatorNodeId());
        assertEquals(3L, state.getCoordinatorEpoch());
        ArgumentCaptor<AiSessionContextAppendRequest> event = ArgumentCaptor.forClass(AiSessionContextAppendRequest.class);
        verify(fixture.contextService).append(eq("session-1"), event.capture());
        assertTrue(event.getValue().getContent().contains("COORDINATOR_CHANGED"));
        assertTrue(event.getValue().getContent().contains("node-a:18192"));
    }

    private Fixture fixture(int coordinatorIndex) {
        AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
        AiSessionParticipantRepository participantRepository = mock(AiSessionParticipantRepository.class);
        AiRelayRegistryService relayRegistryService = mock(AiRelayRegistryService.class);
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextService contextService = mock(AiSessionContextService.class);
        AiSessionEntity session = new AiSessionEntity();
        session.setSessionId("session-1");
        session.setStatus("OPEN");
        List<AiSessionParticipantEntity> participants = new ArrayList<>();
        Map<String, RelayNodeView> nodes = new LinkedHashMap<>();
        for (String nodeId : List.of("node-a:18192", "node-b:18192", "node-c:18192")) {
            nodes.put(nodeId, node(nodeId));
        }
        when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(participantRepository.findBySessionIdAndNodeId(any(), any())).thenAnswer(invocation -> participants.stream()
                .filter(item -> item.getSessionId().equals(invocation.getArgument(0))
                        && item.getNodeId().equals(invocation.getArgument(1)))
                .findFirst());
        when(participantRepository.save(any())).thenAnswer(invocation -> {
            AiSessionParticipantEntity entity = invocation.getArgument(0);
            if (!participants.contains(entity)) {
                entity.setId((long) participants.size() + 1L);
                participants.add(entity);
            }
            return entity;
        });
        when(participantRepository.findBySessionIdOrderByJoinTimeAsc("session-1")).thenAnswer(invocation -> new ArrayList<>(participants));
        when(relayRegistryService.getNode(any())).thenAnswer(invocation -> nodes.get(invocation.getArgument(0)));
        AiSessionCollaborationServiceImpl service = new AiSessionCollaborationServiceImpl(
                sessionRepository, participantRepository, relayRegistryService, sessionService, contextService,
                new ObjectMapper(), bound -> Math.min(coordinatorIndex, bound - 1));
        return new Fixture(service, session, participants, nodes, contextService);
    }

    private RelayNodeView node(String nodeId) {
        RelayNodeView node = new RelayNodeView();
        node.setNodeId(nodeId);
        node.setStatus("AVAILABLE");
        node.setCapabilities(List.of("A2A_MESSAGE_SEND", "A2A_TASK_CREATE"));
        return node;
    }

    private AiSessionParticipantEntity participant(String sessionId, String nodeId) {
        AiSessionParticipantEntity entity = new AiSessionParticipantEntity();
        entity.setId((long) nodeId.hashCode());
        entity.setSessionId(sessionId);
        entity.setNodeId(nodeId);
        entity.setStatus("ACTIVE");
        entity.setJoinTime("1");
        entity.setUpdateTime("1");
        return entity;
    }

    private record Fixture(AiSessionCollaborationServiceImpl service,
                           AiSessionEntity session,
                           List<AiSessionParticipantEntity> participants,
                           Map<String, RelayNodeView> nodes,
                           AiSessionContextService contextService) {
    }
}
