package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RelayGrantView {
    private String auditId;
    private String grantId;
    private String sessionId;
    private String requestId;
    private String sourceNodeId;
    private String targetNodeId;
    private List<String> allowedCapabilities = new ArrayList<>();
    private String status;
    private String expiresAt;
    private String revokedAt;
    private String reason;
}

