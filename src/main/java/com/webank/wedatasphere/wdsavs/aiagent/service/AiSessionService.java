package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;

public interface AiSessionService {

    String openSession(String initiatorType, String initiatorId, String sourceNodeId);

    void closeSession(String sessionId);

    void validateSession(String sessionId);

    void bindTask(String sessionId, AiTaskCreateRequest request);
}
