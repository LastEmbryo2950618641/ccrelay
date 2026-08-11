package com.webank.wedatasphere.wdsavs.aiagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SshDeployResult {
    private boolean success;
    private Integer exitCode;
    private String stdoutSummary;
    private String stderrSummary;
    private Integer resolvedRelayPort;
    private String resolvedRemoteDirectory;

    public SshDeployResult(boolean success, Integer exitCode, String stdoutSummary, String stderrSummary) {
        this.success = success;
        this.exitCode = exitCode;
        this.stdoutSummary = stdoutSummary;
        this.stderrSummary = stderrSummary;
    }
}
