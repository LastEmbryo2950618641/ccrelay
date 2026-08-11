package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class CenterSshPreflightRequest {
    private String host;
    private Integer port = 22;
    private String username;
    private Long timeoutMs = 15_000L;
}
