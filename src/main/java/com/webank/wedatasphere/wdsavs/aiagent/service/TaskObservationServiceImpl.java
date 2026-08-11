package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayHeartbeatEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskEventView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationBatchView;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationQuery;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationView;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationWindowView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayHeartbeatRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class TaskObservationServiceImpl implements TaskObservationService {

    private static final String DEFAULT_OBSERVATION_SOURCE = "CENTER_LOCAL";
    private static final String REMOTE_OBSERVATION_SOURCE = "REMOTE_RELAY";
    private static final String FALLBACK_OBSERVATION_SOURCE = "CENTER_FALLBACK";

    private final AiTaskLifecycleService taskLifecycleService;
    private final AiTaskEventService taskEventService;
    private final AiRelayHeartbeatService heartbeatService;
    private final AiRelayRegistryService relayRegistryService;
    private final AiTaskRepository taskRepository;
    private final AiRelayHeartbeatRepository relayHeartbeatRepository;
    private final RuntimeConfigService runtimeConfigService;
    private final A2aPayloadPolicyService payloadPolicyService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TaskObservationServiceImpl(AiTaskLifecycleService taskLifecycleService,
                                      AiTaskEventService taskEventService,
                                      AiRelayHeartbeatService heartbeatService,
                                      AiRelayRegistryService relayRegistryService,
                                      AiTaskRepository taskRepository,
                                      AiRelayHeartbeatRepository relayHeartbeatRepository,
                                      RuntimeConfigService runtimeConfigService,
                                      A2aPayloadPolicyService payloadPolicyService,
                                      RestTemplate restTemplate) {
        this.taskLifecycleService = taskLifecycleService;
        this.taskEventService = taskEventService;
        this.heartbeatService = heartbeatService;
        this.relayRegistryService = relayRegistryService;
        this.taskRepository = taskRepository;
        this.relayHeartbeatRepository = relayHeartbeatRepository;
        this.runtimeConfigService = runtimeConfigService;
        this.payloadPolicyService = payloadPolicyService;
        this.restTemplate = restTemplate;
    }

    @Override
    public TaskObservationView observe(TaskObservationQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("Observation query is required");
        }
        if (hasAny(query.getTaskIds()) || notBlank(query.getParentTaskId())) {
            TaskObservationBatchView batchView = observeBatch(query);
            if (batchView.getObservations().isEmpty()) {
                throw new IllegalArgumentException("No task matched observation query");
            }
            return batchView.getObservations().get(0);
        }
        String taskId = trim(query.getTaskId());
        if (taskId == null) {
            throw new IllegalArgumentException("taskId is required");
        }
        AiTaskView taskView = taskLifecycleService.getTask(taskId);
        TaskObservationView remoteObservation = tryRemoteObservation(taskView, query);
        if (remoteObservation != null) {
            return remoteObservation;
        }
        return buildLocalObservation(taskView, query, DEFAULT_OBSERVATION_SOURCE, null, null);
    }

    @Override
    public TaskObservationBatchView observeBatch(TaskObservationQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("Observation query is required");
        }
        List<String> taskIds = new ArrayList<>();
        if (hasAny(query.getTaskIds())) {
            for (String taskId : query.getTaskIds()) {
                if (notBlank(taskId)) {
                    taskIds.add(taskId.trim());
                }
            }
        }
        if (notBlank(query.getParentTaskId())) {
            List<AiTaskEntity> childTasks = taskRepository.findByParentTaskIdOrderByCreateTimeAsc(query.getParentTaskId().trim());
            if (childTasks != null) {
                for (AiTaskEntity entity : childTasks) {
                    if (entity != null && notBlank(entity.getTaskId())) {
                        taskIds.add(entity.getTaskId());
                    }
                }
            }
            if (taskIds.isEmpty()) {
                taskIds.add(query.getParentTaskId().trim());
            }
        }
        Set<String> deduplicated = new LinkedHashSet<>(taskIds);
        TaskObservationBatchView batchView = new TaskObservationBatchView();
        batchView.setParentTaskId(trim(query.getParentTaskId()));
        batchView.setObservationTime(String.valueOf(System.currentTimeMillis()));
        List<TaskObservationView> observations = new ArrayList<>();
        for (String taskId : deduplicated) {
            try {
                AiTaskView taskView = taskLifecycleService.getTask(taskId);
                TaskObservationView remoteObservation = tryRemoteObservation(taskView, query);
                observations.add(remoteObservation == null
                        ? buildLocalObservation(taskView, query, DEFAULT_OBSERVATION_SOURCE, null, null)
                        : remoteObservation);
            } catch (Exception error) {
                TaskObservationView failed = new TaskObservationView();
                failed.setTaskId(taskId);
                failed.setObservationSource(FALLBACK_OBSERVATION_SOURCE);
                failed.setObservationTime(String.valueOf(System.currentTimeMillis()));
                failed.setErrorMessage(error.getMessage());
                observations.add(failed);
            }
        }
        batchView.setObservations(observations);
        batchView.setSummary(buildSummary(observations));
        batchView.setTruncated(observations.stream().anyMatch(view -> Boolean.TRUE.equals(view.getTruncated())));
        return batchView;
    }

    private TaskObservationView tryRemoteObservation(AiTaskView taskView, TaskObservationQuery query) {
        if (taskView == null || isBlank(taskView.getTargetNodeId())) {
            return null;
        }
        RelayNodeView nodeView;
        try {
            nodeView = relayRegistryService.getNode(taskView.getTargetNodeId());
        } catch (Exception ignored) {
            return null;
        }
        if (nodeView == null || isBlank(nodeView.getRelayEndpoint())) {
            return null;
        }
        String remoteEndpoint = remoteObservationEndpoint(nodeView.getRelayEndpoint(), taskView.getTaskId());
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(remoteEndpoint)
                .queryParam("taskId", taskView.getTaskId())
                .queryParam("sessionId", taskView.getSessionId())
                .queryParam("targetNodeId", taskView.getTargetNodeId())
                .queryParam("sinceSequenceNo", query.getSinceSequenceNo())
                .queryParam("sinceCreatedTimeMs", query.getSinceCreatedTimeMs())
                .queryParam("lastMs", query.getLastMs())
                .queryParam("limit", query.getLimit())
                .queryParam("tailLines", query.getTailLines())
                .queryParam("maxBytes", query.getMaxBytes())
                .queryParam("perEventMaxBytes", query.getPerEventMaxBytes())
                .queryParam("authorizationScope", "A2A_TASK_OBSERVE");
        appendGrantParams(builder, taskView);
        try {
            return restTemplate.getForObject(builder.toUriString(), TaskObservationView.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String remoteObservationEndpoint(String relayEndpoint, String taskId) {
        String normalized = trim(relayEndpoint);
        if (normalized == null) {
            throw new IllegalArgumentException("relayEndpoint is required");
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String observationPath = "/api/ai/a2a/tasks/" + encode(taskId) + "/observation";
        int a2aIndex = normalized.indexOf("/api/ai/a2a");
        if (a2aIndex >= 0) {
            return normalized.substring(0, a2aIndex) + observationPath;
        }
        int chatIndex = normalized.indexOf("/api/ai/remote-cc/chat");
        if (chatIndex >= 0) {
            return normalized.substring(0, chatIndex) + observationPath;
        }
        try {
            URI uri = URI.create(normalized);
            if (uri.getScheme() != null && uri.getHost() != null) {
                return uri.getScheme() + "://" + uri.getAuthority() + observationPath;
            }
        } catch (Exception ignored) {
        }
        return normalized + observationPath;
    }

    private void appendGrantParams(UriComponentsBuilder builder, AiTaskView taskView) {
        Map<String, Object> authorizationContext = new LinkedHashMap<>();
        if (taskView != null && notBlank(taskView.getTaskId())) {
            taskRepository.findByTaskId(taskView.getTaskId().trim())
                    .map(AiTaskEntity::getRequestPayloadJson)
                    .map(this::readMap)
                    .ifPresent(authorizationContext::putAll);
        }
        if (taskView != null && taskView.getResult() != null) {
            authorizationContext.putAll(taskView.getResult());
        }
        for (String key : List.of("grantId", "signedToken", "expiresAt", "sourceNodeId")) {
            Object value = authorizationContext.get(key);
            if (value != null) {
                builder.queryParam(key, String.valueOf(value));
            }
        }
        Object relayGrant = authorizationContext.get("relayGrant");
        if (relayGrant instanceof Map<?, ?> grantMap) {
            for (String key : List.of("grantId", "signedToken", "expiresAt", "sourceNodeId", "targetNodeId", "sessionId")) {
                Object value = grantMap.get(key);
                if (value != null) {
                    builder.queryParam(key, String.valueOf(value));
                }
            }
        }
    }

    private TaskObservationView buildLocalObservation(AiTaskView taskView,
                                                      TaskObservationQuery query,
                                                      String observationSource,
                                                      RelayNodeView remoteNodeView,
                                                      AiRelayHeartbeatEntity latestHeartbeat) {
        TaskObservationView observation = new TaskObservationView();
        observation.setTaskId(taskView.getTaskId());
        observation.setTargetNodeId(taskView.getTargetNodeId());
        observation.setStatus(taskView.getStatus());
        observation.setCurrentStage(taskView.getCurrentStage());
        observation.setObservationSource(observationSource);
        observation.setAuthorizationScope(trimOrDefault(query == null ? null : query.getAuthorizationScope(), "A2A_TASK_OBSERVE"));
        observation.setTask(normalizeTask(taskView));
        observation.setEvents(normalizeEvents(taskView.getTaskId(), query));
        observation.setControlState(extractControlState(taskView));
        observation.setHeartbeat(buildHeartbeat(taskView, remoteNodeView, latestHeartbeat));
        observation.setDeploymentProgress(extractDeploymentProgress(taskView));
        observation.setWindow(buildWindow(query, observation.getEvents().size()));
        observation.setObservationTime(String.valueOf(System.currentTimeMillis()));
        observation.setTruncated(Boolean.TRUE.equals(observation.getWindow().getTruncated()));
        TaskObservationView trimmed = trimObservation(observation, query);
        trimmed.getWindow().setReturnedCount(trimmed.getEvents() == null ? 0 : trimmed.getEvents().size());
        trimmed.getWindow().setTruncated(Boolean.TRUE.equals(trimmed.getTruncated()));
        return trimmed;
    }

    private TaskObservationView trimObservation(TaskObservationView observation, TaskObservationQuery query) {
        long maxBytes = effectiveMaxBytes(query);
        if (serializedSize(observation) <= maxBytes) {
            return observation;
        }
        List<AiTaskEventView> events = new ArrayList<>(observation.getEvents());
        while (!events.isEmpty() && serializedSize(observation) > maxBytes) {
            events.remove(events.size() - 1);
            observation.setEvents(new ArrayList<>(events));
            observation.getWindow().setReturnedCount(events.size());
            observation.getWindow().setTruncated(Boolean.TRUE);
            observation.setTruncated(Boolean.TRUE);
        }
        return observation;
    }

    private List<AiTaskEventView> normalizeEvents(String taskId, TaskObservationQuery query) {
        int limit = effectiveLimit(query);
        Long sinceSequenceNo = query == null ? null : query.getSinceSequenceNo();
        Long sinceCreatedTimeMs = resolveSinceCreatedTimeMs(query);
        List<AiTaskEventView> events = taskEventService.listEvents(taskId, sinceSequenceNo, sinceCreatedTimeMs, limit);
        List<String> eventTypes = normalizedSet(query == null ? null : query.getEventTypes());
        if (!eventTypes.isEmpty()) {
            events = events.stream()
                    .filter(event -> eventTypes.contains(trimUpper(event.getEventType())))
                    .toList();
        }
        List<AiTaskEventView> normalized = new ArrayList<>();
        long maxBytesPerEvent = effectivePerEventMaxBytes(query);
        int tailLines = effectiveTailLines(query);
        for (AiTaskEventView event : events) {
            normalized.add(normalizeEvent(event, maxBytesPerEvent, tailLines));
        }
        return normalized;
    }

    private AiTaskEventView normalizeEvent(AiTaskEventView event, long maxBytesPerEvent, int tailLines) {
        if (event == null) {
            return null;
        }
        AiTaskEventView normalized = new AiTaskEventView();
        normalized.setEventId(event.getEventId());
        normalized.setTaskId(event.getTaskId());
        normalized.setSessionId(event.getSessionId());
        normalized.setRequestId(event.getRequestId());
        normalized.setTraceId(event.getTraceId());
        normalized.setAuditId(event.getAuditId());
        normalized.setAgentRunId(event.getAgentRunId());
        normalized.setEventType(event.getEventType());
        normalized.setSequenceNo(event.getSequenceNo());
        normalized.setCreatedTime(event.getCreatedTime());
        Map<String, Object> payload = payloadPolicyService.normalizeStructuredResult(event.getPayload());
        String serialized = serialize(payload);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > maxBytesPerEvent) {
            Map<String, Object> truncated = new LinkedHashMap<>();
            truncated.put("payloadTruncated", true);
            truncated.put("sizeBytes", serialized.getBytes(StandardCharsets.UTF_8).length);
            truncated.put("tail", tailText(serialized, tailLines));
            truncated.put("eventType", event.getEventType());
            truncated.put("sequenceNo", event.getSequenceNo());
            normalized.setPayload(truncated);
        } else {
            normalized.setPayload(payload);
        }
        return normalized;
    }

    private AiTaskView normalizeTask(AiTaskView taskView) {
        if (taskView == null) {
            return null;
        }
        AiTaskView normalized = new AiTaskView();
        normalized.setTaskId(taskView.getTaskId());
        normalized.setSessionId(taskView.getSessionId());
        normalized.setParentTaskId(taskView.getParentTaskId());
        normalized.setRequestId(taskView.getRequestId());
        normalized.setTraceId(taskView.getTraceId());
        normalized.setAuditId(taskView.getAuditId());
        normalized.setAgentRunId(taskView.getAgentRunId());
        normalized.setTaskType(taskView.getTaskType());
        normalized.setStatus(taskView.getStatus());
        normalized.setCurrentStage(taskView.getCurrentStage());
        normalized.setSourceNodeId(taskView.getSourceNodeId());
        normalized.setTargetNodeId(taskView.getTargetNodeId());
        normalized.setResult(payloadPolicyService.normalizeStructuredResult(taskView.getResult()));
        normalized.setErrorCode(taskView.getErrorCode());
        normalized.setErrorMessage(taskView.getErrorMessage());
        normalized.setStartTime(taskView.getStartTime());
        normalized.setEndTime(taskView.getEndTime());
        return normalized;
    }

    private Map<String, Object> extractControlState(AiTaskView taskView) {
        Map<String, Object> result = taskView == null ? Map.of() : taskView.getResult();
        if (result == null || result.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object controlState = result.get("controlState");
        if (controlState instanceof Map<?, ?> controlMap) {
            return toMap(controlMap);
        }
        Object latest = result.get("latestA2aControlState");
        if (latest instanceof Map<?, ?> latestMap) {
            return toMap(latestMap);
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of("latestA2aGrantId", "latestA2aStatus", "latestA2aStage", "latestA2aEventType")) {
            if (result.containsKey(key)) {
                compact.put(key, result.get(key));
            }
        }
        return compact;
    }

    private Map<String, Object> buildHeartbeat(AiTaskView taskView, RelayNodeView remoteNodeView, AiRelayHeartbeatEntity latestHeartbeat) {
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        if (remoteNodeView != null) {
            heartbeat.put("nodeId", remoteNodeView.getNodeId());
            heartbeat.put("status", remoteNodeView.getStatus());
            heartbeat.put("lastHeartbeatTime", remoteNodeView.getLastHeartbeatTime());
            heartbeat.put("environmentSummary", remoteNodeView.getEnvironmentSummary());
        }
        if (latestHeartbeat != null) {
            heartbeat.put("status", latestHeartbeat.getStatus());
            heartbeat.put("activeSessions", latestHeartbeat.getActiveSessions());
            heartbeat.put("cpuLoad", latestHeartbeat.getCpuLoad());
            heartbeat.put("memoryUsage", latestHeartbeat.getMemoryUsage());
            heartbeat.put("lastTaskTime", latestHeartbeat.getLastTaskTime());
            heartbeat.put("heartbeatTime", latestHeartbeat.getHeartbeatTime());
            heartbeat.put("detail", readMap(latestHeartbeat.getDetailJson()));
        } else if (taskView != null && notBlank(taskView.getTargetNodeId())) {
            try {
                relayRegistryService.getNode(taskView.getTargetNodeId());
                Optional<AiRelayHeartbeatEntity> heartbeatEntity = relayHeartbeatRepository.findTop1ByNodeIdOrderByHeartbeatTimeDesc(taskView.getTargetNodeId()).stream().findFirst();
                heartbeatEntity.ifPresent(entity -> heartbeat.putAll(buildHeartbeat(taskView, remoteNodeView, entity)));
            } catch (Exception ignored) {
                // fallback below
            }
        }
        if (heartbeat.isEmpty() && taskView != null) {
            heartbeat.put("taskTargetNodeId", taskView.getTargetNodeId());
            heartbeat.put("taskSourceNodeId", taskView.getSourceNodeId());
        }
        return heartbeat;
    }

    private TaskObservationWindowView buildWindow(TaskObservationQuery query, int returnedCount) {
        TaskObservationWindowView window = new TaskObservationWindowView();
        Long sinceCreatedTimeMs = resolveSinceCreatedTimeMs(query);
        window.setSinceSequenceNo(query == null ? null : query.getSinceSequenceNo());
        window.setSinceCreatedTimeMs(sinceCreatedTimeMs);
        window.setLastMs(query == null ? null : query.getLastMs());
        window.setLimit(effectiveLimit(query));
        window.setTailLines(effectiveTailLines(query));
        window.setMaxBytes(effectiveMaxBytes(query));
        window.setPerEventMaxBytes(effectivePerEventMaxBytes(query));
        window.setReturnedCount(returnedCount);
        window.setMatchedCount(returnedCount);
        window.setTruncated(Boolean.FALSE);
        return window;
    }

    private Map<String, Object> buildSummary(List<TaskObservationView> observations) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", observations == null ? 0 : observations.size());
        summary.put("running", countStatus(observations, "RUNNING"));
        summary.put("success", countStatus(observations, "SUCCESS"));
        summary.put("failed", countStatus(observations, "FAILED"));
        summary.put("cancelled", countStatus(observations, "CANCELLED"));
        summary.put("timeout", countStatus(observations, "TIMEOUT"));
        summary.put("unreachable", countSource(observations, FALLBACK_OBSERVATION_SOURCE));
        summary.put("overallProgressPercent", overallProgressPercent(observations));
        return summary;
    }

    private Map<String, Object> extractDeploymentProgress(AiTaskView taskView) {
        if (taskView == null || taskView.getResult() == null) {
            return new LinkedHashMap<>();
        }
        Object progress = taskView.getResult().get("deployProgress");
        if (!(progress instanceof Map<?, ?> progressMap)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        progressMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Integer overallProgressPercent(List<TaskObservationView> observations) {
        if (observations == null || observations.isEmpty()) {
            return 0;
        }
        long total = 0L;
        for (TaskObservationView observation : observations) {
            if (observation != null && "SUCCESS".equalsIgnoreCase(trim(observation.getStatus()))) {
                total += 100L;
                continue;
            }
            Object value = observation == null || observation.getDeploymentProgress() == null
                    ? null
                    : observation.getDeploymentProgress().get("progressPercent");
            if (value instanceof Number number) {
                total += Math.max(0L, Math.min(100L, number.longValue()));
            }
        }
        return (int) Math.round((double) total / observations.size());
    }

    private long effectiveMaxBytes(TaskObservationQuery query) {
        long defaultValue = runtimeConfigService.getLong("wdsavs.ai.observation.default-max-bytes", 65536L);
        return query == null || query.getMaxBytes() == null || query.getMaxBytes() <= 0L
                ? defaultValue
                : query.getMaxBytes();
    }

    private long effectivePerEventMaxBytes(TaskObservationQuery query) {
        long defaultValue = runtimeConfigService.getLong("wdsavs.ai.observation.per-event-max-bytes", 8192L);
        return query == null || query.getPerEventMaxBytes() == null || query.getPerEventMaxBytes() <= 0L
                ? defaultValue
                : Math.max(1L, query.getPerEventMaxBytes());
    }

    private int effectiveLimit(TaskObservationQuery query) {
        int defaultValue = runtimeConfigService.getInt("wdsavs.ai.observation.default-limit", 50);
        int maxLimit = runtimeConfigService.getInt("wdsavs.ai.observation.max-limit", 500);
        int requested = query == null || query.getLimit() == null || query.getLimit() <= 0 ? defaultValue : query.getLimit();
        return Math.min(requested, maxLimit);
    }

    private int effectiveTailLines(TaskObservationQuery query) {
        return query == null || query.getTailLines() == null || query.getTailLines() <= 0
                ? 100
                : Math.min(query.getTailLines(), 1000);
    }

    private Long resolveSinceCreatedTimeMs(TaskObservationQuery query) {
        if (query == null) {
            return null;
        }
        if (query.getSinceCreatedTimeMs() != null && query.getSinceCreatedTimeMs() > 0L) {
            return query.getSinceCreatedTimeMs();
        }
        if (query.getLastMs() != null && query.getLastMs() > 0L) {
            return Math.max(0L, System.currentTimeMillis() - query.getLastMs());
        }
        return null;
    }

    private int countStatus(List<TaskObservationView> observations, String status) {
        if (observations == null || status == null) {
            return 0;
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        int count = 0;
        for (TaskObservationView observation : observations) {
            if (observation != null && normalized.equals(trimUpper(observation.getStatus()))) {
                count++;
            }
        }
        return count;
    }

    private int countSource(List<TaskObservationView> observations, String source) {
        if (observations == null || source == null) {
            return 0;
        }
        int count = 0;
        for (TaskObservationView observation : observations) {
            if (observation != null && source.equals(trim(observation.getObservationSource()))) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> toMap(Map<?, ?> source) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (source == null) {
            return target;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                target.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return target;
    }

    private Map<String, Object> readMap(String json) {
        if (isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private long serializedSize(Object value) {
        return serialize(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private String tailText(String text, int tailLines) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\\R");
        if (tailLines <= 0 || lines.length <= tailLines) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = Math.max(0, lines.length - tailLines); i < lines.length; i++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        return builder.toString();
    }

    private boolean hasAny(List<String> values) {
        return values != null && values.stream().anyMatch(this::notBlank);
    }

    private List<String> normalizedSet(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (notBlank(value)) {
                normalized.add(trimUpper(value));
            }
        }
        return new ArrayList<>(normalized);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private String trimUpper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String encode(String value) {
        if (value == null) {
            return "";
        }
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
