package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class AiTaskStatusView {
    private String taskId;
    private String status;
    private String currentStage;
    private Integer progress;
    private String lastEventTime;
}
