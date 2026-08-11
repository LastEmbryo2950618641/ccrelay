package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiSkillDefinition {

    private String id;
    private String name;
    private String description;
    private String category;
    private String endpoint;
    private String source;
    private Boolean enabled = true;
    private Boolean exposedInAgentCard = true;
    private List<String> tags = new ArrayList<>();
    private List<String> inputModes = new ArrayList<>();
    private List<String> outputModes = new ArrayList<>();

    public A2aAgentSkill toAgentSkill() {
        A2aAgentSkill skill = new A2aAgentSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setDescription(description);
        skill.setTags(new ArrayList<>(tags));
        skill.setInputModes(new ArrayList<>(inputModes));
        skill.setOutputModes(new ArrayList<>(outputModes));
        return skill;
    }
}
