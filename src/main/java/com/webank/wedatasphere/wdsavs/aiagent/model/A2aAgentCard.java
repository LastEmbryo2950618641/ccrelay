package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class A2aAgentCard {

    private String name;
    private String description;
    private String url;
    private String protocolVersion;
    private String version;
    private String preferredTransport;
    private A2aAgentCapabilities capabilities = new A2aAgentCapabilities();
    private List<String> defaultInputModes = new ArrayList<>();
    private List<String> defaultOutputModes = new ArrayList<>();
    private List<String> supportedInputModes = new ArrayList<>();
    private List<String> supportedOutputModes = new ArrayList<>();
    private List<A2aAgentSkill> skills = new ArrayList<>();
    private Map<String, String> endpoints = new LinkedHashMap<>();
}
