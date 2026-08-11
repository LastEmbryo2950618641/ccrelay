package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiCapabilityCatalogSnapshot {

    private long generatedAt;
    private A2aAgentCard agentCard;
    private List<AiSkillDefinition> skills = new ArrayList<>();
    private List<AiMcpToolDefinition> mcpTools = new ArrayList<>();
}
