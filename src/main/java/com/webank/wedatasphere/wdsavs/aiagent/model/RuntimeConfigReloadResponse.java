package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class RuntimeConfigReloadResponse {
    private boolean reloaded;
    private int itemCount;
    private String updateTime;
}
