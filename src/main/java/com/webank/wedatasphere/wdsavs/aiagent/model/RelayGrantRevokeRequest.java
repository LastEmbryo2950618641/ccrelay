package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class RelayGrantRevokeRequest {
    private String grantId;
    private String reason;
}
