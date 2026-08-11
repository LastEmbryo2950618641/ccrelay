package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiDeployRecordEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayNodeEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployMode;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployReportRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployReportResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiDeployRecordRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayNodeRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class AiRelayDeployRemoteFirstFallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolvesSelfReplicateArtifactFromRegisteredSourceWorkspace() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);
        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository,
                taskRepository,
                relayNodeRepository,
                taskEventService,
                auditService,
                sshDeployExecutor,
                remoteSelfReplicateExecutor,
                true,
                List.of("*"));

        String taskId = "deploy-source-workspace-default";
        String targetNodeId = "target-node:18192";
        Map<String, Object> deploymentPayload = new LinkedHashMap<>(payload());
        deploymentPayload.remove("artifactPath");
        deploymentPayload.remove("scriptPath");
        AiTaskEntity task = task(taskId, targetNodeId, deploymentPayload);

        AiRelayNodeEntity targetNode = new AiRelayNodeEntity();
        targetNode.setNodeId(targetNodeId);
        targetNode.setStatus("UNAVAILABLE");
        AiRelayNodeEntity sourceNode = new AiRelayNodeEntity();
        sourceNode.setNodeId(task.getSourceNodeId());
        sourceNode.setRelayEndpoint("http://127.0.0.1:19091/api/ai/remote-cc/chat");
        sourceNode.setWorkspaceRoot("/home/ccrelay/ccrelay/source-node-18192");

        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(taskId)).thenReturn(List.of());
        when(deployRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayNodeRepository.findByNodeId(targetNodeId)).thenReturn(Optional.of(targetNode));
        when(relayNodeRepository.findByNodeId(task.getSourceNodeId())).thenReturn(Optional.of(sourceNode));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskEventService.listEvents(taskId)).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");
        when(remoteSelfReplicateExecutor.execute(eq(sourceNode.getRelayEndpoint()), any(), any()))
                .thenReturn(new SelfReplicateResponse(true, true, "WAIT_REGISTER", "WAIT_REGISTER", 0, "ok", ""));

        service.triggerDeployAsync(taskId, deployRequest(taskId, task, deploymentPayload));

        ArgumentCaptor<SelfReplicateRequest> requestCaptor = ArgumentCaptor.forClass(SelfReplicateRequest.class);
        verify(remoteSelfReplicateExecutor).execute(eq(sourceNode.getRelayEndpoint()), requestCaptor.capture(), any());
        assertEquals(sourceNode.getWorkspaceRoot(), requestCaptor.getValue().getArtifactPath());
        assertEquals(sourceNode.getWorkspaceRoot() + "/install-relay.sh", requestCaptor.getValue().getScriptPath());
        verify(sshDeployExecutor, never()).deploy(any());
    }

    @Test
    void persistsPerNodeProgressForCenterDeploy() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);
        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository,
                taskRepository,
                relayNodeRepository,
                taskEventService,
                auditService,
                sshDeployExecutor,
                remoteSelfReplicateExecutor,
                true,
                List.of("*"));
        String taskId = "deploy-progress-center";
        String targetNodeId = "target-node:18192";
        Map<String, Object> payload = new LinkedHashMap<>(payload());
        payload.put("deployMode", DeployMode.CENTER_DEPLOY.name());
        AiTaskEntity task = task(taskId, targetNodeId, payload);
        AiRelayNodeEntity targetNode = new AiRelayNodeEntity();
        targetNode.setNodeId(targetNodeId);
        AtomicReference<AiDeployRecordEntity> savedRecord = new AtomicReference<>();
        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(taskId)).thenReturn(List.of());
        when(deployRecordRepository.save(any(AiDeployRecordEntity.class))).thenAnswer(invocation -> {
            AiDeployRecordEntity record = invocation.getArgument(0);
            savedRecord.set(record);
            return record;
        });
        when(deployRecordRepository.findByDeployId(any())).thenAnswer(invocation -> Optional.ofNullable(savedRecord.get()));
        when(relayNodeRepository.findByNodeId(targetNodeId)).thenReturn(Optional.of(targetNode));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskEventService.listEvents(taskId)).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");
        when(sshDeployExecutor.deploy(any())).thenAnswer(invocation -> {
            SshDeployRequest sshRequest = invocation.getArgument(0);
            sshRequest.getProgressListener().onProgress(new SshDeployProgress(
                    "PREPARING_DIRECTORY", 20, 0L, null, System.currentTimeMillis(), "preparing"));
            sshRequest.getProgressListener().onProgress(new SshDeployProgress(
                    "COPYING_ARTIFACT", 50, 1024L, 2048L, System.currentTimeMillis(), "copying"));
            return new SshDeployResult(true, 0, "ok", "");
        });

        service.triggerDeployAsync(taskId, deployRequest(taskId, task, payload));

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskEventService, org.mockito.Mockito.atLeast(2)).appendEvent(
                eq(taskId), eq(task.getSessionId()), eventTypeCaptor.capture(), any(), any());
        assertTrue(eventTypeCaptor.getAllValues().contains("DEPLOY_NODE_PROGRESS"));
        assertTrue(task.getResultJson().contains("deployProgress"));
        assertTrue(task.getResultJson().contains("COPYING_ARTIFACT"));
    }

    @Test
    void prefersSelfReplicateFirstAndFallsBackToSshDeploy() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);

        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository,
                taskRepository,
                relayNodeRepository,
                taskEventService,
                auditService,
                sshDeployExecutor,
                remoteSelfReplicateExecutor,
                true,
                List.of("*"));

        String taskId = "deploy-task-remote-first";
        String targetNodeId = "target-node:18191";
        AiTaskEntity task = task(taskId, targetNodeId, payload());

        AiRelayNodeEntity targetNode = new AiRelayNodeEntity();
        targetNode.setNodeId(targetNodeId);
        targetNode.setStatus("UNAVAILABLE");

        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(taskId)).thenReturn(List.of());
        when(deployRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayNodeRepository.findByNodeId(targetNodeId)).thenReturn(Optional.of(targetNode));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskEventService.listEvents(taskId)).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");
        when(sshDeployExecutor.deploy(any())).thenReturn(new SshDeployResult(true, 0, "ssh ok", ""));
        when(remoteSelfReplicateExecutor.execute(eq("http://127.0.0.1:19091/api/ai/remote-cc/chat"), any(), any()))
                .thenThrow(new IllegalStateException("remote self replicate failed"));

        AiTaskCreateRequest request = deployRequest(taskId, task, payload());

        service.triggerDeployAsync(taskId, request);

        assertEquals("PARTIAL_SUCCESS", task.getStatus());
        assertEquals("WAIT_REGISTER", task.getCurrentStage());
        assertEquals("DEPLOYING", targetNode.getStatus());
        assertTrue(task.getResultJson().contains("WAIT_REGISTER"));

        ArgumentCaptor<SshDeployRequest> sshRequestCaptor = ArgumentCaptor.forClass(SshDeployRequest.class);
        verify(sshDeployExecutor).deploy(sshRequestCaptor.capture());
        assertEquals("47.93.195.246", sshRequestCaptor.getValue().getSourceHost());
        assertEquals(Integer.valueOf(22), sshRequestCaptor.getValue().getSourcePort());
        assertEquals("liuqi", sshRequestCaptor.getValue().getSourceUsername());

        var order = inOrder(remoteSelfReplicateExecutor, sshDeployExecutor);
        order.verify(remoteSelfReplicateExecutor).execute(eq("http://127.0.0.1:19091/api/ai/remote-cc/chat"), any(), any());
        order.verify(sshDeployExecutor).deploy(any(SshDeployRequest.class));
    }

    @Test
    void fallsBackToRegisteredSourceNodeAddressWhenPayloadOmitsSourceHost() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);

        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository,
                taskRepository,
                relayNodeRepository,
                taskEventService,
                auditService,
                sshDeployExecutor,
                remoteSelfReplicateExecutor,
                true,
                List.of("*"));

        String taskId = "deploy-task-remote-fallback-source-node";
        String targetNodeId = "target-node:18191";
        Map<String, Object> payload = payloadWithoutSourceHost();
        AiTaskEntity task = task(taskId, targetNodeId, payload);

        AiRelayNodeEntity targetNode = new AiRelayNodeEntity();
        targetNode.setNodeId(targetNodeId);
        targetNode.setStatus("UNAVAILABLE");

        AiRelayNodeEntity sourceNode = new AiRelayNodeEntity();
        sourceNode.setNodeId(task.getSourceNodeId());
        sourceNode.setHost("47.93.195.246");
        sourceNode.setPort(2222);

        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(taskId)).thenReturn(List.of());
        when(deployRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayNodeRepository.findByNodeId(targetNodeId)).thenReturn(Optional.of(targetNode));
        when(relayNodeRepository.findByNodeId(task.getSourceNodeId())).thenReturn(Optional.of(sourceNode));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskEventService.listEvents(taskId)).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");
        when(sshDeployExecutor.deploy(any())).thenReturn(new SshDeployResult(true, 0, "ssh ok", ""));
        when(remoteSelfReplicateExecutor.execute(eq("http://127.0.0.1:19091/api/ai/remote-cc/chat"), any(), any()))
                .thenThrow(new IllegalStateException("remote self replicate failed"));

        service.triggerDeployAsync(taskId, deployRequest(taskId, task, payload));

        ArgumentCaptor<SshDeployRequest> sshRequestCaptor = ArgumentCaptor.forClass(SshDeployRequest.class);
        verify(sshDeployExecutor).deploy(sshRequestCaptor.capture());
        assertEquals("47.93.195.246", sshRequestCaptor.getValue().getSourceHost());
        assertEquals(Integer.valueOf(2222), sshRequestCaptor.getValue().getSourcePort());
        assertEquals("tester", sshRequestCaptor.getValue().getSourceUsername());
    }

    @Test
    void rollsBackCenterDeployFailureAndCleansInvalidRegistration() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);

        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository,
                taskRepository,
                relayNodeRepository,
                taskEventService,
                auditService,
                sshDeployExecutor,
                remoteSelfReplicateExecutor,
                true,
                List.of("*"));

        String taskId = "deploy-task-rollback";
        String targetNodeId = "target-node:28191";
        Map<String, Object> payload = new LinkedHashMap<>(payload());
        payload.put("deployMode", DeployMode.CENTER_DEPLOY.name());
        payload.put("enableCenterFallback", false);
        payload.put("remoteDirectory", "/opt/wdsavs/rollback-target");
        AiTaskEntity task = task(taskId, targetNodeId, payload);

        AiRelayNodeEntity targetNode = new AiRelayNodeEntity();
        targetNode.setNodeId(targetNodeId);
        targetNode.setStatus("REGISTERING");
        targetNode.setRegisterTime(String.valueOf(System.currentTimeMillis() + 60_000L));

        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(taskId)).thenReturn(List.of());
        when(deployRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayNodeRepository.findByNodeId(targetNodeId)).thenReturn(Optional.of(targetNode));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskEventService.listEvents(taskId)).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");
        when(sshDeployExecutor.deploy(any())).thenReturn(new SshDeployResult(false, 23, "", "scp failed"));

        AiTaskCreateRequest request = deployRequest(taskId, task, payload);

        service.triggerDeployAsync(taskId, request);

        assertEquals("FAILED", task.getStatus());
        assertEquals("SSH_FAILED", task.getCurrentStage());
        assertTrue(task.getResultJson().contains("CENTER_DEPLOY_FAILED"));

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskEventService, org.mockito.Mockito.atLeast(2)).appendEvent(eq(taskId), eq(task.getSessionId()), eventTypeCaptor.capture(), any(), any());
        assertTrue(eventTypeCaptor.getAllValues().contains("DEPLOY_FAILED"));
        assertTrue(eventTypeCaptor.getAllValues().contains("DEPLOY_ROLLBACK_EXECUTED"));
        verify(relayNodeRepository).delete(targetNode);
    }

    @Test
    void activatesLinkedGrantAndBackfillsParentWhenReplicatedNodeRegistersHealthy() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);
        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);

        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository,
                taskRepository,
                relayNodeRepository,
                taskEventService,
                auditService,
                sshDeployExecutor,
                remoteSelfReplicateExecutor,
                relayGrantService,
                true,
                List.of("*"));

        String parentTaskId = "parent-expand-a-to-b";
        String childTaskId = "child-deploy-b";
        String targetNodeId = "target-node-b:38191";
        String grantId = "grant-a-to-b-1";
        String relayEndpoint = "http://127.0.0.1:38191/api/ai/remote-cc/chat";

        Map<String, AiTaskEntity> taskStore = new LinkedHashMap<>();
        AiTaskEntity parentTask = new AiTaskEntity();
        parentTask.setTaskId(parentTaskId);
        parentTask.setSessionId("session-expand-1");
        parentTask.setTaskType("A2A_MESSAGE");
        parentTask.setStatus("WAITING_DEPLOY");
        parentTask.setCurrentStage("ACCESS_WAITING_DEPLOY");
        parentTask.setResultJson(objectMapper.writeValueAsString(Map.of("accessGrantId", grantId)));
        parentTask.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        taskStore.put(parentTaskId, parentTask);

        Map<String, Object> childPayload = new LinkedHashMap<>(payload());
        childPayload.put("deployMode", DeployMode.SELF_REPLICATE.name());
        childPayload.put("grantId", grantId);
        childPayload.put("remoteDirectory", "/opt/wdsavs/relay-b");
        AiTaskEntity childTask = task(childTaskId, targetNodeId, childPayload);
        childTask.setParentTaskId(parentTaskId);
        childTask.setStatus("PARTIAL_SUCCESS");
        childTask.setCurrentStage("WAIT_REGISTER");
        taskStore.put(childTaskId, childTask);

        AtomicReference<AiDeployRecordEntity> latestRecord = new AtomicReference<>();
        AiDeployRecordEntity deployRecord = new AiDeployRecordEntity();
        deployRecord.setDeployId("deploy-record-b-1");
        deployRecord.setTaskId(childTaskId);
        deployRecord.setSessionId(childTask.getSessionId());
        deployRecord.setTargetNodeId(targetNodeId);
        deployRecord.setDeployMode(DeployMode.SELF_REPLICATE.name());
        deployRecord.setStatus("WAIT_REGISTER");
        deployRecord.setScriptPath("/opt/wdsavs/install-relay.sh");
        deployRecord.setArtifactPath("/tmp/relay.jar");
        deployRecord.setStartTime(String.valueOf(System.currentTimeMillis() - 30_000L));
        latestRecord.set(deployRecord);

        AiRelayNodeEntity targetNode = new AiRelayNodeEntity();
        targetNode.setNodeId(targetNodeId);
        targetNode.setStatus("REGISTERING");
        targetNode.setRelayEndpoint(relayEndpoint);
        targetNode.setRegisterTime(String.valueOf(System.currentTimeMillis() - 1_000L));

        RelayAccessDecisionResponse decision = new RelayAccessDecisionResponse();
        decision.setGrantId(grantId);
        decision.setDecision("ALLOW");
        decision.setSignedToken("signed-grant-token");
        decision.setTargetRelayEndpoint(relayEndpoint);
        decision.setAllowedCapabilities(List.of("A2A_MESSAGE_SEND"));
        decision.setExpiresAt(String.valueOf(System.currentTimeMillis() + 60_000L));
        when(relayGrantService.activateGrantIfReady(grantId)).thenReturn(decision);

        when(taskRepository.findByTaskId(any())).thenAnswer(invocation -> Optional.ofNullable(taskStore.get(invocation.getArgument(0))));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            AiTaskEntity entity = invocation.getArgument(0);
            taskStore.put(entity.getTaskId(), entity);
            return entity;
        });
        when(deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(childTaskId)).thenReturn(List.of(deployRecord));
        when(deployRecordRepository.save(any(AiDeployRecordEntity.class))).thenAnswer(invocation -> {
            AiDeployRecordEntity entity = invocation.getArgument(0);
            latestRecord.set(entity);
            return entity;
        });
        when(relayNodeRepository.findByNodeId(targetNodeId)).thenReturn(Optional.of(targetNode));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskEventService.listEvents(any())).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");

        DeployReportRequest reportRequest = new DeployReportRequest();
        reportRequest.setTaskId(childTaskId);
        reportRequest.setSessionId(childTask.getSessionId());
        reportRequest.setTargetNodeId(targetNodeId);
        reportRequest.setDeployMode(DeployMode.SELF_REPLICATE.name());
        reportRequest.setStatus("SUCCESS");
        reportRequest.setRelayEndpoint(relayEndpoint);
        reportRequest.setVersion("1.0.0");
        reportRequest.setRegistered(true);
        reportRequest.setHealthPassed(true);
        reportRequest.setStdoutSummary("relay started");
        reportRequest.setExitCode(0);
        reportRequest.setRetryable(false);

        DeployReportResponse response = service.report(reportRequest);

        assertTrue(response.getAccepted());
        assertEquals("REGISTERED", response.getNextAction());
        assertEquals("SUCCESS", latestRecord.get().getStatus());
        assertEquals("AVAILABLE", targetNode.getStatus());

        AiTaskEntity savedChildTask = taskStore.get(childTaskId);
        assertEquals("SUCCESS", savedChildTask.getStatus());
        assertEquals("REGISTERED", savedChildTask.getCurrentStage());
        assertTrue(savedChildTask.getResultJson().contains("grantActivationStatus"));
        assertTrue(savedChildTask.getResultJson().contains("ALLOW"));

        AiTaskEntity savedParentTask = taskStore.get(parentTaskId);
        assertNotNull(savedParentTask);
        assertEquals("PARTIAL_SUCCESS", savedParentTask.getStatus());
        assertEquals("CHILD_GRANT_ACTIVATED", savedParentTask.getCurrentStage());
        assertTrue(savedParentTask.getResultJson().contains("latestChildGrantActivationStatus"));
        assertTrue(savedParentTask.getResultJson().contains("ALLOW"));
        assertTrue(savedParentTask.getResultJson().contains("latestChildRelayEndpoint"));

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskEventService, org.mockito.Mockito.atLeast(4)).appendEvent(any(), any(), eventTypeCaptor.capture(), any(), any());
        assertTrue(eventTypeCaptor.getAllValues().contains("DEPLOY_REGISTERED"));
        assertTrue(eventTypeCaptor.getAllValues().contains("DEPLOY_GRANT_ACTIVATED"));
        assertTrue(eventTypeCaptor.getAllValues().contains("CHILD_TASK_UPDATED"));
        verify(relayGrantService).activateGrantIfReady(grantId);
    }

    @Test
    void doesNotOverwriteCancelledTaskWhenCenterDeployReturnsLate() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);
        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository, taskRepository, relayNodeRepository, taskEventService, auditService,
                sshDeployExecutor, remoteSelfReplicateExecutor, true, List.of("*"));

        String taskId = "deploy-task-cancelled-during-ssh";
        Map<String, Object> payload = new LinkedHashMap<>(payload());
        payload.put("deployMode", DeployMode.CENTER_DEPLOY.name());
        AiTaskEntity task = task(taskId, "target-node:18191", payload);
        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(taskId)).thenReturn(List.of());
        when(deployRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayNodeRepository.findByNodeId(task.getTargetNodeId())).thenReturn(Optional.empty());
        when(taskEventService.listEvents(taskId)).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");
        when(sshDeployExecutor.deploy(any())).thenAnswer(invocation -> {
            task.setStatus("CANCELLED");
            task.setCurrentStage("CANCELLED");
            return new SshDeployResult(false, 2, "", "late deploy failure");
        });

        service.triggerDeployAsync(taskId, deployRequest(taskId, task, payload));

        assertEquals("CANCELLED", task.getStatus());
        assertEquals("CANCELLED", task.getCurrentStage());
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskEventService, org.mockito.Mockito.atLeast(2)).appendEvent(eq(taskId), eq(task.getSessionId()), eventTypeCaptor.capture(), any(), any());
        assertTrue(eventTypeCaptor.getAllValues().contains("DEPLOY_EXECUTOR_RESPONSE_IGNORED"));
        assertFalse(eventTypeCaptor.getAllValues().contains("DEPLOY_FAILED"));
    }

    @Test
    void waitsForUserInputBeforeCenterFallbackWhenSshPreflightIsRequired() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);

        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository, taskRepository, relayNodeRepository, taskEventService, auditService,
                sshDeployExecutor, remoteSelfReplicateExecutor, true, List.of("*"));

        String taskId = "deploy-task-waiting-ssh-input";
        Map<String, Object> payload = new LinkedHashMap<>(payload());
        AiTaskEntity task = task(taskId, "target-node:18191", payload);
        AiRelayNodeEntity targetNode = new AiRelayNodeEntity();
        targetNode.setNodeId(task.getTargetNodeId());
        targetNode.setStatus("UNAVAILABLE");

        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(taskId)).thenReturn(List.of());
        when(deployRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayNodeRepository.findByNodeId(task.getTargetNodeId())).thenReturn(Optional.of(targetNode));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskEventService.listEvents(taskId)).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");
        when(remoteSelfReplicateExecutor.execute(any(), any(), any())).thenThrow(new IllegalStateException("self replicate failed"));
        when(sshDeployExecutor.deploy(any())).thenReturn(
                new SshDeployResult(false, 255, "", "Permission denied (publickey,password)"));

        service.triggerDeployAsync(taskId, deployRequest(taskId, task, payload));

        assertEquals("WAITING_USER_INPUT", task.getStatus());
        assertEquals("SSH_CREDENTIAL_REQUIRED", task.getCurrentStage());
        assertEquals("SSH_CREDENTIAL_REQUIRED", task.getErrorCode());
        assertTrue(task.getResultJson().contains("passwordAcceptedByCenter"));
        verify(sshDeployExecutor).deploy(any());
    }

    @Test
    void resumesCenterDeployAfterSshPreflight() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);

        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository, taskRepository, relayNodeRepository, taskEventService, auditService,
                sshDeployExecutor, remoteSelfReplicateExecutor, true, List.of("*"));

        String taskId = "deploy-task-resume-ssh";
        Map<String, Object> payload = new LinkedHashMap<>(payload());
        payload.put("sshCredentialPreflightRequiredOnFallback", true);
        AiTaskEntity task = task(taskId, "target-node:18191", payload);
        task.setStatus("WAITING_USER_INPUT");
        task.setCurrentStage("SSH_CREDENTIAL_REQUIRED");
        AiRelayNodeEntity targetNode = new AiRelayNodeEntity();
        targetNode.setNodeId(task.getTargetNodeId());
        targetNode.setStatus("UNAVAILABLE");

        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deployRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayNodeRepository.findByNodeId(task.getTargetNodeId())).thenReturn(Optional.of(targetNode));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskEventService.listEvents(taskId)).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");
        when(sshDeployExecutor.deploy(any())).thenReturn(new SshDeployResult(true, 0, "ssh ok", ""));

        boolean resumed = service.resumeCenterDeploy(taskId);

        assertTrue(resumed);
        assertEquals("PARTIAL_SUCCESS", task.getStatus());
        assertEquals("WAIT_REGISTER", task.getCurrentStage());
        assertTrue(task.getRequestPayloadJson().contains("sshCredentialPreflightCompleted"));
        verify(sshDeployExecutor).deploy(any());
    }

    @Test
    void doesNotCompleteSelfReplicatingTaskBeforeDeploymentResponse() throws Exception {
        AiDeployRecordRepository deployRecordRepository = mock(AiDeployRecordRepository.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiRelayNodeRepository relayNodeRepository = mock(AiRelayNodeRepository.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        SshDeployExecutor sshDeployExecutor = mock(SshDeployExecutor.class);
        RemoteSelfReplicateExecutor remoteSelfReplicateExecutor = mock(RemoteSelfReplicateExecutor.class);

        AiRelayDeployServiceImpl service = new AiRelayDeployServiceImpl(
                deployRecordRepository,
                taskRepository,
                relayNodeRepository,
                taskEventService,
                auditService,
                sshDeployExecutor,
                remoteSelfReplicateExecutor,
                true,
                List.of("*"));

        String taskId = "deploy-task-self-replicating-recover";
        String targetNodeId = "47.93.195.246:48191";
        String registeredNodeId = "target-node-recovered:48191";
        Map<String, Object> deploymentPayload = payload();
        deploymentPayload.put("host", "47.93.195.246");
        deploymentPayload.put("relayPort", 48191);
        deploymentPayload.put("remoteDirectory", "/home/ccrelay/ccrelay/47.93.195.246-48191");
        AiTaskEntity task = task(taskId, targetNodeId, deploymentPayload);
        task.setStatus("RUNNING");
        task.setCurrentStage("SELF_REPLICATING");
        task.setResultJson(objectMapper.writeValueAsString(Map.of(
                "resolvedRemoteDirectory", "/home/ccrelay/ccrelay/47.93.195.246-48191")));

        AiDeployRecordEntity deployRecord = new AiDeployRecordEntity();
        deployRecord.setDeployId("deploy-record-recovered");
        deployRecord.setTaskId(taskId);
        deployRecord.setSessionId(task.getSessionId());
        deployRecord.setTargetNodeId(targetNodeId);
        deployRecord.setDeployMode(DeployMode.SELF_REPLICATE.name());
        deployRecord.setStatus("SELF_REPLICATING");
        deployRecord.setArtifactPath("/opt/wdsavs/runtime");

        AiRelayNodeEntity targetNode = new AiRelayNodeEntity();
        targetNode.setNodeId(registeredNodeId);
        targetNode.setHost("target-node-recovered");
        targetNode.setPort(48191);
        targetNode.setWorkspaceRoot("/home/ccrelay/ccrelay/47.93.195.246-48191/source-bundle");
        targetNode.setStatus("AVAILABLE");
        targetNode.setRelayEndpoint("http://127.0.0.1:48191/api/ai/remote-cc/chat");
        targetNode.setVersion("1.0.0");
        targetNode.setRegisterTime(String.valueOf(System.currentTimeMillis() - 1_000L));

        when(relayNodeRepository.findByNodeId(registeredNodeId)).thenReturn(Optional.of(targetNode));
        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AiTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(deployRecordRepository.findAll()).thenReturn(List.of(deployRecord));
        when(deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(taskId)).thenReturn(List.of(deployRecord));
        when(deployRecordRepository.save(any(AiDeployRecordEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(relayNodeRepository.save(any(AiRelayNodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskEventService.listEvents(taskId)).thenReturn(List.of());
        when(taskEventService.appendEvent(any(), any(), any(), any(), any())).thenReturn("event-1");
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");

        boolean completed = service.autoCompleteReadyDeployments(registeredNodeId);

        assertFalse(completed);
        assertEquals("RUNNING", task.getStatus());
        assertEquals("SELF_REPLICATING", task.getCurrentStage());
        assertEquals("SELF_REPLICATING", deployRecord.getStatus());
        verify(deployRecordRepository, never()).save(any(AiDeployRecordEntity.class));
        verify(taskEventService, never()).appendEvent(eq(taskId), eq(task.getSessionId()),
                eq("DEPLOY_SELF_REPLICATE_SUCCEEDED"), any(), any());
    }

    private AiTaskEntity task(String taskId, String targetNodeId, Map<String, Object> payload) throws Exception {
        AiTaskEntity task = new AiTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId("session-deploy-1");
        task.setTaskType("DEPLOY_RELAY");
        task.setStatus("WAITING_DEPLOY");
        task.setCurrentStage("ACCESS_WAITING_DEPLOY");
        task.setSourceNodeId("source-node:18091");
        task.setTargetNodeId(targetNodeId);
        task.setRequestPayloadJson(objectMapper.writeValueAsString(payload));
        task.setCreateTime(String.valueOf(System.currentTimeMillis()));
        task.setUpdateTime(task.getCreateTime());
        return task;
    }

    private AiTaskCreateRequest deployRequest(String taskId, AiTaskEntity task, Map<String, Object> payload) {
        AiTaskCreateRequest request = new AiTaskCreateRequest();
        request.setTaskId(taskId);
        request.setSessionId(task.getSessionId());
        request.setTaskType("DEPLOY_RELAY");
        request.setSourceNodeId(task.getSourceNodeId());
        request.setTargetNodeId(task.getTargetNodeId());
        request.setPayload(payload);
        return request;
    }

    private Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deployMode", DeployMode.SELF_REPLICATE.name());
        payload.put("enableCenterFallback", true);
        payload.put("sourceRelayEndpoint", "http://127.0.0.1:19091/api/ai/remote-cc/chat");
        payload.put("host", "127.0.0.1");
        payload.put("port", 22);
        payload.put("username", "tester");
        payload.put("sourceHost", "47.93.195.246");
        payload.put("sourcePort", 22);
        payload.put("sourceUser", "liuqi");
        payload.put("scriptPath", "/opt/wdsavs/install-relay.sh");
        payload.put("artifactPath", "/tmp/relay.jar");
        payload.put("remoteDirectory", "/opt/wdsavs/relay");
        payload.put("commandArguments", List.of("--spring.profiles.active=dev"));
        return payload;
    }

    private Map<String, Object> payloadWithoutSourceHost() {
        Map<String, Object> payload = payload();
        payload.remove("sourceHost");
        payload.remove("sourcePort");
        payload.remove("sourceUser");
        return payload;
    }
}

