package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayHeartbeatEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayNodeEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatScanResponse;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayHeartbeatRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayNodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AiRelayHeartbeatServiceImpl implements AiRelayHeartbeatService {

    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final int DEFAULT_OFFLINE_THRESHOLD = 3;

    private final AiRelayHeartbeatRepository heartbeatRepository;
    private final AiRelayNodeRepository relayNodeRepository;
    private final AiAuditService auditService;
    private final AiRelayDeployService relayDeployService;
    private final long heartbeatIntervalMs;
    private final int offlineThreshold;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AiRelayHeartbeatServiceImpl(AiRelayHeartbeatRepository heartbeatRepository,
                                       AiRelayNodeRepository relayNodeRepository,
                                       AiAuditService auditService,
                                       @Lazy AiRelayDeployService relayDeployService) {
        this(heartbeatRepository,
                relayNodeRepository,
                auditService,
                relayDeployService,
                longConfig("wdsavs.ai.relay.heartbeat.interval-ms", "WDSAVS_AI_RELAY_HEARTBEAT_INTERVAL_MS", DEFAULT_HEARTBEAT_INTERVAL_MS),
                intConfig("wdsavs.ai.relay.heartbeat.offline-threshold", "WDSAVS_AI_RELAY_HEARTBEAT_OFFLINE_THRESHOLD", DEFAULT_OFFLINE_THRESHOLD));
    }

    AiRelayHeartbeatServiceImpl(AiRelayHeartbeatRepository heartbeatRepository,
                                AiRelayNodeRepository relayNodeRepository,
                                long heartbeatIntervalMs,
                                int offlineThreshold) {
        this(heartbeatRepository, relayNodeRepository, null, null, heartbeatIntervalMs, offlineThreshold);
    }

    AiRelayHeartbeatServiceImpl(AiRelayHeartbeatRepository heartbeatRepository,
                                AiRelayNodeRepository relayNodeRepository,
                                AiAuditService auditService,
                                AiRelayDeployService relayDeployService,
                                long heartbeatIntervalMs,
                                int offlineThreshold) {
        this.heartbeatRepository = heartbeatRepository;
        this.relayNodeRepository = relayNodeRepository;
        this.auditService = auditService;
        this.relayDeployService = relayDeployService;
        this.heartbeatIntervalMs = heartbeatIntervalMs <= 0 ? DEFAULT_HEARTBEAT_INTERVAL_MS : heartbeatIntervalMs;
        this.offlineThreshold = offlineThreshold <= 0 ? DEFAULT_OFFLINE_THRESHOLD : offlineThreshold;
    }

    @Override
    public RelayHeartbeatResponse heartbeat(RelayHeartbeatRequest request) {
        if (request == null || isBlank(request.getNodeId())) {
            throw new IllegalArgumentException("nodeId is required");
        }
        String now = String.valueOf(System.currentTimeMillis());
        AiRelayNodeEntity node = relayNodeRepository.findByNodeId(request.getNodeId())
                .orElseThrow(() -> new IllegalArgumentException("Relay node not registered: " + request.getNodeId()));
        String resolvedStatus = resolveHeartbeatStatus(node, request);
        node.setStatus(resolvedStatus);
        node.setEnvironmentSummaryJson(writeJson(mergeEnvironmentSummary(readJsonMap(node.getEnvironmentSummaryJson()), request.getEnvironmentSummary())));
        node.setLastHeartbeatTime(now);
        node.setUpdateTime(now);
        relayNodeRepository.save(node);

        Map<String, Object> loadSummary = buildLoadSummary(request);
        Map<String, Object> detail = buildHeartbeatDetail(request, resolvedStatus, loadSummary);
        AiRelayHeartbeatEntity heartbeat = new AiRelayHeartbeatEntity();
        heartbeat.setNodeId(request.getNodeId());
        heartbeat.setStatus(resolvedStatus);
        heartbeat.setActiveSessions(request.getActiveSessions());
        heartbeat.setCpuLoad(request.getCpuLoad());
        heartbeat.setMemoryUsage(request.getMemoryUsage());
        heartbeat.setLastTaskTime(request.getLastTaskTime());
        heartbeat.setDetailJson(writeJson(detail));
        heartbeat.setHeartbeatTime(now);
        heartbeatRepository.save(heartbeat);

        RelayHeartbeatResponse response = new RelayHeartbeatResponse(request.getNodeId(), true, heartbeatIntervalMs);
        response.setNodeStatus(resolvedStatus);
        response.setLoadSummary(loadSummary);
        response.setAuditId(recordNodeHeartbeatAudit(request, resolvedStatus, detail));
        if (relayDeployService != null && "AVAILABLE".equalsIgnoreCase(resolvedStatus)) {
            relayDeployService.autoCompleteReadyDeployments(request.getNodeId());
        }
        return response;
    }


    @Override
    public RelayHeartbeatScanResponse scanNodeAvailability() {
        RelayHeartbeatScanResponse response = new RelayHeartbeatScanResponse();
        response.setHeartbeatIntervalMs(heartbeatIntervalMs);
        response.setOfflineThreshold(offlineThreshold);
        int scanned = 0;
        int available = 0;
        int degraded = 0;
        int unavailable = 0;
        for (AiRelayNodeEntity node : relayNodeRepository.findAll()) {
            scanned++;
            boolean nodeAvailable = evaluateAvailability(node);
            String status = normalizeStatus(node.getStatus());
            response.getNodeStatuses().put(node.getNodeId(), status);
            if ("DEGRADED".equals(status)) {
                degraded++;
            } else if (nodeAvailable && "AVAILABLE".equals(status)) {
                available++;
            } else {
                unavailable++;
            }
        }
        response.setScannedCount(scanned);
        response.setAvailableCount(available);
        response.setDegradedCount(degraded);
        response.setUnavailableCount(unavailable);
        return response;
    }

    @Override
    public boolean isNodeAvailable(String nodeId) {
        if (isBlank(nodeId)) {
            return false;
        }
        return relayNodeRepository.findByNodeId(nodeId)
                .map(this::evaluateAvailability)
                .orElse(false);
    }

    private boolean evaluateAvailability(AiRelayNodeEntity node) {
        String status = normalizeStatus(node.getStatus());
        if ("STOPPED".equals(status) || "UNAVAILABLE".equals(status) || "DEPLOYING".equals(status) || "REGISTERING".equals(status)) {
            return false;
        }
        long ageMs = heartbeatAgeMs(node.getLastHeartbeatTime());
        if (ageMs < 0) {
            return false;
        }
        if (ageMs > heartbeatIntervalMs * offlineThreshold) {
            boolean transitioned = updateNodeStatus(node, "UNAVAILABLE");
            if (transitioned) {
                recordNodeUnavailableAudit(node, ageMs);
            }
            return false;
        }
        if (ageMs > heartbeatIntervalMs && "AVAILABLE".equals(status)) {
            updateNodeStatus(node, "DEGRADED");
            return true;
        }
        return "AVAILABLE".equals(status) || "DEGRADED".equals(status);
    }

    private boolean updateNodeStatus(AiRelayNodeEntity node, String status) {
        if (node == null || status == null || status.equalsIgnoreCase(node.getStatus())) {
            return false;
        }
        node.setStatus(status);
        node.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        relayNodeRepository.save(node);
        return true;
    }

    private String recordNodeHeartbeatAudit(RelayHeartbeatRequest request, String resolvedStatus, Map<String, Object> detail) {
        if (auditService == null) {
            return null;
        }
        return auditService.record(null, null, request.getNodeId(), null,
                AuditEventType.NODE_HEARTBEAT, resolvedStatus, detail, "NODE", request.getNodeId());
    }

    private void recordNodeUnavailableAudit(AiRelayNodeEntity node, long ageMs) {
        if (auditService == null) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("nodeId", node.getNodeId());
        detail.put("lastHeartbeatTime", node.getLastHeartbeatTime());
        detail.put("heartbeatAgeMs", ageMs);
        detail.put("heartbeatIntervalMs", heartbeatIntervalMs);
        detail.put("offlineThreshold", offlineThreshold);
        detail.put("status", node.getStatus());
        auditService.record(null, null, node.getNodeId(), null,
                AuditEventType.NODE_UNAVAILABLE, node.getStatus(), detail, "SYSTEM", "HEARTBEAT_MONITOR");
    }

    private String resolveHeartbeatStatus(AiRelayNodeEntity node, RelayHeartbeatRequest request) {
        if (!isBlank(request.getStatus())) {
            return request.getStatus().trim();
        }
        if (node != null && !isBlank(node.getStatus())) {
            return node.getStatus();
        }
        return "AVAILABLE";
    }

    private Map<String, Object> buildHeartbeatDetail(RelayHeartbeatRequest request, String resolvedStatus, Map<String, Object> loadSummary) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("nodeId", request.getNodeId());
        detail.put("status", resolvedStatus);
        detail.put("lastTaskTime", request.getLastTaskTime());
        detail.put("loadSummary", loadSummary);
        detail.put("detail", request.getDetail() == null ? Map.of() : request.getDetail());
        detail.put("environmentSummary", request.getEnvironmentSummary() == null ? Map.of() : request.getEnvironmentSummary());
        return detail;
    }

    private Map<String, Object> buildLoadSummary(RelayHeartbeatRequest request) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("activeSessions", request.getActiveSessions());
        summary.put("cpuLoad", request.getCpuLoad());
        summary.put("memoryUsage", request.getMemoryUsage());
        summary.put("lastTaskTime", request.getLastTaskTime());
        return summary;
    }

    private Map<String, Object> mergeEnvironmentSummary(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(existing);
        }
        if (incoming == null || incoming.isEmpty()) {
            return merged;
        }
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            Object existingValue = merged.get(entry.getKey());
            Object incomingValue = entry.getValue();
            if (existingValue instanceof Map<?, ?> existingMap && incomingValue instanceof Map<?, ?> incomingMap) {
                Map<String, Object> nested = new LinkedHashMap<>();
                for (Map.Entry<?, ?> nestedEntry : existingMap.entrySet()) {
                    if (nestedEntry.getKey() != null) {
                        nested.put(String.valueOf(nestedEntry.getKey()), nestedEntry.getValue());
                    }
                }
                for (Map.Entry<?, ?> nestedEntry : incomingMap.entrySet()) {
                    if (nestedEntry.getKey() != null) {
                        nested.put(String.valueOf(nestedEntry.getKey()), nestedEntry.getValue());
                    }
                }
                merged.put(entry.getKey(), nested);
            } else if (incomingValue != null) {
                merged.put(entry.getKey(), incomingValue);
            }
        }
        return merged;
    }

    private long heartbeatAgeMs(String lastHeartbeatTime) {
        long heartbeatTime = parseLong(lastHeartbeatTime);
        if (heartbeatTime <= 0) {
            return -1L;
        }
        return System.currentTimeMillis() - heartbeatTime;
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }

    private long parseLong(String value) {
        if (isBlank(value)) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize heartbeat detail", e);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static long longConfig(String propertyKey, String envKey, long defaultValue) {
        String value = textConfig(propertyKey, envKey);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static int intConfig(String propertyKey, String envKey, int defaultValue) {
        String value = textConfig(propertyKey, envKey);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String textConfig(String propertyKey, String envKey) {
        String property = System.getProperty(propertyKey);
        if (property != null) {
            return property;
        }
        return System.getenv(envKey);
    }
}
