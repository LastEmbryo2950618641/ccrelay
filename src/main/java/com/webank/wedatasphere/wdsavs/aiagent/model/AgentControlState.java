package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AgentControlState {
    private String sessionId;
    private String taskId;
    private String targetNodeId;
    private String grantId;
    private Boolean stopRequested = false;
    private List<Map<String, Object>> injectedPrompts = new ArrayList<>();
    private Map<String, Object> policyPatch = new LinkedHashMap<>();
    private String updateTime;
}
