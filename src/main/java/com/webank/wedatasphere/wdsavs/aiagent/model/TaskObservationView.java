package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class TaskObservationView {
    private String taskId;
    private String targetNodeId;
    private String status;
    private String currentStage;
    private String observationSource;
    private AiTaskView task;
    private List<AiTaskEventView> events = new ArrayList<>();
    private Map<String, Object> controlState = new LinkedHashMap<>();
    private Map<String, Object> heartbeat = new LinkedHashMap<>();
    private Map<String, Object> deploymentProgress = new LinkedHashMap<>();
    private TaskObservationWindowView window = new TaskObservationWindowView();
    private Boolean truncated = Boolean.FALSE;
    private String observationTime;
    private String errorCode;
    private String errorMessage;
    private String authorizationScope;
}
