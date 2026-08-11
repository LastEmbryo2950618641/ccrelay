package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiTaskCreateResponse {
    private String taskId;
    private String status;
    private Boolean accepted;
    private String requestId;
    private String traceId;
    private String auditId;
}
