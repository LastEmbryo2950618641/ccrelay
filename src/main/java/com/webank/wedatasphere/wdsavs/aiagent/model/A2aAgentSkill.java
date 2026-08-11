package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class A2aAgentSkill {

    private String id;
    private String name;
    private String description;
    private List<String> tags = new ArrayList<>();
    private List<String> inputModes = new ArrayList<>();
    private List<String> outputModes = new ArrayList<>();
}
