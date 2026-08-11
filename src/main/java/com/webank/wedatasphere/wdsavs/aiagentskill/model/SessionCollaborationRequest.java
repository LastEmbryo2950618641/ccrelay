package com.webank.wedatasphere.wdsavs.aiagentskill.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class SessionCollaborationRequest {

    private String collaborationMode;
    private List<String> participantNodeIds = new ArrayList<>();
    private Map<String, Object> collaborationPolicy = new LinkedHashMap<>();
}
