package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class RuntimeConfigView {
    private String key;
    private String value;
    private String maskedValue;
    private String fingerprint;
    private String configType;
    private Boolean dynamic;
    private Boolean restartRequired;
    private Boolean secret;
    private Long version;
    private String description;
    private String updateTime;
    private String createTime;
    private String modifyUser;
}
