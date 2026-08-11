package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionCollaborationView;

import java.util.List;
import java.util.Map;

public interface AiSessionCollaborationService {

    AiSessionCollaborationView initialize(String sessionId, String collaborationMode,
                                          List<String> participantNodeIds,
                                          Map<String, Object> collaborationPolicy);

    AiSessionCollaborationView getState(String sessionId);

    AiSessionCollaborationView ensureCoordinator(String sessionId);

    void enrichTaskParams(Map<String, Object> params);
}
