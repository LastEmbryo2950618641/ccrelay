package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AiSessionMessageCompletion {
    private String queueId;
    private String sessionId;
    private String requestId;
    private String sourceNodeId;
    private String targetNodeId;
    private Map<String, Object> requestParams = new LinkedHashMap<>();
    private AiChatResponse response;
}
