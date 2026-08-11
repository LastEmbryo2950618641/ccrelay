package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TaskObservationQuery {
    private String taskId;
    private List<String> taskIds = new ArrayList<>();
    private String parentTaskId;
    private String targetNodeId;
    private String sessionId;
    private Long sinceSequenceNo;
    private Long sinceCreatedTimeMs;
    private Long lastMs;
    private Integer limit;
    private Integer tailLines;
    private Long maxBytes;
    private Long perEventMaxBytes;
    private List<String> include = new ArrayList<>();
    private List<String> eventTypes = new ArrayList<>();
    private String authorizationScope;
}
