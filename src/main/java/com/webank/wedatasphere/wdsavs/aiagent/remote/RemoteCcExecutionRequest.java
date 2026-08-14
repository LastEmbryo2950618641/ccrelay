package com.webank.wedatasphere.wdsavs.aiagent.remote;

import lombok.Data;

import com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConvergencePolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class RemoteCcExecutionRequest {

    private String command;
    private List<String> arguments = new ArrayList<>();
    private String workingDirectory;
    private String model;
    private String prompt;
    private String retryPrompt;
    private String modelSessionId;
    private boolean resumeModelSession;
    private String claudeSettingsFile;
    private String claudeSettingsJson;
    private Map<String, String> environment = new LinkedHashMap<>();
    private ClaudeCodeConvergencePolicy convergencePolicy;
    private long timeoutMs;
    private ReactEventWriter eventWriter;
    private PromptSnapshot promptSnapshot;
    private boolean privateDraft;
    private String executionPhase;
}
