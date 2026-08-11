package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RelayAccessRequest {
    private String sessionId;
    private String requestId;
    private String sourceNodeId;
    private String targetNodeId;
    private String reason;
    private List<String> requiredCapabilities = new ArrayList<>();
    private Long ttlMs;
    private String parentTaskId;
    private String traceId;
}
