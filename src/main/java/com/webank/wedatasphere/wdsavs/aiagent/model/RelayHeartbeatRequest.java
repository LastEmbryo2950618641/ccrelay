package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class RelayHeartbeatRequest {
    private String nodeId;
    private String status;
    private Integer activeSessions;
    private BigDecimal cpuLoad;
    private Long memoryUsage;
    private String lastTaskTime;
    private Map<String, Object> detail = new LinkedHashMap<>();
    private Map<String, Object> environmentSummary = new LinkedHashMap<>();
}
