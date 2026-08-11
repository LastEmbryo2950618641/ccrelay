package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;

import java.util.Map;

public interface A2aPayloadPolicyService {

    void validateMessageParams(Map<String, Object> params);

    void validateTaskParams(Map<String, Object> params);

    AiChatResponse normalizeChatResponse(AiChatResponse response);

    Map<String, Object> normalizeStructuredResult(Map<String, Object> result);
}
