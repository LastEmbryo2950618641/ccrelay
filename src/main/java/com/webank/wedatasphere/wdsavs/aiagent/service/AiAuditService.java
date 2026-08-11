package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;

import java.util.Map;

public interface AiAuditService {

    String record(String sessionId, String taskId, String sourceNodeId, String targetNodeId,
                  AuditEventType eventType, String decision, Map<String, Object> detail, String operatorType, String operatorId);
}
