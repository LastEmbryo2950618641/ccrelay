package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.A2aAgentCard;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aAgentCapabilities;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiCapabilityCatalogSnapshot;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiMcpToolDefinition;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSkillDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AiCapabilityCatalogService {

    private static final String MCP_SERVER_NAME = "ccrelay-center";

    private final Map<String, AiSkillDefinition> skillRegistry = new ConcurrentHashMap<>();
    private final Map<String, AiMcpToolDefinition> mcpToolRegistry = new ConcurrentHashMap<>();

    public AiCapabilityCatalogService() {
        registerSkill(builtInClaudeCodeRelaySkill());
        registerSkill(builtInModelRelaySkill());
        registerSkill(builtInA2aBridgeSkill());
        registerMcpTool(builtInClaudeCodeChatTool());
        registerMcpTool(builtInModelChatTool());
        registerMcpTool(builtInA2aMessageSendTool());
        registerMcpTool(builtInAgentCardTool());
    }

    public AiCapabilityCatalogSnapshot snapshot() {
        AiCapabilityCatalogSnapshot snapshot = new AiCapabilityCatalogSnapshot();
        snapshot.setGeneratedAt(System.currentTimeMillis());
        snapshot.setAgentCard(agentCard());
        snapshot.setSkills(listSkills());
        snapshot.setMcpTools(listMcpTools());
        return snapshot;
    }

    public List<AiSkillDefinition> listSkills() {
        return skillRegistry.values().stream()
                .map(this::copySkill)
                .sorted(Comparator.comparing(AiSkillDefinition::getId, Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
    }

    public List<AiMcpToolDefinition> listMcpTools() {
        return mcpToolRegistry.values().stream()
                .map(this::copyMcpTool)
                .sorted(Comparator.comparing(AiMcpToolDefinition::getId, Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
    }

    public AiSkillDefinition registerSkill(AiSkillDefinition definition) {
        validateSkill(definition);
        AiSkillDefinition normalized = normalizeSkill(definition);
        skillRegistry.put(normalized.getId(), normalized);
        return copySkill(normalized);
    }

    public AiMcpToolDefinition registerMcpTool(AiMcpToolDefinition definition) {
        validateMcpTool(definition);
        AiMcpToolDefinition normalized = normalizeMcpTool(definition);
        mcpToolRegistry.put(normalized.getId(), normalized);
        return copyMcpTool(normalized);
    }

    public A2aAgentCard agentCard() {
        A2aAgentCard card = new A2aAgentCard();
        card.setName("wdsavs-cc");
        card.setDescription("CC Relay core relay and A2A bridge");
        card.setUrl("/api/ai/a2a/message/send");
        card.setProtocolVersion("0.3.0");
        card.setVersion("0.1.1");
        card.setPreferredTransport("JSONRPC");
        A2aAgentCapabilities capabilities = new A2aAgentCapabilities();
        capabilities.setStreaming(true);
        capabilities.setRemoteRelay(true);
        card.setCapabilities(capabilities);
        card.setDefaultInputModes(List.of("text", "application/json"));
        card.setDefaultOutputModes(List.of("text", "application/json"));
        card.setSupportedInputModes(List.of("text", "application/json"));
        card.setSupportedOutputModes(List.of("text", "application/json"));
        card.setSkills(listSkills().stream()
                .filter(skill -> Boolean.TRUE.equals(skill.getEnabled()) && Boolean.TRUE.equals(skill.getExposedInAgentCard()))
                .map(AiSkillDefinition::toAgentSkill)
                .collect(Collectors.toList()));
        card.setEndpoints(new LinkedHashMap<>(Map.of(
                "messageSend", "/api/ai/a2a/message/send",
                "claudeCodeChat", "/api/ai/claude-code/chat",
                "modelChat", "/api/ai/model-chat",
                "agentCard", "/api/ai/a2a/agent-card",
                "wellKnownAgent", "/.well-known/agent.json",
                "catalog", "/api/ai/catalog"
        )));
        return card;
    }

    private void validateSkill(AiSkillDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("AiSkillDefinition is required");
        }
        if (isBlank(definition.getId())) {
            throw new IllegalArgumentException("AiSkillDefinition.id is required");
        }
        if (isBlank(definition.getName())) {
            throw new IllegalArgumentException("AiSkillDefinition.name is required");
        }
    }

    private void validateMcpTool(AiMcpToolDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("AiMcpToolDefinition is required");
        }
        if (isBlank(definition.getId())) {
            throw new IllegalArgumentException("AiMcpToolDefinition.id is required");
        }
        if (isBlank(definition.getName())) {
            throw new IllegalArgumentException("AiMcpToolDefinition.name is required");
        }
    }

    private AiSkillDefinition normalizeSkill(AiSkillDefinition definition) {
        AiSkillDefinition normalized = copySkill(definition);
        if (normalized.getTags() == null) {
            normalized.setTags(new ArrayList<>());
        }
        if (normalized.getInputModes() == null) {
            normalized.setInputModes(new ArrayList<>());
        }
        if (normalized.getOutputModes() == null) {
            normalized.setOutputModes(new ArrayList<>());
        }
        if (normalized.getEnabled() == null) {
            normalized.setEnabled(true);
        }
        if (normalized.getExposedInAgentCard() == null) {
            normalized.setExposedInAgentCard(true);
        }
        if (isBlank(normalized.getSource())) {
            normalized.setSource("USER_REGISTERED");
        }
        return normalized;
    }

    private AiMcpToolDefinition normalizeMcpTool(AiMcpToolDefinition definition) {
        AiMcpToolDefinition normalized = copyMcpTool(definition);
        if (normalized.getTags() == null) {
            normalized.setTags(new ArrayList<>());
        }
        if (normalized.getEnabled() == null) {
            normalized.setEnabled(true);
        }
        if (isBlank(normalized.getSource())) {
            normalized.setSource("USER_REGISTERED");
        }
        return normalized;
    }

    private AiSkillDefinition builtInClaudeCodeRelaySkill() {
        AiSkillDefinition skill = new AiSkillDefinition();
        skill.setId("claude-code-relay");
        skill.setName("Claude Code Relay");
        skill.setDescription("Relay CC Relay thinking requests to a lightweight remote Claude Code service");
        skill.setCategory("relay");
        skill.setEndpoint("/api/ai/claude-code/chat");
        skill.setSource("BUILTIN");
        skill.setTags(List.of("claude-code", "remote-relay", "wdsavs-cc"));
        skill.setInputModes(List.of("text", "application/json"));
        skill.setOutputModes(List.of("text", "application/json"));
        return skill;
    }

    private AiSkillDefinition builtInA2aBridgeSkill() {
        AiSkillDefinition skill = new AiSkillDefinition();
        skill.setId("a2a-json-rpc-bridge");
        skill.setName("A2A JSON-RPC Bridge");
        skill.setDescription("Accept A2A message/send requests and normalize responses from CC Relay");
        skill.setCategory("bridge");
        skill.setEndpoint("/api/ai/a2a/message/send");
        skill.setSource("BUILTIN");
        skill.setTags(List.of("a2a", "json-rpc", "agent-card"));
        skill.setInputModes(List.of("application/json"));
        skill.setOutputModes(List.of("application/json"));
        return skill;
    }

    private AiSkillDefinition builtInModelRelaySkill() {
        AiSkillDefinition skill = new AiSkillDefinition();
        skill.setId("ai-model-chat-relay");
        skill.setName("AI Model Chat Relay");
        skill.setDescription("Relay chat requests using database maintained model base URL and API key");
        skill.setCategory("relay");
        skill.setEndpoint("/api/ai/model-chat");
        skill.setSource("BUILTIN");
        skill.setTags(List.of("ai-model", "relay", "chat"));
        skill.setInputModes(List.of("text", "application/json"));
        skill.setOutputModes(List.of("text", "application/json"));
        return skill;
    }

    private AiMcpToolDefinition builtInClaudeCodeChatTool() {
        AiMcpToolDefinition tool = new AiMcpToolDefinition();
        tool.setId("mcp.ai.claude-code-chat");
        tool.setName("Claude Code Chat");
        tool.setDescription("Dispatch a Claude Code relay request through CC Relay");
        tool.setServerName(MCP_SERVER_NAME);
        tool.setToolName("claude-code-chat");
        tool.setEndpoint("/api/ai/claude-code/chat");
        tool.setTransport("HTTP");
        tool.setSource("BUILTIN");
        tool.setTags(List.of("claude-code", "relay", "chat"));
        return tool;
    }

    private AiMcpToolDefinition builtInModelChatTool() {
        AiMcpToolDefinition tool = new AiMcpToolDefinition();
        tool.setId("mcp.ai.model-chat");
        tool.setName("AI Model Chat");
        tool.setDescription("Dispatch a chat request through a database maintained model configuration");
        tool.setServerName(MCP_SERVER_NAME);
        tool.setToolName("ai-model-chat");
        tool.setEndpoint("/api/ai/model-chat");
        tool.setTransport("HTTP");
        tool.setSource("BUILTIN");
        tool.setTags(List.of("ai-model", "chat"));
        return tool;
    }

    private AiMcpToolDefinition builtInA2aMessageSendTool() {
        AiMcpToolDefinition tool = new AiMcpToolDefinition();
        tool.setId("mcp.ai.a2a-message-send");
        tool.setName("A2A Message Send");
        tool.setDescription("Accept an A2A JSON-RPC message/send request");
        tool.setServerName(MCP_SERVER_NAME);
        tool.setToolName("a2a-message-send");
        tool.setEndpoint("/api/ai/a2a/message/send");
        tool.setTransport("HTTP");
        tool.setSource("BUILTIN");
        tool.setTags(List.of("a2a", "json-rpc"));
        return tool;
    }

    private AiMcpToolDefinition builtInAgentCardTool() {
        AiMcpToolDefinition tool = new AiMcpToolDefinition();
        tool.setId("mcp.ai.agent-card");
        tool.setName("Agent Card Discovery");
        tool.setDescription("Read the CC Relay A2A agent card and capability catalog");
        tool.setServerName(MCP_SERVER_NAME);
        tool.setToolName("agent-card");
        tool.setEndpoint("/api/ai/a2a/agent-card");
        tool.setTransport("HTTP");
        tool.setSource("BUILTIN");
        tool.setTags(List.of("agent-card", "discovery"));
        return tool;
    }

    private AiSkillDefinition copySkill(AiSkillDefinition definition) {
        AiSkillDefinition copy = new AiSkillDefinition();
        copy.setId(definition.getId());
        copy.setName(definition.getName());
        copy.setDescription(definition.getDescription());
        copy.setCategory(definition.getCategory());
        copy.setEndpoint(definition.getEndpoint());
        copy.setSource(definition.getSource());
        copy.setEnabled(definition.getEnabled());
        copy.setExposedInAgentCard(definition.getExposedInAgentCard());
        copy.setTags(definition.getTags() == null ? new ArrayList<>() : new ArrayList<>(definition.getTags()));
        copy.setInputModes(definition.getInputModes() == null ? new ArrayList<>() : new ArrayList<>(definition.getInputModes()));
        copy.setOutputModes(definition.getOutputModes() == null ? new ArrayList<>() : new ArrayList<>(definition.getOutputModes()));
        return copy;
    }

    private AiMcpToolDefinition copyMcpTool(AiMcpToolDefinition definition) {
        AiMcpToolDefinition copy = new AiMcpToolDefinition();
        copy.setId(definition.getId());
        copy.setName(definition.getName());
        copy.setDescription(definition.getDescription());
        copy.setServerName(definition.getServerName());
        copy.setToolName(definition.getToolName());
        copy.setEndpoint(definition.getEndpoint());
        copy.setTransport(definition.getTransport());
        copy.setSource(definition.getSource());
        copy.setEnabled(definition.getEnabled());
        copy.setTags(definition.getTags() == null ? new ArrayList<>() : new ArrayList<>(definition.getTags()));
        return copy;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
