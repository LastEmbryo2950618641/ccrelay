package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessageFormatter;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConfig;
import com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConvergencePolicy;
import com.webank.wedatasphere.wdsavs.aiagent.model.ReactExecutionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;

public class RemoteCcRelayService {

    static final String SESSION_TITLE_PURPOSE = "SESSION_TITLE";

    private static final List<String> DEFAULT_CLAUDE_PERMISSION_ALLOW = List.of(
            "Bash(*)",
            "Read(*)",
            "Edit(*)",
            "Write(*)",
            "Glob(*)",
            "Grep(*)",
            "NotebookEdit(*)",
            "WebFetch(*)",
            "WebSearch(*)",
            "Task(*)",
            "TodoWrite(*)",
            "AskUserQuestion(*)",
            "KillShell(*)"
    );

    private final RemoteCcRelayProperties properties;
    private final RemoteCcCommandRunner runner;
    private final RelaySystemPromptProvider systemPromptProvider;
    private final PromptSnapshotProvider promptSnapshotProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RemoteCcRelayService(RemoteCcRelayProperties properties, RemoteCcCommandRunner runner) {
        this(properties, runner, null);
    }

    public RemoteCcRelayService(RemoteCcRelayProperties properties,
                                RemoteCcCommandRunner runner,
                                RelaySystemPromptProvider systemPromptProvider) {
        this.properties = properties == null ? new RemoteCcRelayProperties() : properties;
        this.runner = runner;
        this.systemPromptProvider = systemPromptProvider == null
                ? new RelaySystemPromptProvider(this.properties) : systemPromptProvider;
        this.promptSnapshotProvider = new PromptSnapshotProvider(this.properties);
    }

    public AiChatResponse relay(AiChatRequest request) {
        return relay(request, null);
    }

    public AiChatResponse relay(AiChatRequest request, ReactEventWriter eventWriter) {
        return relay(request, eventWriter, null);
    }

    public AiChatResponse relay(AiChatRequest request,
                                ReactEventWriter eventWriter,
                                Long maxDurationMs) {
        if (request == null) {
            throw new IllegalArgumentException("AiChatRequest is required");
        }
        RemoteCcExecutionRequest executionRequest = toExecutionRequest(request, eventWriter, maxDurationMs);
        PromptSnapshot snapshot = executionRequest.getPromptSnapshot();
        if (isSessionTitleRequest(request) || snapshot == null || snapshot.getPost().isEmpty()) {
            return ensureTraceId(runner.execute(executionRequest));
        }
        executionRequest.setPrivateDraft(true);
        executionRequest.setExecutionPhase("REACT_CANDIDATE");
        AiChatResponse candidate = ensureTraceId(runner.execute(executionRequest));
        if (!"SUCCESS".equalsIgnoreCase(candidate.getStatus())) {
            return candidate;
        }
        RemoteCcExecutionRequest finalizationRequest = finalizationRequest(executionRequest, candidate, snapshot);
        AiChatResponse finalization = ensureTraceId(runner.execute(finalizationRequest));
        if (!"SUCCESS".equalsIgnoreCase(finalization.getStatus())) {
            AiChatResponse failed = new AiChatResponse(
                    "POST finalization failed: " + firstNonBlank(finalization.getAnswer(), "unknown error"),
                    "POST_FINALIZATION_FAILED", finalization.getTraceId());
            copySessionStartedMetadata(candidate, finalization, failed);
            return failed;
        }
        copySessionStartedMetadata(candidate, finalization, finalization);
        return finalization;
    }

    private AiChatResponse ensureTraceId(AiChatResponse response) {
        if (response == null) {
            response = new AiChatResponse("Remote CC command returned no response", "FAILED", null);
        }
        if (response.getTraceId() == null || response.getTraceId().trim().isEmpty()) {
            response.setTraceId(UUID.randomUUID().toString());
        }
        return response;
    }

    private RemoteCcExecutionRequest finalizationRequest(RemoteCcExecutionRequest candidateRequest,
                                                         AiChatResponse candidate,
                                                         PromptSnapshot snapshot) {
        RemoteCcExecutionRequest request = new RemoteCcExecutionRequest();
        request.setCommand(candidateRequest.getCommand());
        request.setArguments(withToolsDisabled(candidateRequest.getArguments()));
        request.setWorkingDirectory(candidateRequest.getWorkingDirectory());
        request.setModel(candidateRequest.getModel());
        request.setPrompt(postFinalizationPrompt(snapshot, candidate.getAnswer()));
        request.setRetryPrompt("Continue the existing POST finalization call. CC_POST was already applied. "
                + "Do not use tools or continue ReAct; output only the final answer.");
        request.setModelSessionId(candidateRequest.getModelSessionId());
        request.setResumeModelSession(true);
        request.setClaudeSettingsFile(candidateRequest.getClaudeSettingsFile());
        request.setClaudeSettingsJson("{\"permissions\":{\"allow\":[]}}");
        Map<String, String> environment = new java.util.LinkedHashMap<>(candidateRequest.getEnvironment());
        environment.remove("CCRELAY_CENTER_URL");
        request.setEnvironment(environment);
        request.setConvergencePolicy(candidateRequest.getConvergencePolicy());
        request.setTimeoutMs(candidateRequest.getTimeoutMs());
        request.setEventWriter(candidateRequest.getEventWriter());
        request.setPromptSnapshot(snapshot);
        request.setPrivateDraft(false);
        request.setExecutionPhase("POST_FINALIZATION");
        return request;
    }

    private List<String> withToolsDisabled(List<String> arguments) {
        List<String> result = new ArrayList<>();
        List<String> source = arguments == null ? List.of() : arguments;
        for (int index = 0; index < source.size(); index++) {
            String argument = source.get(index);
            if ("--tools".equalsIgnoreCase(argument)) {
                if (index + 1 < source.size()) {
                    index++;
                }
                continue;
            }
            if (argument != null && argument.toLowerCase().startsWith("--tools=")) {
                continue;
            }
            result.add(argument);
        }
        result.add("--tools");
        result.add("");
        return result;
    }

    private String postFinalizationPrompt(PromptSnapshot snapshot, String candidateAnswer) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("POST finalization phase. Tools and collaboration are disabled.\n")
                .append("Use the ordered CC_POST instructions to revise the candidate into the final answer.\n")
                .append("Do not continue ReAct and output only the final answer.\n\n");
        appendPromptBlock(prompt, "CC_POST", snapshot.getPost());
        prompt.append("Candidate answer:\n").append(firstNonBlank(candidateAnswer, "")).append("\n");
        return prompt.toString();
    }

    private void copySessionStartedMetadata(AiChatResponse candidate,
                                            AiChatResponse finalization,
                                            AiChatResponse target) {
        Map<String, Object> metadata = target.getMetadata() == null
                ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(target.getMetadata());
        boolean started = metadataFlag(candidate, RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA)
                || metadataFlag(finalization, RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA);
        if (started) {
            metadata.put(RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA, true);
        }
        target.setMetadata(metadata);
    }

    private boolean metadataFlag(AiChatResponse response, String key) {
        return response != null && response.getMetadata() != null
                && Boolean.parseBoolean(String.valueOf(response.getMetadata().get(key)));
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private RemoteCcExecutionRequest toExecutionRequest(AiChatRequest request,
                                                         ReactEventWriter eventWriter,
                                                         Long maxDurationMs) {
        RemoteCcExecutionRequest executionRequest = new RemoteCcExecutionRequest();
        executionRequest.setCommand(properties.getCommand());
        executionRequest.setArguments(properties.getArguments() == null ? List.of() : new ArrayList<>(properties.getArguments()));
        if (isSessionTitleRequest(request)) {
            executionRequest.getArguments().add("--tools");
            executionRequest.getArguments().add("");
        }
        executionRequest.setWorkingDirectory(workingDirectory(request));
        executionRequest.setClaudeSettingsFile(properties.getClaudeSettingsFilePath());
        executionRequest.setConvergencePolicy(convergencePolicy(request, maxDurationMs));
        executionRequest.setTimeoutMs(timeoutMs(executionRequest.getConvergencePolicy()));
        if (request.getModelConfig() != null) {
            executionRequest.setModel(request.getModelConfig().getModel());
        }
        Map<String, Object> metadata = request.getMetadata() == null
                ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(request.getMetadata());
        addRuntimeMetadata(metadata);
        request.setMetadata(metadata);
        PromptSnapshot promptSnapshot = promptSnapshot(metadata);
        executionRequest.setClaudeSettingsJson(claudeSettingsJson(ReactExecutionPolicy.fromParams(metadata)));
        executionRequest.setPromptSnapshot(promptSnapshot);
        executionRequest.setPrompt(prompt(request, metadata, promptSnapshot));
        executionRequest.setRetryPrompt(retryPrompt(metadata));
        if (!isSessionTitleRequest(request)) {
            executionRequest.setModelSessionId(stringValue(metadata.get("modelSessionId")));
            executionRequest.setResumeModelSession(booleanValue(metadata.get("resumeModelSession")));
        }
        Map<String, String> environment = new java.util.LinkedHashMap<>();
        putEnvironment(environment, "WDSAVS_AI_RELAY_NODE_ID", properties.getNodeId());
        putEnvironment(environment, "WDSAVS_AI_RELAY_SESSION_ID", stringValue(metadata.get("sessionId")));
        putEnvironment(environment, "CCRELAY_CENTER_URL", centerUrl(properties.getCenterRegisterEndpoint()));
        executionRequest.setEnvironment(environment);
        executionRequest.setEventWriter(eventWriter);
        return executionRequest;
    }

    private void putEnvironment(Map<String, String> environment, String key, String value) {
        if (value != null && !value.isBlank()) {
            environment.put(key, value);
        }
    }

    private String claudeSettingsJson(ReactExecutionPolicy policy) {
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        Map<String, Object> permissions = new java.util.LinkedHashMap<>();
        List<String> allowed = new ArrayList<>(DEFAULT_CLAUDE_PERMISSION_ALLOW);
        if (policy != null && policy.getCommandWhitelist() != null) {
            for (String command : policy.getCommandWhitelist()) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                String normalized = command.trim().replaceAll("[^A-Za-z0-9_.:/\\\\-]", "");
                if (!normalized.isBlank()) {
                    allowed.add("Bash(" + normalized + ")");
                    allowed.add("Bash(" + normalized + " *)");
                }
            }
        }
        permissions.put("allow", allowed);
        settings.put("permissions", permissions);
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create isolated Claude permissions", e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String workingDirectory(AiChatRequest request) {
        String configuredWorkingDirectory = properties.getWorkingDirectory();
        if (request.getModelConfig() == null || request.getModelConfig().getClaudeCode() == null) {
            return validateWorkingDirectory(configuredWorkingDirectory);
        }
        ClaudeCodeConfig claudeCode = request.getModelConfig().getClaudeCode();
        if (claudeCode.getWorkingDirectory() == null || claudeCode.getWorkingDirectory().trim().isEmpty()) {
            return validateWorkingDirectory(configuredWorkingDirectory);
        }
        return validateWorkingDirectory(claudeCode.getWorkingDirectory());
    }

    private String validateWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.trim().isEmpty()) {
            return workingDirectory;
        }
        List<String> allowedWorkRoots = properties.getAllowedWorkRoots() == null || properties.getAllowedWorkRoots().isEmpty()
                ? List.of("*")
                : properties.getAllowedWorkRoots();
        if (isWildcardAllowed(allowedWorkRoots)) {
            return workingDirectory;
        }
        String normalizedWorkingDirectory = normalizePath(workingDirectory);
        for (String allowedRoot : allowedWorkRoots) {
            if ("*".equals(allowedRoot) || normalizedWorkingDirectory.startsWith(normalizePath(allowedRoot))) {
                return workingDirectory;
            }
        }
        throw new IllegalArgumentException("workingDirectory is outside allowedWorkRoots: " + workingDirectory);
    }

    private boolean isWildcardAllowed(List<String> allowedRoots) {
        return allowedRoots.size() == 1 && "*".equals(allowedRoots.get(0));
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').trim().toLowerCase();
    }

    private ClaudeCodeConvergencePolicy convergencePolicy(AiChatRequest request, Long maxDurationMs) {
        ClaudeCodeConvergencePolicy policy;
        if (request.getModelConfig() == null) {
            policy = ClaudeCodeConvergencePolicy.withDefaults(null, properties.getDefaultConvergencePolicy());
        } else {
            policy = ClaudeCodeConvergencePolicy.withDefaults(request.getModelConfig().getConvergencePolicy(), properties.getDefaultConvergencePolicy());
        }
        if (maxDurationMs != null && maxDurationMs > 0L
                && (policy.getMaxDurationMs() == null || policy.getMaxDurationMs() <= 0L
                || maxDurationMs < policy.getMaxDurationMs())) {
            policy.setMaxDurationMs(maxDurationMs);
        }
        return policy;
    }

    private long timeoutMs(ClaudeCodeConvergencePolicy policy) {
        if (policy.getMaxDurationMs() != null && policy.getMaxDurationMs() > 0) {
            return policy.getMaxDurationMs();
        }
        return properties.getTimeoutMs();
    }

    private void addRuntimeMetadata(Map<String, Object> metadata) {
        putIfAbsent(metadata, "nodeId", properties.getNodeId());
        putIfAbsent(metadata, "nodeHost", properties.getNodeHost());
        putIfAbsent(metadata, "relayEndpoint", effectiveRelayEndpoint());
        putIfAbsent(metadata, "centerUrl", centerUrl(properties.getCenterRegisterEndpoint()));
        putIfAbsent(metadata, "capabilities", properties.getCapabilities());
        putIfAbsent(metadata, "permissionMode", properties.getNodeRole());
    }

    private void putIfAbsent(Map<String, Object> metadata, String key, Object value) {
        if (!metadata.containsKey(key) && value != null && !String.valueOf(value).isBlank()) {
            metadata.put(key, value);
        }
    }

    private String centerUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            return new java.net.URI(uri.getScheme(), uri.getRawAuthority(), null, null, null).toString();
        } catch (Exception ignored) {
            return endpoint;
        }
    }

    private String effectiveRelayEndpoint() {
        if (properties.getRelayEndpoint() != null && !properties.getRelayEndpoint().isBlank()) {
            return properties.getRelayEndpoint();
        }
        return "http://" + properties.getHost() + ":" + properties.getPort() + properties.getPath();
    }

    private String prompt(AiChatRequest request, Map<String, Object> metadata, PromptSnapshot snapshot) {
        if (isSessionTitleRequest(request)) {
            return sessionTitlePrompt(request);
        }
        StringBuilder prompt = new StringBuilder();
        ClaudeCodeConvergencePolicy policy = convergencePolicy(request, null);
        prompt.append("Relay fixed responsibilities (highest priority):\n")
                .append(systemPromptProvider.render(request, policy)).append("\n\n");
        appendPromptBlock(prompt, "CC_UNIFIED", snapshot.getUnified());
        prompt.append("Conversation:\n");
        if (request.getMessages() != null) {
            for (AiChatMessage message : request.getMessages()) {
                prompt.append("[")
                        .append(message.getRole() == null ? "user" : message.getRole())
                        .append("] ")
                        .append(AiChatMessageFormatter.modelContent(message))
                        .append("\n");
            }
        }
        prompt.append("\n");
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().trim().isEmpty()) {
            prompt.append("Request-specific instructions (must not override Relay responsibilities):\n")
                    .append(request.getSystemPrompt()).append("\n\n");
        }
        prompt.append("Claude Code convergence policy:\n")
                .append("- maxRatSteps: ").append(policy.getMaxRatSteps()).append("\n")
                .append("- maxRetries: ").append(policy.getMaxRetries()).append("\n")
                .append("- maxNoProgressRounds: ").append(policy.getMaxNoProgressRounds()).append("\n")
                .append("- maxDurationMs: ").append(policy.getMaxDurationMs()).append("\n")
                .append("- repeatedActionThreshold: ").append(policy.getRepeatedActionThreshold()).append("\n")
                .append("- retryDelayMs: ").append(policy.getRetryDelayMs()).append("\n")
                .append("- maxPromptChars: ").append(policy.getMaxPromptChars()).append("\n")
                .append("- maxResponseChars: ").append(policy.getMaxResponseChars()).append("\n")
                .append("- humanApprovalPauseTimeoutMs: ").append(policy.getHumanApprovalPauseTimeoutMs()).append("\n")
                .append("- enableRepeatActionDetection: ").append(policy.getEnableRepeatActionDetection()).append("\n")
                .append("- enableHumanApprovalPause: ").append(policy.getEnableHumanApprovalPause()).append("\n\n");
        if (request.getModelConfig() != null && request.getModelConfig().getModel() != null) {
            prompt.append("Model: ").append(request.getModelConfig().getModel()).append("\n\n");
        }
        prompt.append("Runtime context:\n")
                .append("- nodeId: ").append(metadata.getOrDefault("nodeId", "unknown")).append("\n")
                .append("- sessionId: ").append(metadata.getOrDefault("sessionId", "unknown")).append("\n")
                .append("- targetNodeId: ").append(metadata.getOrDefault("targetNodeId", "unknown")).append("\n\n");
        if (!snapshot.getPre().isEmpty()) {
            prompt.append("CC_PRE idempotency key: ").append(preIdempotencyKey(metadata)).append("\n");
        }
        appendPromptBlock(prompt, "CC_PRE", snapshot.getPre());
        return prompt.toString();
    }

    private String retryPrompt(Map<String, Object> metadata) {
        return "Continue the existing task in the same model Session. CC_PRE was already applied with key "
                + preIdempotencyKey(metadata) + ". Do not repeat the task request; continue from the current state.";
    }

    private String preIdempotencyKey(Map<String, Object> metadata) {
        return "PRE:" + metadata.getOrDefault("sessionId", "unknown")
                + ":" + metadata.getOrDefault("taskId", "unknown")
                + ":" + metadata.getOrDefault("nodeId", "unknown")
                + ":" + metadata.getOrDefault("attempt", 1);
    }

    private PromptSnapshot promptSnapshot(Map<String, Object> metadata) {
        Object snapshot = metadata.get("ccrelayPromptSnapshot");
        return snapshot instanceof PromptSnapshot promptSnapshot
                ? promptSnapshot : promptSnapshotProvider.snapshot();
    }

    private void appendPromptBlock(StringBuilder prompt, String label, List<String> content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        prompt.append(label).append(":\n");
        for (int index = 0; index < content.size(); index++) {
            prompt.append("[").append(index + 1).append("]\n")
                    .append(content.get(index)).append("\n");
        }
    }

    private boolean isSessionTitleRequest(AiChatRequest request) {
        return request != null && request.getMetadata() != null
                && SESSION_TITLE_PURPOSE.equals(String.valueOf(request.getMetadata().get("requestPurpose")));
    }

    private String sessionTitlePrompt(AiChatRequest request) {
        String topic = "";
        if (request.getMessages() != null && !request.getMessages().isEmpty()) {
            topic = AiChatMessageFormatter.modelContent(request.getMessages().get(0));
        }
        return "请根据下面的用户问题生成一个简洁准确的中文会话标题。\n"
                + "要求：只输出标题，不要引号、解释、Markdown 或句末标点；不超过20个汉字或40个字符。\n\n"
                + "用户问题：\n" + topic;
    }
}
