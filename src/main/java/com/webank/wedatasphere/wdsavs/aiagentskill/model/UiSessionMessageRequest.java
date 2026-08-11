package com.webank.wedatasphere.wdsavs.aiagentskill.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class UiSessionMessageRequest {

    private String content;
    private List<String> targetNodeIds = new ArrayList<>();
    private String sourceNodeId;
    private String collaborationMode = "DISCUSSION";
    private Map<String, Object> collaborationPolicy = new LinkedHashMap<>();
}
