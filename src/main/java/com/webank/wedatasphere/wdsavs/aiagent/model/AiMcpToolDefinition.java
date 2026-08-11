package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiMcpToolDefinition {

    private String id;
    private String name;
    private String description;
    private String serverName;
    private String toolName;
    private String endpoint;
    private String transport;
    private String source;
    private Boolean enabled = true;
    private List<String> tags = new ArrayList<>();
}
