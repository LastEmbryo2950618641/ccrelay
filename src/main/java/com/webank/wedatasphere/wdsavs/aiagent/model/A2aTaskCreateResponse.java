package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class A2aTaskCreateResponse {
    private String jsonrpc = "2.0";
    private String id;
    private Map<String, Object> result = new LinkedHashMap<>();
}
