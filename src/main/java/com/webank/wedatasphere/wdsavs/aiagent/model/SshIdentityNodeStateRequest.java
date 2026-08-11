package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class SshIdentityNodeStateRequest {
    private String nodeKey;
    private String bootstrapCredentialScope;
    private String bootstrapUsername;
    private String runtimeUsername;
    private String osType;
    private String accountStatus;
    private String keyInstallStatus;
    private String centerAccessStatus;
    private String mutualAccessStatus;
    private String privilegeSummaryJson;
    private String lastErrorCode;
    private String lastErrorSummary;
    private String lastVerifiedTime;
}
