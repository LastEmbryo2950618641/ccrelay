package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RelayRegisterResponse {
    private String auditId;
    private String nodeId;
    private String status;
    private Boolean accepted;
    private String registerTime;
}

