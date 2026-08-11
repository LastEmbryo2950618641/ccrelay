package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AiTaskView {
    private String taskId;
    private String sessionId;
    private String parentTaskId;
    private String requestId;
    private String traceId;
    private String auditId;
    private String agentRunId;
    private String taskType;
    private String status;
    private String currentStage;
    private String sourceNodeId;
    private String targetNodeId;
    private Map<String, Object> result = new LinkedHashMap<>();
    private String errorCode;
    private String errorMessage;
    private String startTime;
    private String endTime;
}

