package com.webank.wedatasphere.wdsavs.aiagentskill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskEventView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextEventView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionCollaborationView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
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
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationView;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.UiSessionMessageRequest;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.UiSessionMessageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UiCollaborationServiceTest {

    @Test
    void appendsOneUserMessageAndCreatesOneTaskPerTarget() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionCollaborationService collaborationService = mock(AiSessionCollaborationService.class);
        AiSessionContextService contextService = mock(AiSessionContextService.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        A2aTaskService a2aTaskService = mock(A2aTaskService.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        when(registryService.getNode("node-a:18192")).thenReturn(node("node-a:18192"));
        when(registryService.getNode("node-b:18192")).thenReturn(node("node-b:18192"));
        when(grantService.requestAccess(any())).thenAnswer(invocation -> grant(
                invocation.getArgument(0, com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest.class).getTargetNodeId()));
        when(contextService.append(any(), any())).thenReturn(new AiSessionContextEventView());
        AiSessionCollaborationView collaboration = new AiSessionCollaborationView();
        collaboration.setSessionId("session-1");
        collaboration.setCollaborationMode("DISCUSSION");
        collaboration.setCoordinatorNodeId("node-b:18192");
        collaboration.setCoordinatorEpoch(1L);
        collaboration.setParticipantNodeIds(List.of("node-a:18192", "node-b:18192"));
        when(collaborationService.initialize(any(), any(), any(), any())).thenReturn(collaboration);
        when(a2aTaskService.createTask(any())).thenAnswer(invocation -> {
            A2aTaskCreateResponse response = new A2aTaskCreateResponse();
            response.getResult().put("taskId", invocation.getArgument(0,
                    com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest.class).getParams().get("taskId"));
            return response;
        });
        UiCollaborationService service = new UiCollaborationService(sessionService, contextService, registryService,
                grantService, taskLifecycleService, taskEventService, a2aTaskService, taskRepository, new ObjectMapper());
        service.setCollaborationService(collaborationService);
        SessionTitleService sessionTitleService = mock(SessionTitleService.class);
        service.setSessionTitleService(sessionTitleService);
        UiSessionMessageRequest request = new UiSessionMessageRequest();
        request.setContent("请协同检查服务状态");
        request.setTargetNodeIds(List.of("node-a:18192", "node-b:18192"));

        UiSessionMessageResponse response = service.send("session-1", request);

        assertEquals(2, response.getAcceptedCount());
        assertEquals("node-b:18192", response.getCoordinatorNodeId());
        verify(sessionTitleService).generateIfAbsentAsync("session-1", "node-b:18192");
        verify(contextService, times(1)).append(any(), any());
        verify(taskLifecycleService, times(2)).createTask(any());
        ArgumentCaptor<com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest> taskRequests =
                ArgumentCaptor.forClass(com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest.class);
        verify(a2aTaskService, times(2)).createTask(taskRequests.capture());
        assertTrue(taskRequests.getAllValues().stream()
                .allMatch(taskRequest -> Boolean.FALSE.equals(taskRequest.getParams().get("allowCenterForwardFallback"))));
        assertTrue(taskRequests.getAllValues().stream().allMatch(taskRequest -> {
            Object react = taskRequest.getParams().get("react");
            return react instanceof java.util.Map<?, ?> reactMap
                    && List.of("ccrelay-cli", "ccrelay-cli.cmd").equals(reactMap.get("commandWhitelist"));
        }));
        assertEquals(List.of("PARTICIPANT", "COORDINATOR"), taskRequests.getAllValues().stream()
                .map(taskRequest -> String.valueOf(taskRequest.getParams().get("agentRole")))
                .toList());
        ArgumentCaptor<AiSessionContextAppendRequest> context = ArgumentCaptor.forClass(AiSessionContextAppendRequest.class);
        verify(contextService).append(org.mockito.ArgumentMatchers.eq("session-1"), context.capture());
        assertEquals("node-a:18192,node-b:18192", context.getValue().getTargetNodeId());
    }

    @Test
    void reportsFailedSubmissionWhenGrantIsDenied() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextService contextService = mock(AiSessionContextService.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        A2aTaskService a2aTaskService = mock(A2aTaskService.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        when(registryService.getNode("node-a:18192")).thenReturn(node("node-a:18192"));
        RelayAccessDecisionResponse denied = new RelayAccessDecisionResponse();
        denied.setDecision("DENY");
        when(grantService.requestAccess(any())).thenReturn(denied);
        when(contextService.append(any(), any())).thenReturn(new AiSessionContextEventView());
        UiCollaborationService service = new UiCollaborationService(sessionService, contextService, registryService,
                grantService, taskLifecycleService, taskEventService, a2aTaskService, taskRepository, new ObjectMapper());
        UiSessionMessageRequest request = new UiSessionMessageRequest();
        request.setContent("检查服务状态");
        request.setTargetNodeIds(List.of("node-a:18192"));

        UiSessionMessageResponse response = service.send("session-1", request);

        assertEquals(0, response.getAcceptedCount());
        assertEquals("FAILED", response.getSubmissions().get(0).get("status"));
        assertTrue(String.valueOf(response.getSubmissions().get(0).get("message")).contains("not allowed"));
    }

    @Test
    void synchronizeSkipsTasksAlreadyPersistedAsTerminal() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextService contextService = mock(AiSessionContextService.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        A2aTaskService a2aTaskService = mock(A2aTaskService.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskEntity terminalTask = task("task-success", "SUCCESS");
        when(taskRepository.findBySessionIdOrderByCreateTimeDesc("session-1")).thenReturn(List.of(terminalTask));
        UiCollaborationService service = new UiCollaborationService(sessionService, contextService, registryService,
                grantService, taskLifecycleService, taskEventService, a2aTaskService, taskRepository, new ObjectMapper());

        java.util.Map<String, Object> response = service.synchronize("session-1");

        verify(a2aTaskService, never()).getTask(any(), any());
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> results = (List<java.util.Map<String, Object>>) response.get("results");
        assertEquals("ALREADY_TERMINAL", results.get(0).get("status"));
    }

    @Test
    void synchronizeFetchesNonTerminalRemoteTask() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextService contextService = mock(AiSessionContextService.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        A2aTaskService a2aTaskService = mock(A2aTaskService.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskEntity pendingTask = task("task-pending", "PENDING");
        pendingTask.setRequestPayloadJson("{\"targetRelayEndpoint\":\"http://node-a:18192\"}");
        when(taskRepository.findBySessionIdOrderByCreateTimeDesc("session-1")).thenReturn(List.of(pendingTask));
        when(a2aTaskService.getTask(any(), any())).thenReturn(java.util.Map.of("taskId", "task-pending", "status", "SUCCESS"));
        UiCollaborationService service = new UiCollaborationService(sessionService, contextService, registryService,
                grantService, taskLifecycleService, taskEventService, a2aTaskService, taskRepository, new ObjectMapper());

        java.util.Map<String, Object> response = service.synchronize("session-1");

        verify(a2aTaskService).getTask(org.mockito.ArgumentMatchers.eq("task-pending"), any());
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> results = (List<java.util.Map<String, Object>>) response.get("results");
        assertEquals("SYNCED", results.get(0).get("status"));
    }

    @Test
    void marksShadowTaskFailedWhenRemoteCreationFails() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextService contextService = mock(AiSessionContextService.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        A2aTaskService a2aTaskService = mock(A2aTaskService.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskEntity shadowTask = task("shadow-task", "PENDING");
        when(registryService.getNode("node-a:18192")).thenReturn(node("node-a:18192"));
        when(grantService.requestAccess(any())).thenReturn(grant("node-a:18192"));
        when(contextService.append(any(), any())).thenReturn(new AiSessionContextEventView());
        when(taskLifecycleService.createTask(any())).thenAnswer(invocation -> {
            com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest request = invocation.getArgument(0);
            shadowTask.setTaskId(request.getTaskId());
            shadowTask.setSessionId(request.getSessionId());
            shadowTask.setTargetNodeId(request.getTargetNodeId());
            return null;
        });
        when(taskRepository.findByTaskId(any())).thenReturn(Optional.of(shadowTask));
        when(taskEventService.listEvents(any())).thenReturn(List.of());
        when(a2aTaskService.createTask(any())).thenThrow(new IllegalStateException("remote unavailable"));
        UiCollaborationService service = new UiCollaborationService(sessionService, contextService, registryService,
                grantService, taskLifecycleService, taskEventService, a2aTaskService, taskRepository, new ObjectMapper());
        UiSessionMessageRequest request = new UiSessionMessageRequest();
        request.setContent("检查服务状态");
        request.setTargetNodeIds(List.of("node-a:18192"));

        UiSessionMessageResponse response = service.send("session-1", request);

        assertEquals(0, response.getAcceptedCount());
        assertEquals("FAILED", shadowTask.getStatus());
        assertEquals("REMOTE_A2A_CREATE_FAILED", shadowTask.getCurrentStage());
        assertEquals("remote unavailable", shadowTask.getErrorMessage());
        verify(taskRepository).save(shadowTask);
        verify(taskEventService).appendEvent(org.mockito.ArgumentMatchers.eq(shadowTask.getTaskId()),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("REMOTE_A2A_CREATE_FAILED"),
                org.mockito.ArgumentMatchers.eq(1L), any());
    }

    @Test
    void persistsRemoteObservationEventsForRelayUnavailableReloads() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextService contextService = mock(AiSessionContextService.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        A2aTaskService a2aTaskService = mock(A2aTaskService.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        TaskObservationService observationService = mock(TaskObservationService.class);

        AiTaskEntity pendingTask = task("task-observation", "PENDING");
        pendingTask.setRequestPayloadJson("{\"targetRelayEndpoint\":\"http://node-a:18192\"}");
        when(taskRepository.findBySessionIdOrderByCreateTimeDesc("session-1")).thenReturn(List.of(pendingTask));
        when(taskRepository.findByTaskId("task-observation")).thenReturn(Optional.of(pendingTask));
        when(a2aTaskService.getTask(any(), any())).thenReturn(java.util.Map.of("taskId", "task-observation", "status", "SUCCESS"));
        AiTaskEventView remoteEvent = new AiTaskEventView();
        remoteEvent.setEventId("remote-event-1");
        remoteEvent.setEventType("AGENT_TOOL_FINISHED");
        remoteEvent.setSequenceNo(4L);
        remoteEvent.setPayload(new LinkedHashMap<>(java.util.Map.of("tool", "health")));
        TaskObservationView observation = new TaskObservationView();
        observation.setObservationSource("REMOTE_RELAY");
        observation.setEvents(List.of(remoteEvent));
        when(observationService.observe(any())).thenReturn(observation);

        UiCollaborationService service = new UiCollaborationService(sessionService, contextService, registryService,
                grantService, taskLifecycleService, taskEventService, a2aTaskService, taskRepository, new ObjectMapper());
        service.setTaskObservationService(observationService);

        service.synchronize("session-1");

        verify(taskEventService).appendEvent(org.mockito.ArgumentMatchers.eq("task-observation"),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("AGENT_TOOL_FINISHED"),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.argThat(payload -> "remote-event-1".equals(payload.get("remoteObservationEventId"))));
        assertTrue(pendingTask.getResultJson().contains("remote-event-1"));
        verify(taskRepository).save(pendingTask);
    }

    @Test
    void derivesStableRemoteObservationEventIdWhenRelayOmitsEventId() {
        AiSessionService sessionService = mock(AiSessionService.class);
        AiSessionContextService contextService = mock(AiSessionContextService.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        A2aTaskService a2aTaskService = mock(A2aTaskService.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        TaskObservationService observationService = mock(TaskObservationService.class);

        AiTaskEntity pendingTask = task("task-without-event-id", "PENDING");
        pendingTask.setRequestPayloadJson("{\"targetRelayEndpoint\":\"http://node-a:18192\"}");
        when(taskRepository.findBySessionIdOrderByCreateTimeDesc("session-1")).thenReturn(List.of(pendingTask));
        when(taskRepository.findByTaskId("task-without-event-id")).thenReturn(Optional.of(pendingTask));
        when(a2aTaskService.getTask(any(), any())).thenReturn(java.util.Map.of(
                "taskId", "task-without-event-id", "status", "SUCCESS"));
        AiTaskEventView remoteEvent = new AiTaskEventView();
        remoteEvent.setEventType("AGENT_TOOL_FINISHED");
        remoteEvent.setSequenceNo(7L);
        remoteEvent.setPayload(new LinkedHashMap<>(java.util.Map.of("tool", "Read")));
        TaskObservationView observation = new TaskObservationView();
        observation.setObservationSource("REMOTE_RELAY");
        observation.setEvents(List.of(remoteEvent));
        when(observationService.observe(any())).thenReturn(observation);

        UiCollaborationService service = new UiCollaborationService(sessionService, contextService, registryService,
                grantService, taskLifecycleService, taskEventService, a2aTaskService, taskRepository, new ObjectMapper());
        service.setTaskObservationService(observationService);

        service.synchronize("session-1");
        service.synchronize("session-1");

        verify(taskEventService, times(1)).appendEvent(
                org.mockito.ArgumentMatchers.eq("task-without-event-id"),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq("AGENT_TOOL_FINISHED"),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        "task-without-event-id:sequence:7".equals(payload.get("remoteObservationEventId"))));
        assertTrue(pendingTask.getResultJson().contains("task-without-event-id:sequence:7"));
    }

    private AiTaskEntity task(String taskId, String status) {
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId("session-1");
        task.setTaskType("A2A_TASK");
        task.setTargetNodeId("node-a:18192");
        task.setStatus(status);
        return task;
    }

    private RelayNodeView node(String nodeId) {
        RelayNodeView node = new RelayNodeView();
        node.setNodeId(nodeId);
        node.setStatus("AVAILABLE");
        node.setRelayEndpoint("http://" + nodeId + "/api/ai/remote-cc/chat");
        return node;
    }

    private RelayAccessDecisionResponse grant(String nodeId) {
        RelayAccessDecisionResponse response = new RelayAccessDecisionResponse();
        response.setDecision("ALLOW");
        response.setGrantId("grant-" + nodeId);
        response.setSignedToken("signed-token");
        response.setExpiresAt("9999999999999");
        response.setTargetRelayEndpoint("http://" + nodeId + "/api/ai/remote-cc/chat");
        response.setAllowedCapabilities(List.of("A2A_TASK_CREATE", "A2A_TASK_GET", "A2A_TASK_OBSERVE"));
        return response;
    }
}
