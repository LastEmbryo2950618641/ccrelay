package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayGrantEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionStatus;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayGrantRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AiSessionServiceImpl implements AiSessionService {

    private final AiSessionRepository sessionRepository;
    private final AiRelayGrantRepository grantRepository;
    private AiSessionMessageQueueService sessionMessageQueueService;

    public AiSessionServiceImpl(AiSessionRepository sessionRepository, AiRelayGrantRepository grantRepository) {
        this.sessionRepository = sessionRepository;
        this.grantRepository = grantRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSessionMessageQueueService(AiSessionMessageQueueService sessionMessageQueueService) {
        this.sessionMessageQueueService = sessionMessageQueueService;
    }

    @Override
    public String openSession(String initiatorType, String initiatorId, String sourceNodeId) {
        String now = String.valueOf(System.currentTimeMillis());
        AiSessionEntity entity = new AiSessionEntity();
        entity.setSessionId(UUID.randomUUID().toString());
        entity.setSessionType("CHAT");
        entity.setInitiatorType(initiatorType);
        entity.setInitiatorId(initiatorId);
        entity.setSourceNodeId(sourceNodeId);
        entity.setStatus(AiSessionStatus.OPEN.name());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        sessionRepository.save(entity);
        return entity.getSessionId();
    }

    @Override
    public void closeSession(String sessionId) {
        AiSessionEntity entity = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        String now = String.valueOf(System.currentTimeMillis());
        entity.setStatus(AiSessionStatus.CLOSED.name());
        entity.setUpdateTime(now);
        sessionRepository.save(entity);
        revokeSessionGrants(sessionId, now);
        if (sessionMessageQueueService != null) {
            sessionMessageQueueService.cancelSession(sessionId);
        }
    }

    @Override
    public void validateSession(String sessionId) {
        AiSessionEntity entity = requireSession(sessionId);
        if (!AiSessionStatus.OPEN.name().equalsIgnoreCase(entity.getStatus())) {
            throw new IllegalArgumentException("Session is not open: " + sessionId);
        }
    }

    @Override
    public void validateSessionExists(String sessionId) {
        requireSession(sessionId);
    }

    private AiSessionEntity requireSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    @Override
    public void bindTask(String sessionId, AiTaskCreateRequest request) {
        validateSession(sessionId);
    }

    private void revokeSessionGrants(String sessionId, String now) {
        List<AiRelayGrantEntity> grants = grantRepository.findBySessionIdOrderByCreateTimeDesc(sessionId);
        for (AiRelayGrantEntity grant : grants) {
            if (isRevocable(grant.getStatus())) {
                grant.setStatus("REVOKED");
                grant.setRevokedAt(now);
                grant.setReason("session closed");
                grant.setUpdateTime(now);
                grantRepository.save(grant);
            }
        }
    }

    private boolean isRevocable(String status) {
        return "ACTIVE".equalsIgnoreCase(status)
                || "WAITING_DEPLOY".equalsIgnoreCase(status)
                || "PENDING_APPROVAL".equalsIgnoreCase(status)
                || "REVOKE_SCHEDULED".equalsIgnoreCase(status);
    }
}
