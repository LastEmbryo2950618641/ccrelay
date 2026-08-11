package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class TaskObservationBatchView {
    private String parentTaskId;
    private List<TaskObservationView> observations = new ArrayList<>();
    private Map<String, Object> summary = new LinkedHashMap<>();
    private Boolean truncated = Boolean.FALSE;
    private String observationTime;
}
