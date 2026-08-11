package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AiTaskEventView {
    private String eventId;
    private String taskId;
    private String sessionId;
    private String requestId;
    private String traceId;
    private String auditId;
    private String agentRunId;
    private String eventType;
    private Long sequenceNo;
    private String createdTime;
    private Map<String, Object> payload = new LinkedHashMap<>();
}
