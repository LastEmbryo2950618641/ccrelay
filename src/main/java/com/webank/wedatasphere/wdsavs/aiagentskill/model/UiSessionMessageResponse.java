package com.webank.wedatasphere.wdsavs.aiagentskill.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class UiSessionMessageResponse {

    private String sessionId;
    private String messageEventId;
    private Integer acceptedCount;
    private String collaborationMode;
    private String coordinatorNodeId;
    private Long coordinatorEpoch;
    private List<Map<String, Object>> submissions = new ArrayList<>();
}
