package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentExecutionRequest {
    private String sessionId;
    private String taskId;
    private String requestId;
    private String grantId;
    private String sourceNodeId;
    private String targetNodeId;
    private String prompt;
    private AiModelConfig modelConfig;
    private ReactExecutionPolicy reactPolicy;
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
