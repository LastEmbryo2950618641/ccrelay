package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SelfReplicateRequest {
    private String taskId;
    private String sessionId;
    private String sourceNodeId;
    private String targetNodeId;
    private String deployMode;
    private String host;
    private Integer port = 22;
    private String username;
    private Integer relayPort;
    private Boolean replaceExistingRelay = false;
    private String scriptPath;
    private String artifactPath;
    private String remoteDirectory;
    private Long timeoutMs;
    private Long progressPollIntervalMs = 2000L;
    private Long progressHeartbeatIntervalMs = 10000L;
    private List<String> commandArguments = new ArrayList<>();
    private String artifactVersion;
}
