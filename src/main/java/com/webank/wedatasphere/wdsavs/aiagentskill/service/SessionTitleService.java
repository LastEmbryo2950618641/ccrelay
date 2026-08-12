package com.webank.wedatasphere.wdsavs.aiagentskill.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionContextEventEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionContextEventRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionRepository;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayGrantService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayRegistryService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionTitleService {

    private static final String REQUEST_PURPOSE = "SESSION_TITLE";
    private static final String SOURCE_NODE_ID = "cc-center-title";
    private static final int MAX_TITLE_CODE_POINTS = 40;

    private final AiSessionRepository sessionRepository;
    private final AiSessionContextEventRepository contextEventRepository;
    private final AiRelayRegistryService relayRegistryService;
    private final AiRelayGrantService relayGrantService;
    private final RestTemplate restTemplate;
    private final Set<String> inFlightSessions = ConcurrentHashMap.newKeySet();

    public SessionTitleService(AiSessionRepository sessionRepository,
                               AiSessionContextEventRepository contextEventRepository,
                               AiRelayRegistryService relayRegistryService,
                               AiRelayGrantService relayGrantService,
                               RestTemplate aiSkillRestTemplate) {
        this.sessionRepository = sessionRepository;
        this.contextEventRepository = contextEventRepository;
        this.relayRegistryService = relayRegistryService;
        this.relayGrantService = relayGrantService;
        this.restTemplate = aiSkillRestTemplate;
    }

    @Async
    public void generateIfAbsentAsync(String sessionId, String coordinatorNodeId) {
        if (isBlank(sessionId) || isBlank(coordinatorNodeId) || !inFlightSessions.add(sessionId)) {
            return;
        }
        try {
            generateIfAbsent(sessionId, coordinatorNodeId);
        } catch (Exception ignored) {
        } finally {
            inFlightSessions.remove(sessionId);
        }
    }

    void generateIfAbsent(String sessionId, String coordinatorNodeId) {
        AiSessionEntity session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session == null || !isBlank(session.getTitle())) {
            return;
        }
        AiSessionContextEventEntity firstUserMessage = contextEventRepository
                .findFirstBySessionIdAndRoleOrderByIdAsc(sessionId, "user")
                .orElse(null);
        if (firstUserMessage == null || isBlank(firstUserMessage.getContent())) {
            return;
        }
        RelayNodeView coordinator = relayRegistryService.getNode(coordinatorNodeId);
        if (coordinator == null || !"AVAILABLE".equalsIgnoreCase(coordinator.getStatus())
                || isBlank(coordinator.getRelayEndpoint())) {
            return;
        }
        RelayAccessDecisionResponse grant = requestGrant(sessionId, coordinatorNodeId);
        if (grant == null || !"ALLOW".equalsIgnoreCase(grant.getDecision())) {
            return;
        }
        AiChatRequest request = titleRequest(sessionId, coordinatorNodeId, firstUserMessage.getContent(), grant);
        AiChatResponse response = restTemplate.postForObject(coordinator.getRelayEndpoint(), request, AiChatResponse.class);
        String title = normalizeTitle(response == null ? null : response.getAnswer());
        if (response == null || !"SUCCESS".equalsIgnoreCase(response.getStatus()) || isBlank(title)) {
            return;
        }
        AiSessionEntity current = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (current == null || !isBlank(current.getTitle())) {
            return;
        }
        current.setTitle(title);
        current.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        sessionRepository.save(current);
    }

    private RelayAccessDecisionResponse requestGrant(String sessionId, String coordinatorNodeId) {
        RelayAccessRequest request = new RelayAccessRequest();
        request.setSessionId(sessionId);
        request.setRequestId("session-title:" + UUID.randomUUID());
        request.setSourceNodeId(SOURCE_NODE_ID);
        request.setTargetNodeId(coordinatorNodeId);
        request.setReason("Generate a session title from the first user message");
        request.setRequiredCapabilities(List.of("CHAT"));
        return relayGrantService.requestAccess(request);
    }

    private AiChatRequest titleRequest(String sessionId, String coordinatorNodeId, String content,
                                       RelayAccessDecisionResponse grant) {
        AiChatRequest request = new AiChatRequest();
        request.setMessages(List.of(new AiChatMessage("user", content)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestPurpose", REQUEST_PURPOSE);
        metadata.put("sessionId", sessionId);
        metadata.put("sourceNodeId", SOURCE_NODE_ID);
        metadata.put("targetNodeId", coordinatorNodeId);
        Map<String, Object> relayGrant = new LinkedHashMap<>();
        putIfNotBlank(relayGrant, "grantId", grant.getGrantId());
        putIfNotBlank(relayGrant, "sessionId", sessionId);
        putIfNotBlank(relayGrant, "sourceNodeId", SOURCE_NODE_ID);
        putIfNotBlank(relayGrant, "targetNodeId", coordinatorNodeId);
        putIfNotBlank(relayGrant, "signedToken", grant.getSignedToken());
        putIfNotBlank(relayGrant, "expiresAt", grant.getExpiresAt());
        if (grant.getAllowedCapabilities() != null && !grant.getAllowedCapabilities().isEmpty()) {
            relayGrant.put("allowedCapabilities", grant.getAllowedCapabilities());
        }
        metadata.put("relayGrant", relayGrant);
        request.setMetadata(metadata);
        return request;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value);
        }
    }

    String normalizeTitle(String value) {
        if (isBlank(value)) {
            return null;
        }
        String title = value.trim().split("\\R", 2)[0].trim();
        title = title.replaceFirst("^(标题|会话标题)\\s*[:：]\\s*", "");
        title = title.replaceAll("^[\\s\\\"'“”‘’`#]+|[\\s\\\"'“”‘’`。！？!?；;]+$", "");
        if (isBlank(title)) {
            return null;
        }
        int codePoints = title.codePointCount(0, title.length());
        if (codePoints > MAX_TITLE_CODE_POINTS) {
            title = title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS));
        }
        return title;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
