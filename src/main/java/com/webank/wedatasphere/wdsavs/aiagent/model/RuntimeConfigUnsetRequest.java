package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class RuntimeConfigUnsetRequest {
    private String key;
    private String operatorId;
    private String comment;
}
