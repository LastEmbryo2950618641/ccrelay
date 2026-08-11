package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class SshIdentityTrustEdgeRequest {
    private String sourceNodeKey;
    private String targetNodeKey;
    private String runtimeUsername;
    private String keyFingerprint;
    private String status;
    private Long latencyMs;
    private String lastErrorCode;
    private String lastErrorSummary;
    private String lastVerifiedTime;
}
