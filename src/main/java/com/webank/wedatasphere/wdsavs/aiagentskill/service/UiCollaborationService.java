package com.webank.wedatasphere.wdsavs.aiagentskill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionCollaborationView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskEventView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.model.ReactExecutionPolicy;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import com.webank.wedatasphere.wdsavs.aiagent.service.A2aTaskService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayGrantService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayRegistryService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiSessionContextService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiSessionService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiSessionCollaborationService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiTaskEventService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiTaskLifecycleService;
import com.webank.wedatasphere.wdsavs.aiagent.service.TaskObservationService;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationQuery;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationView;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.UiSessionMessageRequest;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.UiSessionMessageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UiCollaborationService {

    private static final String DEFAULT_SOURCE_NODE_ID = "cc-center-ui";
    private static final List<String> UI_CAPABILITIES = List.of(
            "A2A_MESSAGE_SEND",
            "A2A_TASK_CREATE",
            "A2A_TASK_GET",
            "A2A_TASK_OBSERVE",
            "A2A_TASK_CANCEL");
    private static final List<String> UI_COMMAND_WHITELIST = List.of("ccrelay-cli", "ccrelay-cli.cmd");
    private static final List<String> TERMINAL_STATUSES = List.of("SUCCESS", "FAILED", "CANCELLED", "TIMEOUT");

    private final AiSessionService sessionService;
    private final AiSessionContextService contextService;
    private final AiRelayRegistryService relayRegistryService;
    private final AiRelayGrantService relayGrantService;
    private final AiTaskLifecycleService taskLifecycleService;
    private final AiTaskEventService taskEventService;
    private final A2aTaskService a2aTaskService;
    private final AiTaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private TaskObservationService taskObservationService;
    private AiSessionCollaborationService collaborationService;
    private SessionTitleService sessionTitleService;

    public UiCollaborationService(AiSessionService sessionService,
                                  AiSessionContextService contextService,
                                  AiRelayRegistryService relayRegistryService,
                                  AiRelayGrantService relayGrantService,
                                  AiTaskLifecycleService taskLifecycleService,
                                  AiTaskEventService taskEventService,
                                  A2aTaskService a2aTaskService,
                                  AiTaskRepository taskRepository,
                                  ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.contextService = contextService;
        this.relayRegistryService = relayRegistryService;
        this.relayGrantService = relayGrantService;
        this.taskLifecycleService = taskLifecycleService;
        this.taskEventService = taskEventService;
        this.a2aTaskService = a2aTaskService;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setTaskObservationService(TaskObservationService taskObservationService) {
        this.taskObservationService = taskObservationService;
    }

    @Autowired(required = false)
    public void setCollaborationService(AiSessionCollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    @Autowired(required = false)
    public void setSessionTitleService(SessionTitleService sessionTitleService) {
        this.sessionTitleService = sessionTitleService;
    }

    public UiSessionMessageResponse send(String sessionId, UiSessionMessageRequest request) {
        sessionService.validateSession(sessionId);
        String content = requireText(request == null ? null : request.getContent(), "content");
        List<String> targetNodeIds = targetNodeIds(request);
        String sourceNodeId = normalize(request == null ? null : request.getSourceNodeId(), DEFAULT_SOURCE_NODE_ID);
        Map<String, RelayNodeView> targetNodes = resolveAvailableNodes(targetNodeIds);
        AiSessionCollaborationView collaboration = collaborationService == null ? null
                : collaborationService.initialize(sessionId,
                request == null ? null : request.getCollaborationMode(), targetNodeIds,
                request == null ? null : request.getCollaborationPolicy());
        String messageEventId = appendUserMessage(sessionId, content, sourceNodeId, targetNodeIds);
        if (sessionTitleService != null && collaboration != null) {
            sessionTitleService.generateIfAbsentAsync(sessionId, collaboration.getCoordinatorNodeId());
        }

        List<Map<String, Object>> submissions = new ArrayList<>();
        for (String targetNodeId : targetNodeIds) {
            submissions.add(createRemoteTask(sessionId, content, sourceNodeId, targetNodeId,
                    targetNodes.get(targetNodeId), collaboration));
        }
        UiSessionMessageResponse response = new UiSessionMessageResponse();
        response.setSessionId(sessionId);
        response.setMessageEventId(messageEventId);
        response.setSubmissions(submissions);
        if (collaboration != null) {
            response.setCollaborationMode(collaboration.getCollaborationMode());
            response.setCoordinatorNodeId(collaboration.getCoordinatorNodeId());
            response.setCoordinatorEpoch(collaboration.getCoordinatorEpoch());
        }
        response.setAcceptedCount((int) submissions.stream()
                .filter(item -> "ACCEPTED".equals(item.get("status")))
                .count());
        return response;
    }

    public Map<String, Object> synchronize(String sessionId) {
        sessionService.validateSession(sessionId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (AiTaskEntity task : taskRepository.findBySessionIdOrderByCreateTimeDesc(sessionId)) {
            if (!"A2A_TASK".equalsIgnoreCase(task.getTaskType()) || isBlank(task.getTargetNodeId())) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", task.getTaskId());
            result.put("targetNodeId", task.getTargetNodeId());
            if (isTerminal(task.getStatus())) {
                result.put("status", "ALREADY_TERMINAL");
                result.put("task", task);
                result.put("persistedEventCount", snapshotRemoteEvents(task, task));
                results.add(result);
                continue;
            }
            try {
                Map<String, Object> context = readPayload(task.getRequestPayloadJson());
                context.put("sessionId", sessionId);
                context.put("targetNodeId", task.getTargetNodeId());
                context.put("parentTaskId", task.getTaskId());
                Object state = a2aTaskService.getTask(task.getTaskId(), context);
                result.put("status", "SYNCED");
                result.put("task", state);
                result.put("persistedEventCount", snapshotRemoteEvents(task, state));
            } catch (Exception error) {
                result.put("status", "FAILED");
                result.put("message", error.getMessage());
            }
            results.add(result);
        }
        return Map.of(
                "sessionId", sessionId,
                "taskCount", results.size(),
                "results", results,
                "synchronizedAt", String.valueOf(System.currentTimeMillis()));
    }

    private int snapshotRemoteEvents(AiTaskEntity task, Object state) {
        if (taskObservationService == null || task == null || isBlank(task.getTaskId())) {
            return 0;
        }
        TaskObservationQuery query = new TaskObservationQuery();
        query.setTaskId(task.getTaskId());
        query.setLimit(500);
        query.setMaxBytes(524288L);
        query.setPerEventMaxBytes(65536L);
        TaskObservationView observation;
        try {
            observation = taskObservationService.observe(query);
        } catch (Exception ignored) {
            return 0;
        }
        if (observation == null || !"REMOTE_RELAY".equalsIgnoreCase(observation.getObservationSource())
                || observation.getEvents() == null || observation.getEvents().isEmpty()) {
            return 0;
        }
        AiTaskEntity current = taskRepository.findByTaskId(task.getTaskId()).orElse(task);
        Map<String, Object> result = readPayload(current.getResultJson());
        LinkedHashSet<String> persistedIds = new LinkedHashSet<>();
        Object storedIds = result.get("remoteObservationEventIds");
        if (storedIds instanceof List<?> list) {
            list.forEach(value -> {
                if (value != null && !isBlank(String.valueOf(value))) {
                    persistedIds.add(String.valueOf(value));
                }
            });
        }
        long sequence = nextEventSequence(task.getTaskId());
        int persisted = 0;
        for (AiTaskEventView remoteEvent : observation.getEvents()) {
            if (remoteEvent == null) {
                continue;
            }
            String remoteEventId = remoteObservationEventId(task.getTaskId(), remoteEvent);
            if (!persistedIds.add(remoteEventId)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (remoteEvent.getPayload() != null) {
                payload.putAll(remoteEvent.getPayload());
            }
            payload.put("remoteObservationEventId", remoteEventId);
            payload.put("remoteObservationSequenceNo", remoteEvent.getSequenceNo());
            payload.put("remoteObservationEventType", remoteEvent.getEventType());
            payload.put("observationSource", "REMOTE_RELAY");
            taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), remoteEvent.getEventType(), sequence++, payload);
            persisted++;
        }
        if (persisted > 0) {
            result.put("remoteObservationEventIds", new ArrayList<>(persistedIds));
            result.put("remoteObservationLastSyncTime", String.valueOf(System.currentTimeMillis()));
            current.setResultJson(writeJson(result));
            current.setUpdateTime(String.valueOf(System.currentTimeMillis()));
            taskRepository.save(current);
        }
        return persisted;
    }

    private String remoteObservationEventId(String taskId, AiTaskEventView remoteEvent) {
        if (!isBlank(remoteEvent.getEventId())) {
            return remoteEvent.getEventId().trim();
        }
        if (remoteEvent.getSequenceNo() != null) {
            return taskId + ":sequence:" + remoteEvent.getSequenceNo();
        }
        String fingerprint = taskId + "|" + normalize(remoteEvent.getEventType(), "UNKNOWN") + "|"
                + normalize(remoteEvent.getCreatedTime(), "0") + "|" + writeJson(remoteEvent.getPayload());
        return taskId + ":derived:"
                + UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8));
    }

    private long nextEventSequence(String taskId) {
        List<AiTaskEventView> events = taskEventService.listEvents(taskId);
        if (events == null || events.isEmpty()) {
            return 1L;
        }
        return events.stream()
                .map(AiTaskEventView::getSequenceNo)
                .filter(value -> value != null)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L) + 1L;
    }

    private Map<String, Object> createRemoteTask(String sessionId,
                                                  String content,
                                                  String sourceNodeId,
                                                  String targetNodeId,
                                                  RelayNodeView targetNode,
                                                  AiSessionCollaborationView collaboration) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetNodeId", targetNodeId);
        String shadowTaskId = null;
        try {
            RelayAccessDecisionResponse grant = requestGrant(sessionId, sourceNodeId, targetNodeId);
            if (!"ALLOW".equalsIgnoreCase(grant.getDecision())) {
                throw new IllegalStateException("Relay access was not allowed: " + grant.getDecision());
            }
            String taskId = UUID.randomUUID().toString();
            shadowTaskId = taskId;
            String requestId = "ui-task:" + taskId;
            Map<String, Object> params = taskParams(sessionId, content, sourceNodeId, targetNodeId, targetNode,
                    grant, taskId, requestId, collaboration);
            createShadowTask(sessionId, sourceNodeId, targetNodeId, taskId, requestId, params);
            params.put("parentTaskId", taskId);

            A2aTaskCreateRequest createRequest = new A2aTaskCreateRequest();
            createRequest.setJsonrpc("2.0");
            createRequest.setId(requestId);
            createRequest.setMethod("tasks/create");
            createRequest.setParams(params);
            A2aTaskCreateResponse created = a2aTaskService.createTask(createRequest);
            result.put("status", "ACCEPTED");
            result.put("taskId", created.getResult().getOrDefault("taskId", taskId));
            result.put("grantId", grant.getGrantId());
        } catch (Exception error) {
            if (!isBlank(shadowTaskId)) {
                markShadowTaskFailed(shadowTaskId, error);
            }
            result.put("status", "FAILED");
            result.put("message", error.getMessage());
        }
        return result;
    }

    private void markShadowTaskFailed(String taskId, Exception error) {
        AiTaskEntity task = taskRepository.findByTaskId(taskId).orElse(null);
        if (task == null || isTerminal(task.getStatus())) {
            return;
        }
        String now = String.valueOf(System.currentTimeMillis());
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        task.setStatus("FAILED");
        task.setCurrentStage("REMOTE_A2A_CREATE_FAILED");
        task.setErrorCode("REMOTE_A2A_CREATE_FAILED");
        task.setErrorMessage(message);
        task.setEndTime(now);
        task.setUpdateTime(now);
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("status", "FAILED");
        failure.put("currentStage", task.getCurrentStage());
        failure.put("errorCode", task.getErrorCode());
        failure.put("errorMessage", message);
        task.setResultJson(writeJson(failure));
        taskRepository.save(task);
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "REMOTE_A2A_CREATE_FAILED",
                nextEventSequence(task.getTaskId()), failure);
    }

    private RelayAccessDecisionResponse requestGrant(String sessionId, String sourceNodeId, String targetNodeId) {
        RelayAccessRequest request = new RelayAccessRequest();
        request.setSessionId(sessionId);
        request.setRequestId("ui-grant:" + UUID.randomUUID());
        request.setSourceNodeId(sourceNodeId);
        request.setTargetNodeId(targetNodeId);
        request.setReason("CC Relay observer session message");
        request.setRequiredCapabilities(UI_CAPABILITIES);
        return relayGrantService.requestAccess(request);
    }

    private Map<String, Object> taskParams(String sessionId,
                                           String content,
                                           String sourceNodeId,
                                           String targetNodeId,
                                           RelayNodeView targetNode,
                                           RelayAccessDecisionResponse grant,
                                           String taskId,
                                           String requestId,
                                           AiSessionCollaborationView collaboration) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sessionId);
        params.put("taskId", taskId);
        params.put("requestId", requestId);
        params.put("idempotencyKey", requestId);
        params.put("sourceNodeId", sourceNodeId);
        params.put("targetNodeId", targetNodeId);
        params.put("targetRelayEndpoint", targetNode.getRelayEndpoint());
        params.put("grantId", grant.getGrantId());
        params.put("signedToken", grant.getSignedToken());
        params.put("expiresAt", grant.getExpiresAt());
        params.put("input", Map.of("prompt", content));
        params.put("messages", List.of(Map.of("role", "user", "content", content)));
        params.put("executionMode", ReactExecutionPolicy.DEFAULT_MODE);
        params.put("commandWhitelist", UI_COMMAND_WHITELIST);
        params.put("contextAlreadyAppended", Boolean.TRUE);
        params.put("allowCenterForwardFallback", Boolean.FALSE);
        if (collaboration != null) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("collaborationMode", collaboration.getCollaborationMode());
            metadata.put("coordinatorNodeId", collaboration.getCoordinatorNodeId());
            metadata.put("coordinatorEpoch", collaboration.getCoordinatorEpoch());
            metadata.put("participantNodeIds", collaboration.getParticipantNodeIds());
            metadata.put("collaborationPolicy", collaboration.getCollaborationPolicy());
            String agentRole = targetNodeId.equals(collaboration.getCoordinatorNodeId())
                    ? "COORDINATOR" : "PARTICIPANT";
            metadata.put("agentRole", agentRole);
            params.put("metadata", metadata);
            params.put("agentRole", agentRole);
        }
        ReactExecutionPolicy.fromParams(params).applyToParams(params);
        return params;
    }

    private void createShadowTask(String sessionId,
                                  String sourceNodeId,
                                  String targetNodeId,
                                  String taskId,
                                  String requestId,
                                  Map<String, Object> params) {
        AiTaskCreateRequest shadow = new AiTaskCreateRequest();
        shadow.setTaskId(taskId);
        shadow.setSessionId(sessionId);
        shadow.setRequestId("ui-shadow:" + requestId);
        shadow.setTaskType("A2A_TASK");
        shadow.setSourceNodeId(sourceNodeId);
        shadow.setTargetNodeId(targetNodeId);
        shadow.setPayload(new LinkedHashMap<>(params));
        taskLifecycleService.createTask(shadow);
    }

    private String appendUserMessage(String sessionId,
                                     String content,
                                     String sourceNodeId,
                                     List<String> targetNodeIds) {
        String eventId = "ui-message:" + UUID.randomUUID();
        AiSessionContextAppendRequest contextRequest = new AiSessionContextAppendRequest();
        contextRequest.setEventId(eventId);
        contextRequest.setSenderType("USER");
        contextRequest.setSenderId(sourceNodeId);
        contextRequest.setTargetNodeId(String.join(",", targetNodeIds));
        contextRequest.setRole("user");
        contextRequest.setContent(content);
        contextRequest.setContentType("TEXT");
        contextService.append(sessionId, contextRequest);
        return eventId;
    }

    private Map<String, RelayNodeView> resolveAvailableNodes(List<String> targetNodeIds) {
        Map<String, RelayNodeView> result = new LinkedHashMap<>();
        for (String targetNodeId : targetNodeIds) {
            RelayNodeView node = relayRegistryService.getNode(targetNodeId);
            if (node == null || !"AVAILABLE".equalsIgnoreCase(node.getStatus()) || isBlank(node.getRelayEndpoint())) {
                throw new IllegalArgumentException("Target Relay is not available: " + targetNodeId);
            }
            result.put(targetNodeId, node);
        }
        return result;
    }

    private List<String> targetNodeIds(UiSessionMessageRequest request) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (request != null && request.getTargetNodeIds() != null) {
            for (String value : request.getTargetNodeIds()) {
                if (!isBlank(value)) {
                    values.add(value.trim());
                }
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("targetNodeIds is required");
        }
        return new ArrayList<>(values);
    }

    private Map<String, Object> readPayload(String payloadJson) {
        if (isBlank(payloadJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<>() { });
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to read task routing context", error);
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize task failure", error);
        }
    }

    private String requireText(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private String normalize(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.stream().anyMatch(status::equalsIgnoreCase);
    }
}
