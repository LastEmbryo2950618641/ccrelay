package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConvergencePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RelaySystemPromptProvider {

    private static final String RESOURCE_PATH = "/config/relay-system-prompt.txt";
    private static final String FALLBACK_TEMPLATE = "你是 CC Relay 协作 Agent。你负责当前节点的受控观察、诊断、工具执行和会话协作，不是控制中心。\n"
            + "所有跨节点工作必须通过标准 ccrelay-cli、中心授权和 A2A 会话完成；不得用 SSH 或私有 HTTP 绕过标准流程处理业务问题。\n"
            + "所有 Relay 使用相同职责提示词；当前会话角色由 CC center 下发的 coordinatorNodeId 和 agentRole 决定。\n"
            + "遵守当前 ReAct 步数、命令白名单、超时、AI 开关、授权和审计策略。不得把 API key、Grant token、私钥或其他秘密写入上下文、工具参数、日志或回复。";

    private final String template;

    public RelaySystemPromptProvider(RemoteCcRelayProperties properties) {
        this.template = loadTemplate(properties == null ? null : properties.getSystemPromptFilePath());
    }

    public String render(AiChatRequest request, ClaudeCodeConvergencePolicy policy) {
        Map<String, String> values = runtimeValues(request, policy);
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }

    private String loadTemplate(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            try {
                String external = Files.readString(Path.of(configuredPath), StandardCharsets.UTF_8);
                if (!external.isBlank()) {
                    return external.trim();
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        try (InputStream inputStream = RelaySystemPromptProvider.class.getResourceAsStream(RESOURCE_PATH)) {
            if (inputStream != null) {
                String bundled = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                if (!bundled.isBlank()) {
                    return bundled.trim();
                }
            }
        } catch (IOException ignored) {
        }
        return FALLBACK_TEMPLATE;
    }

    private Map<String, String> runtimeValues(AiChatRequest request, ClaudeCodeConvergencePolicy policy) {
        Map<String, Object> metadata = request == null || request.getMetadata() == null
                ? Map.of() : request.getMetadata();
        return valuesFromMetadata(metadata, policy);
    }

    private Map<String, String> valuesFromMetadata(Map<String, Object> metadata,
                                                   ClaudeCodeConvergencePolicy policy) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Object> react = mapValue(metadata.get("react"));
        values.put("nodeId", value(metadata, "nodeId", "unknown"));
        values.put("nodeHost", value(metadata, "nodeHost", "unknown"));
        values.put("relayEndpoint", value(metadata, "relayEndpoint", "unknown"));
        values.put("centerUrl", value(metadata, "centerUrl", "unknown"));
        values.put("sessionId", value(metadata, "sessionId", "unknown"));
        values.put("sourceNodeId", value(metadata, "sourceNodeId", "unknown"));
        values.put("targetNodeId", value(metadata, "targetNodeId", "unknown"));
        values.put("collaborationMode", value(metadata, "collaborationMode", "DIRECT"));
        values.put("coordinatorNodeId", value(metadata, "coordinatorNodeId", "unknown"));
        values.put("coordinatorEpoch", value(metadata, "coordinatorEpoch", "0"));
        values.put("participantNodeIds", value(metadata, "participantNodeIds", "[]"));
        values.put("agentRole", value(metadata, "agentRole", "PARTICIPANT"));
        values.put("collaborationPolicy", value(metadata, "collaborationPolicy", "{}"));
        values.put("capabilities", value(metadata, "capabilities", "unknown"));
        values.put("permissionMode", value(metadata, "permissionMode", "standard"));
        values.put("reactMode", firstValue(react, "mode", null, "ReAct"));
        values.put("maxSteps", firstValue(react, "maxSteps", null, "unknown"));
        values.put("commandWhitelist", firstValue(react, "commandWhitelist", null, "[]"));
        values.put("stepTimeoutMs", firstValue(react, "stepTimeoutMs", null, "unknown"));
        values.put("taskTimeoutMs", firstValue(react, "taskTimeoutMs", null, "unknown"));
        values.put("auditLevel", firstValue(react, "auditLevel", null, "FULL"));
        values.put("allowAi", firstValue(react, "allowAi", null, "true"));
        values.put("collaborationCli", collaborationCli());
        return values;
    }

    private String collaborationCli() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "ccrelay-cli.cmd" : "ccrelay-cli";
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }

    private String firstValue(Map<String, Object> source, String key, Object fallback, String defaultValue) {
        Object value = source.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            value = fallback;
        }
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private String value(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata.get(key);
        if (value instanceof List<?> list) {
            return list.toString();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            return String.valueOf(value);
        }
        return fallback;
    }
}
