package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class A2aJsonRpcRequest {

    private String jsonrpc;
    private String id;
    private String method;
    private Map<String, Object> params = new LinkedHashMap<>();
}
