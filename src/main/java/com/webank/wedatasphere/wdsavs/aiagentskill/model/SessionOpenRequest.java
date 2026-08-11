package com.webank.wedatasphere.wdsavs.aiagentskill.model;

import lombok.Data;

@Data
public class SessionOpenRequest {
    private String initiatorType;
    private String initiatorId;
    private String sourceNodeId;
}
