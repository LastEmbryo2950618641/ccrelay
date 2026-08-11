package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayHeartbeatEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskEventView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationQuery;
import com.webank.wedatasphere.wdsavs.aiagent.model.TaskObservationView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayHeartbeatRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskObservationServiceImplTest {

    private AiTaskLifecycleService taskLifecycleService;
    private AiTaskEventService taskEventService;
    private AiRelayHeartbeatService heartbeatService;
    private AiRelayRegistryService relayRegistryService;
    private AiTaskRepository taskRepository;
    private AiRelayHeartbeatRepository relayHeartbeatRepository;
    private RuntimeConfigService runtimeConfigService;
    private A2aPayloadPolicyService payloadPolicyService;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        taskLifecycleService = mock(AiTaskLifecycleService.class);
        taskEventService = mock(AiTaskEventService.class);
        heartbeatService = mock(AiRelayHeartbeatService.class);
        relayRegistryService = mock(AiRelayRegistryService.class);
        taskRepository = mock(AiTaskRepository.class);
        relayHeartbeatRepository = mock(AiRelayHeartbeatRepository.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        payloadPolicyService = mock(A2aPayloadPolicyService.class);
        restTemplate = mock(RestTemplate.class);
    }

    @Test
    void observeReturnsWindowedLocalSnapshot() {
        AiTaskView taskView = new AiTaskView();
        taskView.setTaskId("task-1");
        taskView.setStatus("RUNNING");
        taskView.setCurrentStage("STAGE-1");
        taskView.setTargetNodeId("node-1");
        taskView.setResult(Map.of(
                "controlState", Map.of("stopRequested", false, "policyPatch", Map.of()),
                "deployProgress", Map.of(
                        "phase", "COPYING_ARTIFACT",
                        "progressPercent", 54,
                        "bytesTransferred", 1024L,
                        "bytesPerSecond", 512L)));

        AiTaskEventView first = new AiTaskEventView();
        first.setEventType("TASK_CREATED");
        first.setSequenceNo(1L);
        first.setPayload(Map.of("message", "created"));
        AiTaskEventView second = new AiTaskEventView();
        second.setEventType("TASK_RUNNING");
        second.setSequenceNo(2L);
        second.setPayload(Map.of("message", "running"));

        when(taskLifecycleService.getTask("task-1")).thenReturn(taskView);
        when(taskEventService.listEvents("task-1", null, null, 1)).thenReturn(List.of(first));
        when(relayRegistryService.getNode("node-1")).thenThrow(new IllegalArgumentException("node not registered"));
        when(runtimeConfigService.getInt("wdsavs.ai.observation.default-limit", 50)).thenReturn(1);
        when(runtimeConfigService.getInt("wdsavs.ai.observation.max-limit", 500)).thenReturn(10);
        when(runtimeConfigService.getLong("wdsavs.ai.observation.default-max-bytes", 65536L)).thenReturn(4096L);
        when(runtimeConfigService.getLong("wdsavs.ai.observation.per-event-max-bytes", 8192L)).thenReturn(2048L);
        when(payloadPolicyService.normalizeStructuredResult(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayHeartbeatRepository.findTop1ByNodeIdOrderByHeartbeatTimeDesc(anyString())).thenReturn(List.of());

        TaskObservationServiceImpl service = new TaskObservationServiceImpl(
                taskLifecycleService,
                taskEventService,
                heartbeatService,
                relayRegistryService,
                taskRepository,
                relayHeartbeatRepository,
                runtimeConfigService,
                payloadPolicyService,
                restTemplate);

        TaskObservationQuery query = new TaskObservationQuery();
        query.setTaskId("task-1");
        query.setLimit(1);
        TaskObservationView observation = service.observe(query);

        assertEquals("task-1", observation.getTaskId());
        assertEquals("CENTER_LOCAL", observation.getObservationSource());
        assertEquals(1, observation.getEvents().size());
        assertEquals("TASK_CREATED", observation.getEvents().get(0).getEventType());
        assertEquals(false, observation.getControlState().get("stopRequested"));
        assertEquals("COPYING_ARTIFACT", observation.getDeploymentProgress().get("phase"));
        assertEquals(54, observation.getDeploymentProgress().get("progressPercent"));
        assertTrue(observation.getHeartbeat().containsKey("taskTargetNodeId") || observation.getHeartbeat().containsKey("nodeId"));
    }

    @Test
    void observeBatchAggregatesChildTasks() {
        AiTaskView child1 = new AiTaskView();
        child1.setTaskId("child-1");
        child1.setStatus("SUCCESS");
        AiTaskView child2 = new AiTaskView();
        child2.setTaskId("child-2");
        child2.setStatus("FAILED");

        com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity entity1 = new com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity();
        entity1.setTaskId("child-1");
        entity1.setCreateTime("1");
        com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity entity2 = new com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity();
        entity2.setTaskId("child-2");
        entity2.setCreateTime("2");

        when(taskRepository.findByParentTaskIdOrderByCreateTimeAsc("parent-1")).thenReturn(List.of(entity1, entity2));
        when(taskLifecycleService.getTask("child-1")).thenReturn(child1);
        when(taskLifecycleService.getTask("child-2")).thenReturn(child2);
        when(taskEventService.listEvents(anyString(), any(), any(), any())).thenReturn(List.of());
        when(runtimeConfigService.getInt("wdsavs.ai.observation.default-limit", 50)).thenReturn(50);
        when(runtimeConfigService.getInt("wdsavs.ai.observation.max-limit", 500)).thenReturn(500);
        when(runtimeConfigService.getLong("wdsavs.ai.observation.default-max-bytes", 65536L)).thenReturn(65536L);
        when(runtimeConfigService.getLong("wdsavs.ai.observation.per-event-max-bytes", 8192L)).thenReturn(8192L);
        when(payloadPolicyService.normalizeStructuredResult(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskObservationServiceImpl service = new TaskObservationServiceImpl(
                taskLifecycleService,
                taskEventService,
                heartbeatService,
                relayRegistryService,
                taskRepository,
                relayHeartbeatRepository,
                runtimeConfigService,
                payloadPolicyService,
                restTemplate);

        TaskObservationQuery query = new TaskObservationQuery();
        query.setParentTaskId("parent-1");
        query.setTaskIds(List.of());
        assertEquals(2, service.observeBatch(query).getObservations().size());
        assertEquals("child-1", service.observeBatch(query).getObservations().get(0).getTaskId());
        assertEquals("child-2", service.observeBatch(query).getObservations().get(1).getTaskId());
        assertEquals(1, service.observeBatch(query).getSummary().get("failed"));
        assertEquals(50, service.observeBatch(query).getSummary().get("overallProgressPercent"));
    }

    @Test
    void observeUsesRelayOriginForRemoteObservationEndpoint() {
        AiTaskView taskView = new AiTaskView();
        taskView.setTaskId("task-remote");
        taskView.setSessionId("session-1");
        taskView.setStatus("RUNNING");
        taskView.setTargetNodeId("node-remote");

        RelayNodeView nodeView = new RelayNodeView();
        nodeView.setNodeId("node-remote");
        nodeView.setRelayEndpoint("http://remote.example:18091/api/ai/remote-cc/chat");

        TaskObservationView remoteObservation = new TaskObservationView();
        remoteObservation.setTaskId("task-remote");
        remoteObservation.setObservationSource("REMOTE_RELAY");

        when(taskLifecycleService.getTask("task-remote")).thenReturn(taskView);
        when(relayRegistryService.getNode("node-remote")).thenReturn(nodeView);
        when(restTemplate.getForObject(anyString(), any())).thenReturn(remoteObservation);

        TaskObservationServiceImpl service = new TaskObservationServiceImpl(
                taskLifecycleService,
                taskEventService,
                heartbeatService,
                relayRegistryService,
                taskRepository,
                relayHeartbeatRepository,
                runtimeConfigService,
                payloadPolicyService,
                restTemplate);

        TaskObservationQuery query = new TaskObservationQuery();
        query.setTaskId("task-remote");
        TaskObservationView observation = service.observe(query);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).getForObject(urlCaptor.capture(), any());
        assertEquals("REMOTE_RELAY", observation.getObservationSource());
        assertTrue(urlCaptor.getValue().startsWith("http://remote.example:18091/api/ai/a2a/tasks/task-remote/observation?"));
    }

    @Test
    void observeUsesPersistedRequestGrantAfterTaskResultBecomesTerminal() {
        AiTaskView taskView = new AiTaskView();
        taskView.setTaskId("task-terminal");
        taskView.setSessionId("session-1");
        taskView.setStatus("SUCCESS");
        taskView.setTargetNodeId("node-remote");
        taskView.setResult(Map.of("latestA2aStatus", "SUCCESS"));

        AiTaskEntity taskEntity = new AiTaskEntity();
        taskEntity.setTaskId("task-terminal");
        taskEntity.setRequestPayloadJson("{\"grantId\":\"grant-1\",\"signedToken\":\"token-1\","
                + "\"expiresAt\":\"9999999999999\",\"sourceNodeId\":\"cc-center-ui\"}");

        RelayNodeView nodeView = new RelayNodeView();
        nodeView.setNodeId("node-remote");
        nodeView.setRelayEndpoint("http://remote.example:18091/api/ai/remote-cc/chat");

        TaskObservationView remoteObservation = new TaskObservationView();
        remoteObservation.setTaskId("task-terminal");
        remoteObservation.setObservationSource("REMOTE_RELAY");

        when(taskLifecycleService.getTask("task-terminal")).thenReturn(taskView);
        when(taskRepository.findByTaskId("task-terminal")).thenReturn(Optional.of(taskEntity));
        when(relayRegistryService.getNode("node-remote")).thenReturn(nodeView);
        when(restTemplate.getForObject(anyString(), any())).thenReturn(remoteObservation);

        TaskObservationServiceImpl service = new TaskObservationServiceImpl(
                taskLifecycleService,
                taskEventService,
                heartbeatService,
                relayRegistryService,
                taskRepository,
                relayHeartbeatRepository,
                runtimeConfigService,
                payloadPolicyService,
                restTemplate);

        TaskObservationQuery query = new TaskObservationQuery();
        query.setTaskId("task-terminal");
        TaskObservationView observation = service.observe(query);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).getForObject(urlCaptor.capture(), any());
        String requestUrl = urlCaptor.getValue();
        assertEquals("REMOTE_RELAY", observation.getObservationSource());
        assertTrue(requestUrl.contains("grantId=grant-1"));
        assertTrue(requestUrl.contains("signedToken=token-1"));
        assertTrue(requestUrl.contains("sourceNodeId=cc-center-ui"));
    }
}
