package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class RuntimeConfigHistoryView {
    private String key;
    private String action;
    private String eventType;
    private String decision;
    private Long version;
    private String updateTime;
    private String operatorId;
    private Map<String, Object> detail = new LinkedHashMap<>();
}
