package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class RuntimeConfigUpsertRequest {
    private String key;
    private String value;
    private String operatorId;
    private String comment;
}
