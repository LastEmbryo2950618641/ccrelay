package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeployReportResponse {
    private Boolean accepted;
    private String nextAction;
}
