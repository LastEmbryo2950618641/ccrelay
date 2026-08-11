package com.webank.wedatasphere.wdsavs.aiagent.remote;

import lombok.Data;

@Data
public class ReactCommandResult {
    private String status;
    private Integer exitCode;
    private String stdout;
    private String stderr;
    private String errorMessage;
    private Boolean timedOut = false;
}
