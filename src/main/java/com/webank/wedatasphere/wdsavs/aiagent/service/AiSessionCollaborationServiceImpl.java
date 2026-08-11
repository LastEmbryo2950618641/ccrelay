package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionParticipantEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiCollaborationMode;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionCollaborationView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionParticipantRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

@Service
public class AiSessionCollaborationServiceImpl implements AiSessionCollaborationService {

    private static final String PARTICIPANT_ACTIVE = "ACTIVE";
    private static final String ROLE_COORDINATOR = "COORDINATOR";
    private static final String ROLE_PARTICIPANT = "PARTICIPANT";

    private final AiSessionRepository sessionRepository;
    private final AiSessionParticipantRepository participantRepository;
    private final AiRelayRegistryService relayRegistryService;
    private final AiSessionService sessionService;
    private final AiSessionContextService contextService;
    private final ObjectMapper objectMapper;
    private final IntUnaryOperator coordinatorIndexSelector;

    @Autowired
    public AiSessionCollaborationServiceImpl(AiSessionRepository sessionRepository,
                                             AiSessionParticipantRepository participantRepository,
                                             AiRelayRegistryService relayRegistryService,
                                             AiSessionService sessionService,
                                             AiSessionContextService contextService,
                                             ObjectMapper objectMapper) {
        this(sessionRepository, participantRepository, relayRegistryService, sessionService, contextService,
                objectMapper, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    AiSessionCollaborationServiceImpl(AiSessionRepository sessionRepository,
                                      AiSessionParticipantRepository participantRepository,
                                      AiRelayRegistryService relayRegistryService,
                                      AiSessionService sessionService,
                                      AiSessionContextService contextService,
                                      ObjectMapper objectMapper,
                                      IntUnaryOperator coordinatorIndexSelector) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.relayRegistryService = relayRegistryService;
        this.sessionService = sessionService;
        this.contextService = contextService;
        this.objectMapper = objectMapper;
        this.coordinatorIndexSelector = coordinatorIndexSelector;
    }

    @Override
    public synchronized AiSessionCollaborationView initialize(String sessionId, String collaborationMode,
                                                               List<String> participantNodeIds,
                                                               Map<String, Object> collaborationPolicy) {
        sessionService.validateSession(sessionId);
        AiSessionEntity session = session(sessionId);
        List<String> participants = availableParticipants(participantNodeIds);
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("At least one available participantNodeId is required");
        }
        boolean newlyInitialized = isBlank(session.getCoordinatorNodeId());
        boolean participantsAdded = addParticipants(sessionId, participants);
        if (newlyInitialized) {
            AiCollaborationMode fallback = participants.size() == 1
                    ? AiCollaborationMode.DIRECT : AiCollaborationMode.INDEPENDENT_FANOUT;
            session.setCollaborationMode(AiCollaborationMode.from(collaborationMode, fallback).name());
            session.setCoordinatorNodeId(selectCoordinator(participants));
            session.setCoordinatorEpoch(1L);
            session.setCollaborationPolicyJson(writeJson(collaborationPolicy));
        } else if (collaborationPolicy != null && !collaborationPolicy.isEmpty()) {
            session.setCollaborationPolicyJson(writeJson(collaborationPolicy));
        }
        session.setUpdateTime(now());
        sessionRepository.save(session);
        AiSessionCollaborationView state = toView(session);
        if (newlyInitialized) {
            appendControlEvent(state, "SESSION_COLLABORATION_INITIALIZED", null);
        } else if (participantsAdded) {
            appendControlEvent(state, "SESSION_PARTICIPANTS_CHANGED", null);
        }
        return state;
    }

    @Override
    public AiSessionCollaborationView getState(String sessionId) {
        sessionService.validateSession(sessionId);
        return toView(session(sessionId));
    }

    @Override
    public synchronized AiSessionCollaborationView ensureCoordinator(String sessionId) {
        sessionService.validateSession(sessionId);
        AiSessionEntity session = session(sessionId);
        String current = session.getCoordinatorNodeId();
        if (!isBlank(current) && available(current)) {
            return toView(session);
        }
        List<String> candidates = activeParticipants(sessionId).stream().filter(this::available).toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No available participant can become coordinator for session: " + sessionId);
        }
        String previous = current;
        session.setCoordinatorNodeId(selectCoordinator(candidates));
        session.setCoordinatorEpoch(session.getCoordinatorEpoch() == null ? 1L : session.getCoordinatorEpoch() + 1L);
        session.setUpdateTime(now());
        sessionRepository.save(session);
        AiSessionCollaborationView state = toView(session);
        appendControlEvent(state, "COORDINATOR_CHANGED", previous);
        return state;
    }

    @Override
    public void enrichTaskParams(Map<String, Object> params) {
        if (params == null || isBlank(stringValue(params.get("sessionId")))) {
            return;
        }
        AiSessionCollaborationView state;
        try {
            String sessionId = stringValue(params.get("sessionId"));
            String targetNodeId = stringValue(params.get("targetNodeId"));
            if (!isBlank(targetNodeId)) {
                initialize(sessionId, null, List.of(targetNodeId), Map.of());
            }
            state = ensureCoordinator(sessionId);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        Map<String, Object> metadata = mapValue(params.get("metadata"));
        metadata.put("collaborationMode", state.getCollaborationMode());
        metadata.put("coordinatorNodeId", state.getCoordinatorNodeId());
        metadata.put("coordinatorEpoch", state.getCoordinatorEpoch());
        metadata.put("participantNodeIds", state.getParticipantNodeIds());
        metadata.put("collaborationPolicy", state.getCollaborationPolicy());
        String targetNodeId = stringValue(params.get("targetNodeId"));
        metadata.put("agentRole", state.getCoordinatorNodeId() != null
                && state.getCoordinatorNodeId().equals(targetNodeId) ? ROLE_COORDINATOR : ROLE_PARTICIPANT);
        params.put("metadata", metadata);
        params.put("agentRole", metadata.get("agentRole"));
    }

    private boolean addParticipants(String sessionId, List<String> nodeIds) {
        String currentTime = now();
        boolean added = false;
        for (String nodeId : nodeIds) {
            AiSessionParticipantEntity participant = participantRepository.findBySessionIdAndNodeId(sessionId, nodeId)
                    .orElseGet(AiSessionParticipantEntity::new);
            if (participant.getId() == null) {
                added = true;
                participant.setSessionId(sessionId);
                participant.setNodeId(nodeId);
                participant.setJoinTime(currentTime);
            }
            participant.setStatus(PARTICIPANT_ACTIVE);
            participant.setUpdateTime(currentTime);
            participantRepository.save(participant);
        }
        return added;
    }

    private List<String> availableParticipants(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (!isBlank(value) && available(value.trim())) {
                    result.add(value.trim());
                }
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> activeParticipants(String sessionId) {
        return participantRepository.findBySessionIdOrderByJoinTimeAsc(sessionId).stream()
                .filter(item -> PARTICIPANT_ACTIVE.equalsIgnoreCase(item.getStatus()))
                .map(AiSessionParticipantEntity::getNodeId)
                .toList();
    }

    private boolean available(String nodeId) {
        try {
            RelayNodeView node = relayRegistryService.getNode(nodeId);
            return node != null && "AVAILABLE".equalsIgnoreCase(node.getStatus())
                    && node.getCapabilities() != null
                    && (node.getCapabilities().contains("A2A_MESSAGE_SEND")
                    || node.getCapabilities().contains("A2A_TASK_CREATE"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String selectCoordinator(List<String> candidates) {
        int index = coordinatorIndexSelector.applyAsInt(candidates.size());
        if (index < 0 || index >= candidates.size()) {
            throw new IllegalStateException("Coordinator selector returned invalid index: " + index);
        }
        return candidates.get(index);
    }

    private AiSessionCollaborationView toView(AiSessionEntity session) {
        AiSessionCollaborationView view = new AiSessionCollaborationView();
        view.setSessionId(session.getSessionId());
        view.setCollaborationMode(session.getCollaborationMode());
        view.setCoordinatorNodeId(session.getCoordinatorNodeId());
        view.setCoordinatorEpoch(session.getCoordinatorEpoch());
        view.setParticipantNodeIds(activeParticipants(session.getSessionId()));
        view.setCollaborationPolicy(readJson(session.getCollaborationPolicyJson()));
        return view;
    }

    private void appendControlEvent(AiSessionCollaborationView state, String eventType, String previousCoordinator) {
        AiSessionContextAppendRequest request = new AiSessionContextAppendRequest();
        request.setEventId("session-control:" + UUID.randomUUID());
        request.setSenderType("CENTER");
        request.setSenderId("cc-center");
        request.setTargetNodeId(String.join(",", state.getParticipantNodeIds()));
        request.setRole("system");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("eventType", eventType);
        content.put("collaborationMode", state.getCollaborationMode());
        content.put("coordinatorNodeId", state.getCoordinatorNodeId());
        content.put("coordinatorEpoch", state.getCoordinatorEpoch());
        content.put("participantNodeIds", state.getParticipantNodeIds());
        content.put("collaborationPolicy", state.getCollaborationPolicy());
        if (!isBlank(previousCoordinator)) {
            content.put("previousCoordinatorNodeId", previousCoordinator);
        }
        request.setContent(writeJson(content));
        request.setContentType("SESSION_CONTROL");
        contextService.append(state.getSessionId(), request);
    }

    private AiSessionEntity session(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    private Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) {
            source.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
        }
        return result;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize collaboration policy", error);
        }
    }

    private Map<String, Object> readJson(String value) {
        if (isBlank(value)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception error) {
            return new LinkedHashMap<>();
        }
    }

    private String now() {
        return String.valueOf(System.currentTimeMillis());
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
