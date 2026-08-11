package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;

public class RoutingAiRelayService implements AiRelayService {

    private final ClaudeCodeRelayService claudeCodeRelayService;
    private final OpenAiCompatibleRelayService openAiCompatibleRelayService;

    public RoutingAiRelayService(ClaudeCodeRelayService claudeCodeRelayService,
                                 OpenAiCompatibleRelayService openAiCompatibleRelayService) {
        this.claudeCodeRelayService = claudeCodeRelayService;
        this.openAiCompatibleRelayService = openAiCompatibleRelayService;
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        if (request == null || request.getModelConfig() == null) {
            throw new IllegalArgumentException("AiChatRequest.modelConfig is required");
        }
        String provider = request.getModelConfig().getProvider();
        if ("CLAUDE_CODE".equalsIgnoreCase(provider)) {
            return claudeCodeRelayService.chat(request);
        }
        return openAiCompatibleRelayService.chat(request);
    }
}
