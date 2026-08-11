package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ClaudeCodeConfig {

    private String command;
    private String workingDirectory;
    private Map<String, Object> environment = new LinkedHashMap<>();
}
