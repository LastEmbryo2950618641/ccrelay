package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class RelayHeartbeatResponse {
    private String auditId;
    private String nodeId;
    private Boolean accepted;
    private Long nextHeartbeatAfterMs;
    private String nodeStatus;
    private Map<String, Object> loadSummary = new LinkedHashMap<>();

    public RelayHeartbeatResponse(String nodeId, Boolean accepted, Long nextHeartbeatAfterMs) {
        this.nodeId = nodeId;
        this.accepted = accepted;
        this.nextHeartbeatAfterMs = nextHeartbeatAfterMs;
    }
}
