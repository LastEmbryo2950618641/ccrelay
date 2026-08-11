package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CenterSshPreflightResponse {
    private Boolean success;
    private String status;
    private String failureType;
    private String summary;
    private String host;
    private Integer port;
    private String username;
    private Long latencyMs;
}
