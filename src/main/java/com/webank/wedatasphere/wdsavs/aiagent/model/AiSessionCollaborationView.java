package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AiSessionCollaborationView {

    private String sessionId;
    private String collaborationMode;
    private String coordinatorNodeId;
    private Long coordinatorEpoch;
    private List<String> participantNodeIds = new ArrayList<>();
    private Map<String, Object> collaborationPolicy = new LinkedHashMap<>();
}
