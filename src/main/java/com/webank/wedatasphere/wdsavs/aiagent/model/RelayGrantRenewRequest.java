package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class RelayGrantRenewRequest {
    private String grantId;
    private String requestId;
    private Long ttlMs;
}
