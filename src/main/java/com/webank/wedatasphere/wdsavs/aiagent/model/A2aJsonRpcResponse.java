package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class A2aJsonRpcResponse {

    private String jsonrpc;
    private String id;
    private Map<String, Object> result;
    private Map<String, Object> error;

    public static A2aJsonRpcResponse ok(String id, Map<String, Object> result) {
        A2aJsonRpcResponse response = new A2aJsonRpcResponse();
        response.setJsonrpc("2.0");
        response.setId(id);
        response.setResult(result);
        return response;
    }

    public static A2aJsonRpcResponse error(String id, int code, String message) {
        A2aJsonRpcResponse response = new A2aJsonRpcResponse();
        response.setJsonrpc("2.0");
        response.setId(id);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        response.setError(error);
        return response;
    }
}
