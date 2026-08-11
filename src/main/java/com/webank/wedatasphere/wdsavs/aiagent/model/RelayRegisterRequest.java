package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class RelayRegisterRequest {
    private String nodeId;
    private String host;
    private Integer port;
    private String relayEndpoint;
    private String version;
    private String protocolVersion;
    private List<String> capabilities = new ArrayList<>();
    private String workspaceRoot;
    private Map<String, Object> environmentSummary = new LinkedHashMap<>();
}
