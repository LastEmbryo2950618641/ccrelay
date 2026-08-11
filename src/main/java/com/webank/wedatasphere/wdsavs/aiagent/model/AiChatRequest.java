package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AiChatRequest {

    private String systemPrompt;
    private List<AiChatMessage> messages;
    private AiModelConfig modelConfig;
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
