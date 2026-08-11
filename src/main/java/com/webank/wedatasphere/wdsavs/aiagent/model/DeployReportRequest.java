package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class DeployReportRequest {
    private String taskId;
    private String sessionId;
    private String targetNodeId;
    private String deployMode;
    private String status;
    private String relayEndpoint;
    private String version;
    private Boolean healthPassed;
    private Boolean registered;
    private String stderrSummary;
    private String stdoutSummary;
    private Integer exitCode;
    private Boolean retryable;
}
