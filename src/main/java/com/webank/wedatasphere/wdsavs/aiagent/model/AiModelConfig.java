package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class AiModelConfig {

    private Long id;
    private String provider;
    private String endpoint;
    private String apiPath;
    private String remoteEndpoint;
    private String model;
    private ClaudeCodeConfig claudeCode;
    private ClaudeCodeConvergencePolicy convergencePolicy;
}
