package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelfReplicateResponse {
    private Boolean accepted;
    private Boolean success;
    private String status;
    private String nextAction;
    private Integer exitCode;
    private String stdoutSummary;
    private String stderrSummary;
    private Integer resolvedRelayPort;
    private String resolvedRemoteDirectory;
    private String operationId;
    private SelfReplicateProgress progress;

    public SelfReplicateResponse(Boolean accepted, Boolean success, String status, String nextAction,
                                 Integer exitCode, String stdoutSummary, String stderrSummary) {
        this.accepted = accepted;
        this.success = success;
        this.status = status;
        this.nextAction = nextAction;
        this.exitCode = exitCode;
        this.stdoutSummary = stdoutSummary;
        this.stderrSummary = stderrSummary;
    }
}
