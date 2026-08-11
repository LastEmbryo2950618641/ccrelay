package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionMessageCompletion;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionMessageDispatchResult;

import java.util.Map;
import java.util.function.Function;

public interface AiSessionMessageQueueService {

    AiSessionMessageDispatchResult submit(Map<String, Object> params, String requestId,
                                          boolean autoWakeWhenDeferred);

    void cancelSession(String sessionId);

    void registerDispatcher(Function<Map<String, Object>, AiChatResponse> dispatcher);

    void registerCompletionListener(Function<AiSessionMessageCompletion, Boolean> completionListener);
}
