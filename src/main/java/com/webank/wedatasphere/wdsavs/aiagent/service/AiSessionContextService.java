package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextEventView;

import java.util.List;

public interface AiSessionContextService {

    AiSessionContextEventView append(String sessionId, AiSessionContextAppendRequest request);

    long headCursor(String sessionId);

    List<AiSessionContextEventView> delta(String sessionId, long afterCursor, int limit);
}
