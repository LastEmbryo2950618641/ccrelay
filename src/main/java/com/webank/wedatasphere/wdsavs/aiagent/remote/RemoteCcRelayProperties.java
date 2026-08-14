package com.webank.wedatasphere.wdsavs.aiagent.remote;

import lombok.Data;

import com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConvergencePolicy;

import java.util.ArrayList;
import java.util.List;

@Data
public class RemoteCcRelayProperties {

    private String host = "127.0.0.1";
    private int port = 18091;
    private String path = "/api/ai/remote-cc/chat";
    private String command = "claude";
    private List<String> arguments = new ArrayList<>(List.of("--print"));
    private String workingDirectory;
    private List<String> allowedWorkRoots = new ArrayList<>(List.of("*"));
    private List<String> allowedLogRoots = new ArrayList<>(List.of("*"));
    private List<String> allowedCodeRoots = new ArrayList<>(List.of("*"));
    private long a2aLargeFileThresholdBytes = 5L * 1024L * 1024L;
    private String centerGrantValidateEndpoint;
    private String centerRegisterEndpoint;
    private String centerHeartbeatEndpoint;
    private long heartbeatIntervalMs = 30000L;
    private String skillDirectory = "./skills";
    private String skillMetadataDbPath;
    private String promptDirectory = "./prompts";
    private String promptMetadataPath;
    private String nodeIdFilePath;
    private String contextStateFilePath;
    private String nodeId;
    private String nodeHost;
    private String nodeRole = "RELAY";
    private String relayEndpoint;
    private String version = "0.1.2";
    private String protocolVersion = "1.0";
    private List<String> capabilities = new ArrayList<>(List.of("CHAT", "A2A_MESSAGE_SEND", "A2A_TASK_CREATE", "A2A_TASK_GET", "A2A_TASK_CANCEL", "SELF_REPLICATE"));
    private long timeoutMs = 600000L;
    private int maxConcurrentSessions = 4;
    private String model;
    private String baseUrl;
    private String systemPromptFilePath;
    private String claudeSettingsFilePath;
    private boolean apiKeyConfigured;
    private ClaudeCodeConvergencePolicy defaultConvergencePolicy = ClaudeCodeConvergencePolicy.defaults();

    public boolean isAiConfigReady() {
        return model != null && !model.isBlank()
                && baseUrl != null && !baseUrl.isBlank()
                && apiKeyConfigured;
    }
}

