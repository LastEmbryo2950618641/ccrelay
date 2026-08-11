package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class RelayHeartbeatScanResponse {
    private Integer scannedCount;
    private Integer availableCount;
    private Integer degradedCount;
    private Integer unavailableCount;
    private Long heartbeatIntervalMs;
    private Integer offlineThreshold;
    private Map<String, String> nodeStatuses = new LinkedHashMap<>();
}