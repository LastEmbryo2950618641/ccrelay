package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AiTaskCreateRequest {
    private String taskId;
    private String sessionId;
    private String requestId;
    private String parentTaskId;
    private String taskType;
    private String sourceNodeId;
    private String targetNodeId;
    private Map<String, Object> payload = new LinkedHashMap<>();
    private Long timeoutMs;
}