package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiAuditLogEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiAuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AiAuditServiceImpl implements AiAuditService {

    private final AiAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiAuditServiceImpl(AiAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    public String record(String sessionId, String taskId, String sourceNodeId, String targetNodeId,
                         AuditEventType eventType, String decision, Map<String, Object> detail, String operatorType, String operatorId) {
        String now = String.valueOf(System.currentTimeMillis());
        AiAuditLogEntity entity = new AiAuditLogEntity();
        entity.setAuditId(UUID.randomUUID().toString());
        entity.setSessionId(sessionId);
        entity.setTaskId(taskId);
        entity.setSourceNodeId(sourceNodeId);
        entity.setTargetNodeId(targetNodeId);
        entity.setEventType(eventType == null ? null : eventType.name());
        entity.setDecision(decision);
        entity.setDetailJson(writeJson(detail));
        entity.setOperatorType(operatorType);
        entity.setOperatorId(operatorId);
        entity.setCreatedTime(now);
        auditLogRepository.save(entity);
        return entity.getAuditId();
    }

    private String writeJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize audit detail", e);
        }
    }
}
