package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiDeployRecordEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayNodeEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployReportRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployReportResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployMode;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateProgress;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiDeployRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRelayNodeRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiRelayDeployServiceImpl implements AiRelayDeployService {

    private final AiDeployRecordRepository deployRecordRepository;
    private final AiTaskRepository taskRepository;
    private final AiRelayNodeRepository relayNodeRepository;
    private final AiTaskEventService taskEventService;
    private final AiAuditService auditService;
    private final SshDeployExecutor sshDeployExecutor;
    private final RemoteSelfReplicateExecutor remoteSelfReplicateExecutor;
    private final AiRelayGrantService relayGrantService;
    private final boolean nodeWhitelistEnabled;
    private final List<String> allowedNodeIds;
    private final DeployConcurrencyGate deployConcurrencyGate = new DeployConcurrencyGate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    AiRelayDeployServiceImpl(AiDeployRecordRepository deployRecordRepository,
                             AiTaskRepository taskRepository,
                             AiRelayNodeRepository relayNodeRepository,
                             AiTaskEventService taskEventService,
                             AiAuditService auditService,
                             SshDeployExecutor sshDeployExecutor,
                             RemoteSelfReplicateExecutor remoteSelfReplicateExecutor) {
        this(deployRecordRepository,
                taskRepository,
                relayNodeRepository,
                taskEventService,
                auditService,
                sshDeployExecutor,
                remoteSelfReplicateExecutor,
                null,
                booleanConfig("wdsavs.ai.relay.node-whitelist-enabled", "WDSAVS_AI_RELAY_NODE_WHITELIST_ENABLED", true),
                listConfig("wdsavs.ai.relay.allowed-node-ids", "WDSAVS_AI_RELAY_ALLOWED_NODE_IDS", List.of("*")));
    }

    @Autowired
    public AiRelayDeployServiceImpl(AiDeployRecordRepository deployRecordRepository,
                                    AiTaskRepository taskRepository,
                                    AiRelayNodeRepository relayNodeRepository,
                                    AiTaskEventService taskEventService,
                                    AiAuditService auditService,
                                    SshDeployExecutor sshDeployExecutor,
                                    RemoteSelfReplicateExecutor remoteSelfReplicateExecutor,
                                    AiRelayGrantService relayGrantService) {
        this(deployRecordRepository,
                taskRepository,
                relayNodeRepository,
                taskEventService,
                auditService,
                sshDeployExecutor,
                remoteSelfReplicateExecutor,
                relayGrantService,
                booleanConfig("wdsavs.ai.relay.node-whitelist-enabled", "WDSAVS_AI_RELAY_NODE_WHITELIST_ENABLED", true),
                listConfig("wdsavs.ai.relay.allowed-node-ids", "WDSAVS_AI_RELAY_ALLOWED_NODE_IDS", List.of("*")));
    }

    AiRelayDeployServiceImpl(AiDeployRecordRepository deployRecordRepository,
                             AiTaskRepository taskRepository,
                             AiRelayNodeRepository relayNodeRepository,
                             AiTaskEventService taskEventService,
                             AiAuditService auditService,
                             SshDeployExecutor sshDeployExecutor,
                             RemoteSelfReplicateExecutor remoteSelfReplicateExecutor,
                             boolean nodeWhitelistEnabled,
                             List<String> allowedNodeIds) {
        this(deployRecordRepository, taskRepository, relayNodeRepository, taskEventService, auditService,
                sshDeployExecutor, remoteSelfReplicateExecutor, null, nodeWhitelistEnabled, allowedNodeIds);
    }

    AiRelayDeployServiceImpl(AiDeployRecordRepository deployRecordRepository,
                             AiTaskRepository taskRepository,
                             AiRelayNodeRepository relayNodeRepository,
                             AiTaskEventService taskEventService,
                             AiAuditService auditService,
                             SshDeployExecutor sshDeployExecutor,
                             RemoteSelfReplicateExecutor remoteSelfReplicateExecutor,
                             AiRelayGrantService relayGrantService,
                             boolean nodeWhitelistEnabled,
                             List<String> allowedNodeIds) {
        this.deployRecordRepository = deployRecordRepository;
        this.taskRepository = taskRepository;
        this.relayNodeRepository = relayNodeRepository;
        this.taskEventService = taskEventService;
        this.auditService = auditService;
        this.sshDeployExecutor = sshDeployExecutor;
        this.remoteSelfReplicateExecutor = remoteSelfReplicateExecutor;
        this.relayGrantService = relayGrantService;
        this.nodeWhitelistEnabled = nodeWhitelistEnabled;
        this.allowedNodeIds = normalizeAllowedNodeIds(allowedNodeIds);
    }

    @Async
    @Override
    public void triggerDeployAsync(String taskId, AiTaskCreateRequest request) {
        AiTaskEntity task = findTaskWithRetry(taskId);
        String now = String.valueOf(System.currentTimeMillis());
        if (!isForceRecoverDeploy(request)) {
            Optional<AiDeployRecordEntity> reusableRecord = reusableDeployRecord(task, request);
            if (reusableRecord.isPresent()) {
                reuseDeployRecord(task, reusableRecord.get(), now);
                return;
            }
        }
        AiDeployRecordEntity deployRecord = createDeployRecord(task, request, now);
        if (!isNodeAllowed(task.getTargetNodeId())) {
            rejectWhitelistDenied(task, deployRecord, now);
            return;
        }
        Map<String, Object> payload = request.getPayload() == null ? Map.of() : request.getPayload();
        String deploymentBatchId = readString(payload, "deploymentBatchId", null);
        int deploymentBatchConcurrency = readInteger(payload, "deploymentBatchConcurrency", 1);
        if (!isBlank(deploymentBatchId)) {
            markWaitingForBatchSlot(task, deployRecord, deploymentBatchId, deploymentBatchConcurrency);
        }
        try (DeployConcurrencyGate.Permit ignored = deployConcurrencyGate.acquire(
                deploymentBatchId, deploymentBatchConcurrency)) {
            AiTaskEntity currentTask = findTaskWithRetry(taskId);
            if (isTerminalTask(currentTask)) {
                convergeDeployRecordAfterTerminalTask(
                        currentTask, deployRecord, String.valueOf(System.currentTimeMillis()));
                return;
            }
            task = currentTask;
            if (isSelfReplicateMode(deployRecord.getDeployMode())) {
                startSelfReplicate(task, request, deployRecord, String.valueOf(System.currentTimeMillis()));
                return;
            }
            executeCenterDeploy(task, request, deployRecord, String.valueOf(System.currentTimeMillis()));
        }
    }

    private void markWaitingForBatchSlot(AiTaskEntity task, AiDeployRecordEntity deployRecord,
                                         String batchId, int concurrency) {
        String now = String.valueOf(System.currentTimeMillis());
        task.setStatus("WAITING_DEPLOY");
        task.setCurrentStage("WAITING_BATCH_SLOT");
        task.setUpdateTime(now);
        Map<String, Object> result = readJson(task.getResultJson());
        result.put("deploymentBatchId", batchId);
        result.put("deploymentBatchConcurrency", Math.max(1, Math.min(32, concurrency)));
        result.put("deployProgress", Map.of(
                "phase", "WAITING_BATCH_SLOT",
                "progressPercent", 0,
                "updatedTime", System.currentTimeMillis(),
                "message", "等待批量部署并发许可"
        ));
        task.setResultJson(writeJson(result));
        taskRepository.save(task);
        Map<String, Object> event = eventPayload(
                task, deployRecord, task.getStatus(), task.getCurrentStage(), null);
        event.put("deploymentBatchId", batchId);
        event.put("deploymentBatchConcurrency", Math.max(1, Math.min(32, concurrency)));
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_WAITING_BATCH_SLOT",
                nextSequence(task.getTaskId()), event);
    }

    @Override
    public boolean autoCompleteReadyDeployments(String targetNodeId) {
        if (isBlank(targetNodeId)) {
            return false;
        }
        AiRelayNodeEntity node = relayNodeRepository.findByNodeId(targetNodeId).orElse(null);
        if (node == null || !"AVAILABLE".equalsIgnoreCase(valueOrDefault(node.getStatus(), ""))) {
            return false;
        }
        boolean completed = false;
        for (AiDeployRecordEntity deployRecord : deployRecordRepository.findAll()) {
            if (deployRecord == null) {
                continue;
            }
            String deployStatus = valueOrDefault(deployRecord.getStatus(), "");
            if (!"WAIT_REGISTER".equalsIgnoreCase(deployStatus)
                    && !"WAIT_HEALTH".equalsIgnoreCase(deployStatus)) {
                continue;
            }
            AiTaskEntity task = taskRepository.findByTaskId(deployRecord.getTaskId()).orElse(null);
            if (task == null || isTerminal(task.getStatus()) || "REGISTERED".equalsIgnoreCase(task.getCurrentStage())) {
                continue;
            }
            if (!matchesRegisteredDeploymentTarget(task, deployRecord, node, targetNodeId)) {
                continue;
            }
            DeployReportRequest request = new DeployReportRequest();
            request.setTaskId(task.getTaskId());
            request.setSessionId(task.getSessionId());
            request.setTargetNodeId(targetNodeId);
            request.setDeployMode(deployRecord.getDeployMode());
            request.setStatus("SUCCEEDED");
            request.setRelayEndpoint(node.getRelayEndpoint());
            request.setVersion(node.getVersion());
            request.setHealthPassed(Boolean.TRUE);
            request.setRegistered(Boolean.TRUE);
            request.setStdoutSummary(isBlank(deployRecord.getStdoutSummary())
                    ? "auto completed after register and heartbeat"
                    : deployRecord.getStdoutSummary());
            request.setStderrSummary(valueOrDefault(deployRecord.getStderrSummary(), ""));
            request.setExitCode(deployRecord.getExitCode() == null ? 0 : deployRecord.getExitCode());
            request.setRetryable(Boolean.FALSE);
            try {
                DeployReportResponse response = report(request);
                completed = completed || (response != null && Boolean.TRUE.equals(response.getAccepted()));
            } catch (Exception ignored) {
            }
        }
        return completed;
    }

    private boolean matchesRegisteredDeploymentTarget(AiTaskEntity task, AiDeployRecordEntity deployRecord,
                                                      AiRelayNodeEntity node, String registeredNodeId) {
        if (registeredNodeId.equals(deployRecord.getTargetNodeId())) {
            return true;
        }
        Map<String, Object> payload = readJson(task.getRequestPayloadJson());
        Map<String, Object> result = readJson(task.getResultJson());
        Integer expectedPort = readInteger(payload, "relayPort",
                readInteger(payload, "ccRelayPort", readInteger(payload, "targetRelayPort", nodePort(task.getTargetNodeId()))));
        if (expectedPort == null || node.getPort() == null || !expectedPort.equals(node.getPort())) {
            return false;
        }
        String expectedDirectory = readString(result, "resolvedRemoteDirectory",
                readString(payload, "remoteDirectory", readString(payload, "remoteWorkDir", null)));
        if (!isBlank(expectedDirectory) && isWorkspaceWithin(node.getWorkspaceRoot(), expectedDirectory)) {
            return true;
        }
        String expectedHost = readString(payload, "host",
                readString(payload, "sshHost", readString(payload, "targetHost", null)));
        return !isBlank(expectedHost) && expectedHost.equalsIgnoreCase(valueOrDefault(node.getHost(), ""));
    }

    private boolean isWorkspaceWithin(String workspaceRoot, String expectedDirectory) {
        if (isBlank(workspaceRoot) || isBlank(expectedDirectory)) {
            return false;
        }
        String workspace = workspaceRoot.replace('\\', '/').replaceAll("/+$", "");
        String expected = expectedDirectory.replace('\\', '/').replaceAll("/+$", "");
        return workspace.equals(expected) || workspace.startsWith(expected + "/");
    }

    @Override
    public DeployReportResponse report(DeployReportRequest request) {
        if (request == null || isBlank(request.getTaskId())) {
            throw new IllegalArgumentException("taskId is required");
        }
        AiTaskEntity task = findTask(request.getTaskId());
        String now = String.valueOf(System.currentTimeMillis());
        AiDeployRecordEntity deployRecord = latestDeployRecord(request.getTaskId())
                .orElseGet(() -> createDeployRecord(task, rebuildCreateRequest(task), now));

        String targetNodeId = valueOrDefault(request.getTargetNodeId(), task.getTargetNodeId());
        deployRecord.setSessionId(valueOrDefault(request.getSessionId(), task.getSessionId()));
        deployRecord.setTargetNodeId(targetNodeId);
        deployRecord.setDeployMode(valueOrDefault(request.getDeployMode(), deployRecord.getDeployMode()));
        deployRecord.setArtifactVersion(valueOrDefault(request.getVersion(), deployRecord.getArtifactVersion()));
        deployRecord.setStdoutSummary(valueOrDefault(request.getStdoutSummary(), deployRecord.getStdoutSummary()));
        deployRecord.setStderrSummary(valueOrDefault(request.getStderrSummary(), deployRecord.getStderrSummary()));
        deployRecord.setExitCode(request.getExitCode() == null ? deployRecord.getExitCode() : request.getExitCode());
        deployRecord.setRetryable(request.getRetryable() == null ? deployRecord.getRetryable() : request.getRetryable());
        deployRecord.setStartTime(deployRecord.getStartTime() == null ? now : deployRecord.getStartTime());
        deployRecord.setEndTime(now);
        deployRecord.setUpdateTime(now);

        boolean healthPassed = Boolean.TRUE.equals(request.getHealthPassed());
        boolean reportedRegistered = Boolean.TRUE.equals(request.getRegistered());
        boolean registered = reportedRegistered && isRegisteredInCenter(targetNodeId, request);
        boolean explicitFailed = "FAILED".equalsIgnoreCase(request.getStatus());

        if (healthPassed && registered && !explicitFailed) {
            deployRecord.setStatus("SUCCESS");
            deployRecordRepository.save(deployRecord);
            updateNodeStatus(targetNodeId, "AVAILABLE");
            updateTask(task, "SUCCESS", "REGISTERED", null, null, now, now);
            Map<String, Object> result = eventPayload(task, deployRecord, "SUCCESS", "REGISTERED", null);
            carryDeployProgress(task, result, "REGISTERED", 100);
            if (!isBlank(request.getRelayEndpoint())) {
                result.put("relayEndpoint", request.getRelayEndpoint());
            }
            if (!isBlank(request.getVersion())) {
                result.put("version", request.getVersion());
            }
            RelayAccessDecisionResponse grantDecision = activateLinkedGrantIfReady(task, result);
            task.setResultJson(writeJson(result));
            taskRepository.save(task);
            taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_REGISTERED", nextSequence(task.getTaskId()), result);
            syncParentTaskFromChild(task, "DEPLOY_REGISTERED", "SUCCESS", "REGISTERED", result, null, null, now);
            if (grantDecision != null && "ALLOW".equals(grantDecision.getDecision())) {
                Map<String, Object> grantPayload = grantActivationEvent(grantDecision);
                taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_GRANT_ACTIVATED", nextSequence(task.getTaskId()), grantPayload);
                syncParentTaskFromChild(task, "DEPLOY_GRANT_ACTIVATED", "PARTIAL_SUCCESS", "GRANT_ACTIVATED", grantPayload, null, null, now);
            }
            auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                    AuditEventType.DEPLOY_SUCCEEDED, "SUCCESS", result, "RELAY", valueOrDefault(request.getTargetNodeId(), task.getTargetNodeId()));
            return new DeployReportResponse(true, "REGISTERED");
        }

        if (explicitFailed && shouldFallbackToCenterDeployOnReport(task, deployRecord, healthPassed, registered)) {
            deployRecord.setStatus("SELF_REPLICATE_FAILED");
            deployRecordRepository.save(deployRecord);
            updateTask(task, "WAITING_DEPLOY", "CENTER_DEPLOY_FALLBACK", "SELF_REPLICATE_FAILED", summarize(request.getStderrSummary()), now, null);
            Map<String, Object> fallbackEvent = eventPayload(task, deployRecord, "WAITING_DEPLOY", "CENTER_DEPLOY_FALLBACK", "SELF_REPLICATE_FAILED");
            taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_FALLBACK_STARTED", nextSequence(task.getTaskId()), fallbackEvent);
            auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                    AuditEventType.DEPLOY_FAILED, "SELF_REPLICATE_FAILED", fallbackEvent, "RELAY", valueOrDefault(request.getTargetNodeId(), task.getTargetNodeId()));
            boolean success = executeCenterFallback(task, null, now, summarize(request.getStderrSummary()), deployRecord);
            return new DeployReportResponse(true, success ? "WAIT_REGISTER" : "CENTER_DEPLOY_FAILED");
        }

        if (explicitFailed) {
            deployRecord.setStatus("FAILED");
            deployRecordRepository.save(deployRecord);
            updateNodeStatus(targetNodeId, "UNAVAILABLE");
            String failStage = healthPassed ? "REGISTER_FAILED" : "HEALTH_FAILED";
            String errorCode = isSelfReplicateMode(deployRecord.getDeployMode()) ? "SELF_REPLICATE_FAILED" : "REMOTE_CC_UNAVAILABLE";
            updateTask(task, "FAILED", failStage, errorCode, summarize(request.getStderrSummary()), now, now);
            Map<String, Object> result = eventPayload(task, deployRecord, "FAILED", failStage, errorCode);
            task.setResultJson(writeJson(result));
            taskRepository.save(task);
            taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_FAILED", nextSequence(task.getTaskId()), result);
            syncParentTaskFromChild(task, "DEPLOY_FAILED", "FAILED", failStage, result, errorCode, summarize(request.getStderrSummary()), now);
            auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                    AuditEventType.DEPLOY_FAILED, "FAILED", result, "RELAY", valueOrDefault(request.getTargetNodeId(), task.getTargetNodeId()));
            executeRollback(task, deployRecord, rollbackRemoteDirectory(task), failStage, now);
            return new DeployReportResponse(true, healthPassed ? "WAIT_REGISTER" : "WAIT_HEALTH");
        }

        String stage = registered ? "REGISTERED_PENDING_HEALTH" : (healthPassed ? "WAIT_REGISTER" : "WAIT_HEALTH");
        String status = registered && healthPassed ? "SUCCESS" : "PARTIAL_SUCCESS";
        deployRecord.setStatus(registered ? (healthPassed ? "SUCCESS" : "WAIT_HEALTH") : "WAIT_REGISTER");
        deployRecordRepository.save(deployRecord);
        updateNodeStatus(targetNodeId, registered ? "REGISTERING" : "DEPLOYING");
        updateTask(task, status, stage, null, null, now, healthPassed && registered ? now : null);
        Map<String, Object> result = eventPayload(task, deployRecord, status, stage, null);
        if (!isBlank(request.getRelayEndpoint())) {
            result.put("relayEndpoint", request.getRelayEndpoint());
        }
        if (reportedRegistered && !registered) {
            result.put("registeredInCenter", false);
            result.put("registrationError", "Target relay is not registered in center: " + targetNodeId);
        }
        task.setResultJson(writeJson(result));
        taskRepository.save(task);
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_REPORTED", nextSequence(task.getTaskId()), result);
        return new DeployReportResponse(true, registered ? "WAIT_HEALTH" : "WAIT_REGISTER");
    }

    @Override
    public boolean resumeCenterDeploy(String taskId) {
        AiTaskEntity task = findTask(taskId);
        if (!"DEPLOY_RELAY".equalsIgnoreCase(task.getTaskType())) {
            throw new IllegalArgumentException("Task is not a DEPLOY_RELAY task: " + taskId);
        }
        if (!"SSH_CREDENTIAL_REQUIRED".equalsIgnoreCase(task.getCurrentStage())) {
            throw new IllegalStateException("Task is not waiting for SSH credentials: " + taskId);
        }
        String now = String.valueOf(System.currentTimeMillis());
        Map<String, Object> payload = readJson(task.getRequestPayloadJson());
        payload.put("sshCredentialPreflightCompleted", true);
        payload.put("sshCredentialPreflightRequiredOnFallback", false);
        task.setRequestPayloadJson(writeJson(payload));
        updateTask(task, "WAITING_DEPLOY", "CENTER_DEPLOY_RESUMED", null, null, now, null);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("status", task.getStatus());
        event.put("currentStage", task.getCurrentStage());
        event.put("targetNodeId", task.getTargetNodeId());
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_RESUMED",
                nextSequence(task.getTaskId()), event);
        AiTaskCreateRequest request = rebuildCreateRequest(task);
        request.getPayload().put("deployMode", DeployMode.CENTER_DEPLOY.name());
        AiDeployRecordEntity record = createDeployRecord(task, request, now);
        return executeCenterDeploy(task, request, record, now);
    }

    private void startSelfReplicate(AiTaskEntity task, AiTaskCreateRequest request, AiDeployRecordEntity deployRecord, String now) {
        deployRecord.setStatus("SELF_REPLICATING");
        deployRecord.setUpdateTime(now);
        deployRecordRepository.save(deployRecord);
        updateNodeStatus(task.getTargetNodeId(), "DEPLOYING");
        updateTask(task, "RUNNING", "SELF_REPLICATING", null, null, now, null);
        Map<String, Object> payload = eventPayload(task, deployRecord, "RUNNING", "SELF_REPLICATING", null);
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_SELF_REPLICATING", nextSequence(task.getTaskId()), payload);
        auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                AuditEventType.DEPLOY_STARTED, "SELF_REPLICATING", deployDetail(request, deployRecord), "SYSTEM", "AI_AGENT_CENTER");

        try {
            String sourceRelayEndpoint = resolveSourceRelayEndpoint(task, request);
            SelfReplicateRequest selfReplicateRequest = toSelfReplicateRequest(task, request, deployRecord);
            AtomicLong lastProgressEventTime = new AtomicLong(0L);
            AtomicReference<String> lastProgressPhase = new AtomicReference<>();
            SelfReplicateResponse response = remoteSelfReplicateExecutor.execute(
                    sourceRelayEndpoint,
                    selfReplicateRequest,
                    progress -> reportSelfReplicateProgress(
                            task.getTaskId(), deployRecord.getDeployId(), progress,
                            lastProgressEventTime, lastProgressPhase));
            handleSelfReplicateResponse(task, request, deployRecord, sourceRelayEndpoint, response);
        } catch (Exception e) {
            handleSelfReplicateFailure(task, request, deployRecord, summarize(e.getMessage()));
        }
    }

    private boolean executeCenterDeploy(AiTaskEntity task, AiTaskCreateRequest request, AiDeployRecordEntity deployRecord, String startTime) {
        try {
            updateNodeStatus(task.getTargetNodeId(), "DEPLOYING");
            updateTask(task, "RUNNING", "SSH_DISTRIBUTING", null, null, startTime, null);
            taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_STARTED", nextSequence(task.getTaskId()),
                    eventPayload(task, deployRecord, "RUNNING", "SSH_DISTRIBUTING", null));
            auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                    AuditEventType.DEPLOY_STARTED, "RUNNING", deployDetail(request, deployRecord), "SYSTEM", "AI_AGENT_CENTER");

            SshDeployRequest sshRequest = toSshDeployRequest(task, request);
            AtomicLong lastProgressEventTime = new AtomicLong(0L);
            AtomicReference<String> lastProgressPhase = new AtomicReference<>();
            String progressTaskId = task.getTaskId();
            DeployProgressAccumulator progressAccumulator = new DeployProgressAccumulator(
                    "center-deploy:" + deployRecord.getDeployId(),
                    progressTaskId,
                    "CC_CENTER",
                    task.getTargetNodeId());
            sshRequest.setProgressListener(progress -> reportSelfReplicateProgress(
                    progressTaskId,
                    deployRecord.getDeployId(),
                    progressAccumulator.update(progress),
                    lastProgressEventTime,
                    lastProgressPhase));
            SshDeployResult result = sshDeployExecutor.deploy(sshRequest);
            String finishTime = String.valueOf(System.currentTimeMillis());
            deployRecord.setExitCode(result.getExitCode());
            deployRecord.setStdoutSummary(result.getStdoutSummary());
            deployRecord.setStderrSummary(result.getStderrSummary());
            deployRecord.setRetryable(!result.isSuccess());
            deployRecord.setEndTime(finishTime);
            deployRecord.setUpdateTime(finishTime);

            AiTaskEntity latestTask = taskRepository.findByTaskId(task.getTaskId()).orElse(task);
            if (isTerminalTask(latestTask)) {
                convergeDeployRecordAfterTerminalTask(latestTask, deployRecord, finishTime);
                return isCompletedDeployment(latestTask);
            }
            task = latestTask;

            if (!result.isSuccess()) {
                if (isSshAuthenticationFailure(result) && !sshCredentialPreflightCompleted(task)) {
                    deployRecord.setStatus("SSH_CREDENTIAL_REQUIRED");
                    deployRecordRepository.save(deployRecord);
                    waitForSshCredential(task, deployRecord, finishTime, summarize(result.getStderrSummary()));
                    return false;
                }
                deployRecord.setStatus("FAILED");
                deployRecordRepository.save(deployRecord);
                updateTask(task, "FAILED", "SSH_FAILED", "CENTER_DEPLOY_FAILED", summarize(result.getStderrSummary()), finishTime, finishTime);
                Map<String, Object> failurePayload = eventPayload(task, deployRecord, "FAILED", "SSH_FAILED", "CENTER_DEPLOY_FAILED");
                carryDeployProgress(task, failurePayload, "FAILED", null);
                task.setResultJson(writeJson(failurePayload));
                taskRepository.save(task);
                taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_FAILED", nextSequence(task.getTaskId()), failurePayload);
                syncParentTaskFromChild(task, "DEPLOY_FAILED", "FAILED", "SSH_FAILED", failurePayload,
                        "CENTER_DEPLOY_FAILED", summarize(result.getStderrSummary()), finishTime);
                auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                        AuditEventType.DEPLOY_FAILED, "FAILED", deployResultDetail(request, deployRecord, result), "SYSTEM", "AI_AGENT_CENTER");
                executeRollback(task, deployRecord, valueOrDefault(result.getResolvedRemoteDirectory(), rollbackRemoteDirectory(task)), "SSH_FAILED", finishTime);
                return false;
            }

            deployRecord.setStatus("WAIT_REGISTER");
            deployRecordRepository.save(deployRecord);
            updateTask(task, "PARTIAL_SUCCESS", "WAIT_REGISTER", null, null, finishTime, null);
            Map<String, Object> waitRegisterResult = eventPayload(task, deployRecord, "PARTIAL_SUCCESS", "WAIT_REGISTER", null);
            carryDeployProgress(task, waitRegisterResult, "WAIT_REGISTER", 100);
            task.setResultJson(writeJson(waitRegisterResult));
            taskRepository.save(task);
            Map<String, Object> waitRegisterPayload = eventPayload(task, deployRecord, "PARTIAL_SUCCESS", "WAIT_REGISTER", null);
            taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_SCRIPT_SUCCEEDED", nextSequence(task.getTaskId()),
                    waitRegisterPayload);
            syncParentTaskFromChild(task, "DEPLOY_SCRIPT_SUCCEEDED", "PARTIAL_SUCCESS", "WAIT_REGISTER", waitRegisterPayload, null, null, finishTime);
            return true;
        } catch (Exception e) {
            String finishTime = String.valueOf(System.currentTimeMillis());
            deployRecord.setStatus("FAILED");
            deployRecord.setStderrSummary(summarize(e.getMessage()));
            deployRecord.setRetryable(Boolean.TRUE);
            deployRecord.setEndTime(finishTime);
            deployRecord.setUpdateTime(finishTime);
            AiTaskEntity latestTask = taskRepository.findByTaskId(task.getTaskId()).orElse(task);
            if (isTerminalTask(latestTask)) {
                convergeDeployRecordAfterTerminalTask(latestTask, deployRecord, finishTime);
                return isCompletedDeployment(latestTask);
            }
            task = latestTask;
            deployRecordRepository.save(deployRecord);
            updateTask(task, "FAILED", "DEPLOY_EXCEPTION", "CENTER_DEPLOY_FAILED", summarize(e.getMessage()), finishTime, finishTime);
            Map<String, Object> exceptionPayload = eventPayload(task, deployRecord, "FAILED", "DEPLOY_EXCEPTION", "CENTER_DEPLOY_FAILED");
            carryDeployProgress(task, exceptionPayload, "FAILED", null);
            taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_FAILED", nextSequence(task.getTaskId()),
                    exceptionPayload);
            syncParentTaskFromChild(task, "DEPLOY_FAILED", "FAILED", "DEPLOY_EXCEPTION", exceptionPayload, "CENTER_DEPLOY_FAILED", summarize(e.getMessage()), finishTime);
            Map<String, Object> detail = deployDetail(request, deployRecord);
            detail.put("errorMessage", summarize(e.getMessage()));
            auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                    AuditEventType.DEPLOY_FAILED, "FAILED", detail, "SYSTEM", "AI_AGENT_CENTER");
            return false;
        }
    }

    private void executeRollback(AiTaskEntity task, AiDeployRecordEntity deployRecord, String remoteDirectory,
                                 String failStage, String now) {
        Map<String, Object> rollbackDetail = rollbackDetail(task, deployRecord, remoteDirectory, failStage);
        boolean invalidRegistrationRolledBack = rollbackInvalidRegistration(deployRecord, now);
        rollbackDetail.put("invalidRegistrationRolledBack", invalidRegistrationRolledBack);
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_ROLLBACK_EXECUTED",
                nextSequence(task.getTaskId()), rollbackDetail);
        auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                AuditEventType.ROLLBACK_EXECUTED, failStage, rollbackDetail, "SYSTEM", "AI_AGENT_CENTER");
    }

    private boolean rollbackInvalidRegistration(AiDeployRecordEntity deployRecord, String now) {
        if (deployRecord == null || isBlank(deployRecord.getTargetNodeId())) {
            return false;
        }
        Optional<AiRelayNodeEntity> nodeOptional = relayNodeRepository.findByNodeId(deployRecord.getTargetNodeId());
        if (nodeOptional.isEmpty()) {
            return false;
        }
        AiRelayNodeEntity node = nodeOptional.get();
        long deployStart = parseLong(deployRecord.getStartTime());
        long registerTime = parseLong(node.getRegisterTime());
        if (deployStart > 0 && registerTime >= deployStart) {
            relayNodeRepository.delete(node);
            return true;
        }
        String status = valueOrDefault(node.getStatus(), "").trim().toUpperCase();
        if ("REGISTERING".equals(status) || "DEPLOYING".equals(status)) {
            node.setStatus("UNAVAILABLE");
            node.setUpdateTime(now);
            relayNodeRepository.save(node);
        }
        return false;
    }

    private Map<String, Object> rollbackDetail(AiTaskEntity task, AiDeployRecordEntity deployRecord,
                                               String remoteDirectory, String failStage) {
        Map<String, Object> detail = eventPayload(task, deployRecord, "FAILED", failStage, "ROLLBACK_EXECUTED");
        if (remoteDirectory != null) {
            detail.put("remoteDirectory", remoteDirectory);
        }
        Map<String, Object> rollbackTargets = new LinkedHashMap<>();
        rollbackTargets.put("scriptPath", deployRecord == null ? null : deployRecord.getScriptPath());
        rollbackTargets.put("artifactPath", deployRecord == null ? null : deployRecord.getArtifactPath());
        rollbackTargets.put("remoteDirectory", remoteDirectory);
        detail.put("rollbackTargets", rollbackTargets);
        return detail;
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

    private String rollbackRemoteDirectory(AiTaskEntity task) {
        if (task == null) {
            return null;
        }
        AiTaskCreateRequest rebuild = rebuildCreateRequest(task);
        return rebuild.getPayload() == null ? null : readString(rebuild.getPayload(), "remoteDirectory", null);
    }

    private AiDeployRecordEntity createDeployRecord(AiTaskEntity task, AiTaskCreateRequest request, String now) {
        AiDeployRecordEntity entity = new AiDeployRecordEntity();
        entity.setDeployId(UUID.randomUUID().toString());
        entity.setTaskId(task.getTaskId());
        entity.setSessionId(task.getSessionId());
        entity.setTargetNodeId(task.getTargetNodeId());
        entity.setDeployMode(readString(request == null ? null : request.getPayload(), "deployMode", DeployMode.CENTER_DEPLOY.name()));
        entity.setArtifactVersion(readString(request == null ? null : request.getPayload(), "artifactVersion", null));
        entity.setScriptPath(readString(request == null ? null : request.getPayload(), "scriptPath", null));
        entity.setArtifactPath(readString(request == null ? null : request.getPayload(), "artifactPath", null));
        entity.setStatus("WAITING_DEPLOY");
        entity.setRetryable(Boolean.FALSE);
        entity.setStartTime(now);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        return deployRecordRepository.save(entity);
    }

    private AiTaskCreateRequest rebuildCreateRequest(AiTaskEntity task) {
        AiTaskCreateRequest request = new AiTaskCreateRequest();
        request.setSessionId(task.getSessionId());
        request.setRequestId(task.getRequestId());
        request.setTaskType(task.getTaskType());
        request.setSourceNodeId(task.getSourceNodeId());
        request.setTargetNodeId(task.getTargetNodeId());
        request.setPayload(readJson(task.getRequestPayloadJson()));
        return request;
    }

    private AiTaskCreateRequest copyCreateRequest(AiTaskCreateRequest request) {
        AiTaskCreateRequest copy = new AiTaskCreateRequest();
        copy.setSessionId(request.getSessionId());
        copy.setRequestId(request.getRequestId());
        copy.setTaskType(request.getTaskType());
        copy.setSourceNodeId(request.getSourceNodeId());
        copy.setTargetNodeId(request.getTargetNodeId());
        copy.setTimeoutMs(request.getTimeoutMs());
        copy.setPayload(request.getPayload() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getPayload()));
        return copy;
    }

    private void rejectWhitelistDenied(AiTaskEntity task, AiDeployRecordEntity deployRecord, String now) {
        deployRecord.setStatus("WHITELIST_DENIED");
        deployRecord.setUpdateTime(now);
        deployRecord.setEndTime(now);
        deployRecordRepository.save(deployRecord);
        String message = "Target node is not in whitelist: " + task.getTargetNodeId();
        updateTask(task, "FAILED", "WHITELIST_DENIED", "AUTH_DENIED", message, now, now);
        Map<String, Object> payload = eventPayload(task, deployRecord, "FAILED", "WHITELIST_DENIED", "AUTH_DENIED");
        payload.put("message", message);
        task.setResultJson(writeJson(payload));
        taskRepository.save(task);
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_REJECTED", nextSequence(task.getTaskId()), payload);
        syncParentTaskFromChild(task, "DEPLOY_REJECTED", "FAILED", "WHITELIST_DENIED", payload, "AUTH_DENIED", message, now);
        auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                AuditEventType.DEPLOY_FAILED, "FAILED", payload, "RELAY", task.getTargetNodeId());
    }

    private boolean isNodeAllowed(String targetNodeId) {
        if (!nodeWhitelistEnabled) {
            return true;
        }
        if (isBlank(targetNodeId)) {
            return false;
        }
        if (allowedNodeIds.isEmpty() || allowedNodeIds.contains("*")) {
            return true;
        }
        return allowedNodeIds.contains(targetNodeId.trim());
    }

    private Optional<AiDeployRecordEntity> reusableDeployRecord(AiTaskEntity task, AiTaskCreateRequest request) {
        Optional<AiDeployRecordEntity> latest = latestDeployRecord(task.getTaskId());
        if (latest.isEmpty() || isTerminalDeployStatus(latest.get().getStatus())) {
            return Optional.empty();
        }
        String requestedMode = readString(request == null ? null : request.getPayload(), "deployMode", DeployMode.CENTER_DEPLOY.name());
        String existingMode = latest.get().getDeployMode();
        if (!valueOrDefault(existingMode, "").equalsIgnoreCase(valueOrDefault(requestedMode, ""))) {
            return Optional.empty();
        }
        String requestedTarget = valueOrDefault(request == null ? null : request.getTargetNodeId(), task.getTargetNodeId());
        if (!valueOrDefault(latest.get().getTargetNodeId(), "").equals(valueOrDefault(requestedTarget, ""))) {
            return Optional.empty();
        }
        return latest;
    }

    private void reuseDeployRecord(AiTaskEntity task, AiDeployRecordEntity deployRecord, String now) {
        Map<String, Object> payload = eventPayload(task, deployRecord, task.getStatus(),
                valueOrDefault(task.getCurrentStage(), deployRecord.getStatus()), null);
        payload.put("deduplicated", Boolean.TRUE);
        payload.put("reuseDeployId", deployRecord.getDeployId());
        task.setUpdateTime(now);
        task.setResultJson(writeJson(payload));
        taskRepository.save(task);
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_DEDUP_REUSED", nextSequence(task.getTaskId()), payload);
        auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                AuditEventType.DEPLOY_STARTED, "DEDUP_REUSED", payload, "SYSTEM", "AI_AGENT_CENTER");
        syncParentTaskFromChild(task, "DEPLOY_DEDUP_REUSED", task.getStatus(),
                valueOrDefault(task.getCurrentStage(), deployRecord.getStatus()), payload, null, null, now);
    }

    private boolean isTerminalDeployStatus(String status) {
        String normalized = valueOrDefault(status, "").trim().toUpperCase();
        return "SUCCESS".equals(normalized) || "FAILED".equals(normalized)
                || "CENTER_DEPLOY_FAILED".equals(normalized) || "SELF_REPLICATE_FAILED".equals(normalized)
                || "REMOTE_CC_UNAVAILABLE".equals(normalized);
    }

    private boolean allowCenterFallback(AiTaskEntity task) {
        Map<String, Object> payload = readJson(task.getRequestPayloadJson());
        Object flag = payload.get("enableCenterFallback");
        if (flag == null) {
            return true;
        }
        if (flag instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(flag));
    }

    private boolean isForceRecoverDeploy(AiTaskCreateRequest request) {
        return readBoolean(request == null ? null : request.getPayload(), "forceRecoverDeploy", false);
    }

    private boolean shouldFallbackToCenterDeployOnReport(AiTaskEntity task, AiDeployRecordEntity deployRecord,
                                                         boolean healthPassed, boolean registered) {
        if (!isSelfReplicateMode(deployRecord == null ? null : deployRecord.getDeployMode()) || !allowCenterFallback(task)) {
            return false;
        }
        if (healthPassed || registered) {
            return false;
        }
        String deployStatus = valueOrDefault(deployRecord == null ? null : deployRecord.getStatus(), "").trim().toUpperCase();
        return !"WAIT_REGISTER".equals(deployStatus)
                && !"WAIT_HEALTH".equals(deployStatus)
                && !"SUCCESS".equals(deployStatus);
    }

    private String resolveSourceRelayEndpoint(AiTaskEntity task, AiTaskCreateRequest request) {
        Map<String, Object> payload = request == null || request.getPayload() == null ? Map.of() : request.getPayload();
        String endpoint = readString(payload, "sourceRelayEndpoint", null);
        if (!isBlank(endpoint)) {
            return endpoint;
        }
        String sourceNodeId = valueOrDefault(request == null ? null : request.getSourceNodeId(), task.getSourceNodeId());
        if (isBlank(sourceNodeId)) {
            throw new IllegalArgumentException("sourceNodeId is required for SELF_REPLICATE mode");
        }
        AiRelayNodeEntity sourceNode = relayNodeRepository.findByNodeId(sourceNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Source relay node not found: " + sourceNodeId));
        if (isBlank(sourceNode.getRelayEndpoint())) {
            throw new IllegalArgumentException("Source relay endpoint is unavailable for node: " + sourceNodeId);
        }
        return sourceNode.getRelayEndpoint();
    }

    private boolean isSelfReplicateMode(String deployMode) {
        return DeployMode.SELF_REPLICATE.name().equalsIgnoreCase(valueOrDefault(deployMode, ""));
    }

    private SelfReplicateRequest toSelfReplicateRequest(AiTaskEntity task, AiTaskCreateRequest request, AiDeployRecordEntity deployRecord) {
        SshDeployRequest sshRequest = toSshDeployRequest(task, request);
        SelfReplicateRequest selfReplicateRequest = new SelfReplicateRequest();
        selfReplicateRequest.setTaskId(task.getTaskId());
        selfReplicateRequest.setSessionId(task.getSessionId());
        selfReplicateRequest.setSourceNodeId(task.getSourceNodeId());
        selfReplicateRequest.setTargetNodeId(task.getTargetNodeId());
        selfReplicateRequest.setDeployMode(deployRecord.getDeployMode());
        selfReplicateRequest.setHost(sshRequest.getHost());
        selfReplicateRequest.setPort(sshRequest.getPort());
        selfReplicateRequest.setUsername(sshRequest.getUsername());
        selfReplicateRequest.setRelayPort(sshRequest.getRelayPort());
        selfReplicateRequest.setReplaceExistingRelay(sshRequest.getReplaceExistingRelay());
        selfReplicateRequest.setScriptPath(sshRequest.getScriptPath());
        selfReplicateRequest.setArtifactPath(sshRequest.getArtifactPath());
        selfReplicateRequest.setRemoteDirectory(sshRequest.getRemoteDirectory());
        selfReplicateRequest.setTimeoutMs(sshRequest.getTimeoutMs());
        Map<String, Object> payload = request == null || request.getPayload() == null ? Map.of() : request.getPayload();
        selfReplicateRequest.setProgressPollIntervalMs(readLong(payload, "progressPollIntervalMs", 2000L, 500L));
        selfReplicateRequest.setProgressHeartbeatIntervalMs(readLong(payload, "progressHeartbeatIntervalMs", 10000L, 1000L));
        selfReplicateRequest.setCommandArguments(sshRequest.getCommandArguments());
        selfReplicateRequest.setArtifactVersion(readString(payload, "artifactVersion", null));
        return selfReplicateRequest;
    }

    private void handleSelfReplicateResponse(AiTaskEntity task, AiTaskCreateRequest request, AiDeployRecordEntity deployRecord,
                                             String sourceRelayEndpoint, SelfReplicateResponse response) {
        if (response == null) {
            handleSelfReplicateFailure(task, request, deployRecord, "Remote self replicate returned empty response");
            return;
        }
        String finishTime = String.valueOf(System.currentTimeMillis());
        deployRecord.setExitCode(response.getExitCode());
        deployRecord.setStdoutSummary(summarize(response.getStdoutSummary()));
        deployRecord.setStderrSummary(summarize(response.getStderrSummary()));
        deployRecord.setRetryable(!Boolean.TRUE.equals(response.getSuccess()));
        deployRecord.setEndTime(finishTime);
        deployRecord.setUpdateTime(finishTime);

        AiTaskEntity latestTask = taskRepository.findByTaskId(task.getTaskId()).orElse(task);
        if (isTerminalTask(latestTask)) {
            if (isCompletedDeployment(latestTask) && Boolean.TRUE.equals(response.getAccepted())
                    && Boolean.TRUE.equals(response.getSuccess())) {
                deployRecord.setStatus("SUCCESS");
                deployRecord.setRetryable(Boolean.FALSE);
                deployRecordRepository.save(deployRecord);
                Map<String, Object> confirmation = eventPayload(latestTask, deployRecord, "SUCCESS", "REGISTERED", null);
                confirmation.put("sourceRelayEndpoint", sourceRelayEndpoint);
                confirmation.put("responseConfirmedAfterTerminalState", true);
                taskEventService.appendEvent(latestTask.getTaskId(), latestTask.getSessionId(),
                        "DEPLOY_SELF_REPLICATE_RESPONSE_CONFIRMED", nextSequence(latestTask.getTaskId()), confirmation);
            }
            return;
        }
        task = latestTask;

        if (Boolean.TRUE.equals(response.getAccepted()) && Boolean.TRUE.equals(response.getSuccess())) {
            deployRecord.setStatus("WAIT_REGISTER");
            deployRecordRepository.save(deployRecord);
            updateTask(task, "PARTIAL_SUCCESS", "WAIT_REGISTER", null, null, finishTime, null);
            Map<String, Object> result = eventPayload(task, deployRecord, "PARTIAL_SUCCESS", "WAIT_REGISTER", null);
            result.put("sourceRelayEndpoint", sourceRelayEndpoint);
            result.put("resolvedRelayPort", response.getResolvedRelayPort());
            result.put("resolvedRemoteDirectory", response.getResolvedRemoteDirectory());
            carryDeployProgress(task, result, "WAIT_REGISTER", 100);
            task.setResultJson(writeJson(result));
            taskRepository.save(task);
            taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_SELF_REPLICATE_SUCCEEDED",
                    nextSequence(task.getTaskId()), result);
            return;
        }

        handleSelfReplicateFailure(task, request, deployRecord, summarize(response.getStderrSummary()));
    }

    private void handleSelfReplicateFailure(AiTaskEntity task, AiTaskCreateRequest request,
                                            AiDeployRecordEntity deployRecord, String errorMessage) {
        String finishTime = String.valueOf(System.currentTimeMillis());
        deployRecord.setStatus("SELF_REPLICATE_FAILED");
        deployRecord.setRetryable(Boolean.TRUE);
        deployRecord.setStderrSummary(summarize(errorMessage));
        deployRecord.setEndTime(finishTime);
        deployRecord.setUpdateTime(finishTime);
        deployRecordRepository.save(deployRecord);

        if (allowCenterFallback(task)) {
            executeCenterFallback(task, request, finishTime, summarize(errorMessage), deployRecord);
            return;
        }

        updateNodeStatus(task.getTargetNodeId(), "UNAVAILABLE");
        updateTask(task, "FAILED", "SELF_REPLICATE_FAILED", "SELF_REPLICATE_FAILED", summarize(errorMessage), finishTime, finishTime);
        Map<String, Object> result = eventPayload(task, deployRecord, "FAILED", "SELF_REPLICATE_FAILED", "SELF_REPLICATE_FAILED");
        carryDeployProgress(task, result, "FAILED", null);
        task.setResultJson(writeJson(result));
        taskRepository.save(task);
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_FAILED", nextSequence(task.getTaskId()), result);
        auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                AuditEventType.DEPLOY_FAILED, "FAILED", result, "RELAY", valueOrDefault(task.getTargetNodeId(), task.getTargetNodeId()));
    }

    private boolean executeCenterFallback(AiTaskEntity task, AiTaskCreateRequest request, String now,
                                          String errorMessage, AiDeployRecordEntity selfReplicateRecord) {
        updateTask(task, "WAITING_DEPLOY", "CENTER_DEPLOY_FALLBACK", "SELF_REPLICATE_FAILED", errorMessage, now, null);
        Map<String, Object> fallbackEvent = eventPayload(task, selfReplicateRecord,
                "WAITING_DEPLOY", "CENTER_DEPLOY_FALLBACK", "SELF_REPLICATE_FAILED");
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_FALLBACK_STARTED", nextSequence(task.getTaskId()), fallbackEvent);
        auditService.record(task.getSessionId(), task.getTaskId(), task.getSourceNodeId(), task.getTargetNodeId(),
                AuditEventType.DEPLOY_FAILED, "SELF_REPLICATE_FAILED", fallbackEvent, "RELAY", valueOrDefault(task.getTargetNodeId(), task.getTargetNodeId()));
        AiTaskCreateRequest fallbackRequest = request == null ? rebuildCreateRequest(task) : copyCreateRequest(request);
        Map<String, Object> fallbackPayload = fallbackRequest.getPayload() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(fallbackRequest.getPayload());
        fallbackPayload.put("deployMode", DeployMode.CENTER_DEPLOY.name());
        fallbackRequest.setPayload(fallbackPayload);
        AiDeployRecordEntity fallbackRecord = createDeployRecord(task, fallbackRequest, now);
        return executeCenterDeploy(task, fallbackRequest, fallbackRecord, now);
    }

    private void carryDeployProgress(AiTaskEntity task, Map<String, Object> target,
                                     String phase, Integer progressPercent) {
        if (task == null || target == null) {
            return;
        }
        Object existing = readJson(task.getResultJson()).get("deployProgress");
        if (!(existing instanceof Map<?, ?> progressMap)) {
            return;
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progressMap.forEach((key, value) -> progress.put(String.valueOf(key), value));
        progress.put("phase", phase);
        if (progressPercent != null) {
            progress.put("progressPercent", progressPercent);
        }
        progress.put("estimatedRemainingMs", 0L);
        progress.put("updatedTime", System.currentTimeMillis());
        progress.put("stalled", false);
        target.put("deployProgress", progress);
    }

    private static final class DeployProgressAccumulator {
        private final String operationId;
        private final String taskId;
        private final String sourceNodeId;
        private final String targetNodeId;
        private final long startedTime = System.currentTimeMillis();
        private long previousBytes;
        private long previousTime = startedTime;
        private Long rollingSpeed;
        private Long previousTotalBytes;

        private DeployProgressAccumulator(String operationId, String taskId, String sourceNodeId, String targetNodeId) {
            this.operationId = operationId;
            this.taskId = taskId;
            this.sourceNodeId = sourceNodeId;
            this.targetNodeId = targetNodeId;
        }

        private synchronized SelfReplicateProgress update(SshDeployProgress update) {
            long updatedTime = update.getUpdatedTime() == null ? System.currentTimeMillis() : update.getUpdatedTime();
            long currentBytes = update.getBytesTransferred() == null ? 0L : update.getBytesTransferred();
            boolean transferPhase = "COPYING_ARTIFACT".equalsIgnoreCase(update.getPhase());
            if (!transferPhase && currentBytes == 0L && previousBytes > 0L) {
                currentBytes = previousBytes;
            }
            long deltaTime = Math.max(1L, updatedTime - previousTime);
            long instantSpeed = Math.max(0L, currentBytes - previousBytes) * 1000L / deltaTime;
            if (!transferPhase) {
                rollingSpeed = null;
            } else if (rollingSpeed == null || rollingSpeed <= 0L) {
                rollingSpeed = instantSpeed > 0L ? Long.valueOf(instantSpeed) : null;
            } else {
                rollingSpeed = Long.valueOf(Math.round(rollingSpeed * 0.7d + instantSpeed * 0.3d));
            }
            Long totalBytes = update.getTotalBytes() == null ? previousTotalBytes : update.getTotalBytes();
            Long remainingMs = totalBytes != null && totalBytes > currentBytes && rollingSpeed != null && rollingSpeed > 0L
                    ? Math.max(0L, totalBytes - currentBytes) * 1000L / rollingSpeed
                    : null;
            previousBytes = currentBytes;
            previousTime = updatedTime;
            previousTotalBytes = totalBytes;
            return new SelfReplicateProgress(
                    operationId,
                    taskId,
                    sourceNodeId,
                    targetNodeId,
                    update.getPhase(),
                    update.getProgressPercent(),
                    currentBytes,
                    totalBytes,
                    rollingSpeed,
                    remainingMs,
                    startedTime,
                    updatedTime,
                    Math.max(0L, updatedTime - startedTime),
                    update.getMessage());
        }
    }

    private void reportSelfReplicateProgress(String taskId, String deployId, SelfReplicateProgress progress,
                                             AtomicLong lastEventTime, AtomicReference<String> lastPhase) {
        if (progress == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String phase = valueOrDefault(progress.getPhase(), "SELF_REPLICATING");
        AiTaskEntity latestTask = taskRepository.findByTaskId(taskId).orElse(null);
        if (latestTask == null || isTerminalTask(latestTask)) {
            return;
        }
        Map<String, Object> requestPayload = readJson(latestTask.getRequestPayloadJson());
        long eventIntervalMs = readLong(requestPayload, "progressEventIntervalMs", 10_000L, 1_000L);
        long stallThresholdMs = readLong(requestPayload, "progressStallThresholdMs", 30_000L, eventIntervalMs * 2L);
        boolean phaseChanged = !phase.equals(lastPhase.get());
        boolean stalled = progress.getUpdatedTime() != null && now - progress.getUpdatedTime() >= stallThresholdMs;
        if (!phaseChanged && !stalled && now - lastEventTime.get() < eventIntervalMs) {
            return;
        }
        AiDeployRecordEntity latestRecord = deployRecordRepository.findByDeployId(deployId).orElse(null);
        if (latestRecord == null) {
            return;
        }
        Map<String, Object> payload = eventPayload(
                latestTask,
                latestRecord,
                stalled ? "STALLED" : "RUNNING",
                phase,
                stalled ? "DEPLOY_PROGRESS_STALLED" : null);
        payload.put("operationId", progress.getOperationId());
        payload.put("sourceNodeId", progress.getSourceNodeId());
        payload.put("targetNodeId", progress.getTargetNodeId());
        payload.put("phase", phase);
        payload.put("progressPercent", progress.getProgressPercent());
        payload.put("bytesTransferred", progress.getBytesTransferred());
        payload.put("totalBytes", progress.getTotalBytes());
        payload.put("bytesPerSecond", progress.getBytesPerSecond());
        payload.put("estimatedRemainingMs", progress.getEstimatedRemainingMs());
        payload.put("elapsedMs", progress.getElapsedMs());
        payload.put("updatedTime", progress.getUpdatedTime());
        payload.put("message", progress.getMessage());
        payload.put("stalled", stalled);
        Map<String, Object> result = readJson(latestTask.getResultJson());
        result.put("deployProgress", payload);
        latestTask.setResultJson(writeJson(result));
        latestTask.setUpdateTime(String.valueOf(now));
        taskRepository.save(latestTask);
        latestRecord.setUpdateTime(String.valueOf(now));
        deployRecordRepository.save(latestRecord);
        taskEventService.appendEvent(taskId, latestTask.getSessionId(), "DEPLOY_NODE_PROGRESS",
                nextSequence(taskId), payload);
        syncParentTaskFromChild(latestTask, "DEPLOY_NODE_PROGRESS", latestTask.getStatus(), phase,
                payload, null, null, String.valueOf(now));
        lastEventTime.set(now);
        lastPhase.set(phase);
    }

    private long readLong(Map<String, Object> values, String key, long defaultValue, long minimumValue) {
        Object value = values == null ? null : values.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(minimumValue, Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private boolean sshCredentialPreflightCompleted(AiTaskEntity task) {
        if (task == null) {
            return false;
        }
        return readBoolean(readJson(task.getRequestPayloadJson()), "sshCredentialPreflightCompleted", false);
    }

    private boolean isSshAuthenticationFailure(SshDeployResult result) {
        if (result == null) {
            return false;
        }
        String message = (valueOrDefault(result.getStderrSummary(), "") + " "
                + valueOrDefault(result.getStdoutSummary(), "")).toLowerCase(java.util.Locale.ROOT);
        return message.contains("permission denied")
                || message.contains("authentication failed")
                || message.contains("authentication refused")
                || message.contains("publickey")
                || message.contains("no supported authentication methods")
                || message.contains("keyboard-interactive authentication")
                || (Integer.valueOf(255).equals(result.getExitCode()) && message.contains("auth"));
    }

    private boolean isCompletedDeployment(AiTaskEntity task) {
        return task != null
                && "SUCCESS".equalsIgnoreCase(task.getStatus())
                && "REGISTERED".equalsIgnoreCase(task.getCurrentStage());
    }

    private boolean isTerminalTask(AiTaskEntity task) {
        if (task == null || isCompletedDeployment(task)) {
            return true;
        }
        String status = task.getStatus();
        return "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "CANCELED".equalsIgnoreCase(status)
                || "TIMEOUT".equalsIgnoreCase(status);
    }

    private void convergeDeployRecordAfterTerminalTask(AiTaskEntity task, AiDeployRecordEntity deployRecord, String now) {
        String status = isCompletedDeployment(task) ? "SUCCESS"
                : ("CANCELLED".equalsIgnoreCase(task.getStatus()) || "CANCELED".equalsIgnoreCase(task.getStatus())
                ? "CANCELLED" : "FAILED");
        deployRecord.setStatus(status);
        deployRecord.setRetryable(Boolean.FALSE);
        deployRecord.setEndTime(now);
        deployRecord.setUpdateTime(now);
        deployRecordRepository.save(deployRecord);
        Map<String, Object> event = eventPayload(task, deployRecord, task.getStatus(), task.getCurrentStage(), task.getErrorCode());
        event.put("executorResponseIgnoredAfterTerminalState", true);
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_EXECUTOR_RESPONSE_IGNORED",
                nextSequence(task.getTaskId()), event);
    }

    private void waitForSshCredential(AiTaskEntity task, AiDeployRecordEntity deployRecord, String now, String errorMessage) {
        updateTask(task, "WAITING_USER_INPUT", "SSH_CREDENTIAL_REQUIRED", "SSH_CREDENTIAL_REQUIRED",
                errorMessage, now, null);
        Map<String, Object> result = eventPayload(task, deployRecord, "WAITING_USER_INPUT",
                "SSH_CREDENTIAL_REQUIRED", "SSH_CREDENTIAL_REQUIRED");
        result.put("taskCreated", true);
        result.put("passwordAcceptedByCenter", false);
        result.put("nextAction", "Configure SSH credentials with skill-local CLI, bootstrap the center public key, run center preflight, then resume this task");
        task.setResultJson(writeJson(result));
        taskRepository.save(task);
        taskEventService.appendEvent(task.getTaskId(), task.getSessionId(), "DEPLOY_USER_INPUT_REQUIRED",
                nextSequence(task.getTaskId()), result);
        syncParentTaskFromChild(task, "DEPLOY_USER_INPUT_REQUIRED", "WAITING_USER_INPUT",
                "SSH_CREDENTIAL_REQUIRED", result, "SSH_CREDENTIAL_REQUIRED", errorMessage, now);
    }

    private SshDeployRequest toSshDeployRequest(AiTaskEntity task, AiTaskCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AiTaskCreateRequest is required");
        }
        Map<String, Object> payload = request.getPayload() == null ? Map.of() : request.getPayload();
        SshDeployRequest sshRequest = new SshDeployRequest();
        String host = readString(payload, "host", readString(payload, "sshHost", readString(payload, "targetHost", null)));
        String username = readString(payload, "username", readString(payload, "sshUser", readString(payload, "targetUser", null)));
        String artifactPath = resolveArtifactPath(task, payload);
        sshRequest.setHost(requireValue(host, "host"));
        sshRequest.setUsername(requireValue(username, "username"));
        sshRequest.setPort(readInteger(payload, "port", readInteger(payload, "sshPort", readInteger(payload, "targetPort", 22))));
        sshRequest.setSourceHost(resolveSourceHost(task, payload));
        sshRequest.setSourcePort(resolveSourcePort(task, payload));
        sshRequest.setSourceUsername(resolveSourceUsername(payload, sshRequest.getUsername()));
        sshRequest.setScriptPath(resolveScriptPath(payload, artifactPath));
        sshRequest.setArtifactPath(artifactPath);
        Integer relayPort = readInteger(payload, "relayPort",
                readInteger(payload, "ccRelayPort", readInteger(payload, "targetRelayPort", null)));
        sshRequest.setRelayPort(relayPort == null ? nodePort(task.getTargetNodeId()) : relayPort);
        sshRequest.setReplaceExistingRelay(readBoolean(payload, "replaceExistingRelay", true));
        sshRequest.setRemoteDirectory(resolveRemoteDirectory(payload));
        sshRequest.setTimeoutMs(request.getTimeoutMs() == null || request.getTimeoutMs() <= 0L
                ? task.getTimeoutMs()
                : request.getTimeoutMs());
        sshRequest.setCommandArguments(readStringList(payload.get("commandArguments")));
        if (isBlank(task.getTargetNodeId())) {
            throw new IllegalArgumentException("targetNodeId is required for DEPLOY_RELAY task");
        }
        return sshRequest;
    }

    private Integer nodePort(String nodeId) {
        if (isBlank(nodeId)) {
            return null;
        }
        int separator = nodeId.lastIndexOf(':');
        if (separator < 0 || separator == nodeId.length() - 1) {
            return null;
        }
        try {
            int port = Integer.parseInt(nodeId.substring(separator + 1));
            return port > 0 ? port : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String resolveRemoteDirectory(Map<String, Object> payload) {
        String remoteDirectory = readString(payload, "remoteDirectory", readString(payload, "remoteWorkDir", null));
        if (!isBlank(remoteDirectory)) {
            return remoteDirectory.replace("\\", "/");
        }
        return null;
    }

    private String resolveScriptPath(Map<String, Object> payload, String artifactPath) {
        String scriptPath = readString(payload, "scriptPath", null);
        if (!isBlank(scriptPath)) {
            return scriptPath;
        }
        if (!isBlank(artifactPath)) {
            return artifactPath.replace("\\", "/") + "/install-relay.sh";
        }
        throw new IllegalArgumentException("scriptPath is required in task payload");
    }

    private String resolveArtifactPath(AiTaskEntity task, Map<String, Object> payload) {
        String artifactPath = readString(payload, "artifactPath", null);
        if (!isBlank(artifactPath)) {
            return artifactPath.replace("\\", "/");
        }
        if (task == null || isBlank(task.getSourceNodeId())) {
            return null;
        }
        return relayNodeRepository.findByNodeId(task.getSourceNodeId())
                .map(AiRelayNodeEntity::getWorkspaceRoot)
                .filter(workspaceRoot -> !isBlank(workspaceRoot))
                .map(workspaceRoot -> workspaceRoot.replace("\\", "/"))
                .orElse(null);
    }

    private String requireValue(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " is required in task payload");
        }
        return value;
    }

    private String resolveSourceHost(AiTaskEntity task, Map<String, Object> payload) {
        String sourceHost = readString(payload, "sourceHost", readString(payload, "sourceSshHost", null));
        if (!isBlank(sourceHost)) {
            return sourceHost;
        }
        if (task == null || isBlank(task.getSourceNodeId())) {
            return null;
        }
        return relayNodeRepository.findByNodeId(task.getSourceNodeId())
                .map(AiRelayNodeEntity::getHost)
                .orElse(null);
    }

    private Integer resolveSourcePort(AiTaskEntity task, Map<String, Object> payload) {
        Integer sourcePort = readInteger(payload, "sourcePort", readInteger(payload, "sourceSshPort", null));
        if (sourcePort != null) {
            return sourcePort;
        }
        if (task == null || isBlank(task.getSourceNodeId())) {
            return 22;
        }
        return relayNodeRepository.findByNodeId(task.getSourceNodeId())
                .map(AiRelayNodeEntity::getPort)
                .orElse(22);
    }

    private String resolveSourceUsername(Map<String, Object> payload, String defaultValue) {
        return readString(payload, "sourceUser",
                readString(payload, "sourceUsername",
                        readString(payload, "sourceSshUser", defaultValue)));
    }

    private void updateTask(AiTaskEntity task, String status, String currentStage, String errorCode,
                            String errorMessage, String updateTime, String endTime) {
        task.setStatus(status);
        task.setCurrentStage(currentStage);
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        if (task.getStartTime() == null) {
            task.setStartTime(updateTime);
        }
        if (endTime != null) {
            task.setEndTime(endTime);
        }
        task.setUpdateTime(updateTime);
        taskRepository.save(task);
    }

    private boolean isRegisteredInCenter(String targetNodeId, DeployReportRequest request) {
        if (isBlank(targetNodeId)) {
            return false;
        }
        Optional<AiRelayNodeEntity> nodeOptional = relayNodeRepository.findByNodeId(targetNodeId);
        if (nodeOptional.isEmpty()) {
            return false;
        }
        AiRelayNodeEntity node = nodeOptional.get();
        if (isBlank(node.getRegisterTime())) {
            return false;
        }
        if (!isBlank(request.getRelayEndpoint()) && !isBlank(node.getRelayEndpoint())
                && !request.getRelayEndpoint().trim().equals(node.getRelayEndpoint().trim())) {
            return false;
        }
        return true;
    }

    private void updateNodeStatus(String nodeId, String status) {
        if (isBlank(nodeId) || isBlank(status)) {
            return;
        }
        Optional<AiRelayNodeEntity> nodeOptional = relayNodeRepository.findByNodeId(nodeId);
        if (nodeOptional.isEmpty()) {
            return;
        }
        AiRelayNodeEntity node = nodeOptional.get();
        node.setStatus(status);
        node.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        relayNodeRepository.save(node);
    }

    private Optional<AiDeployRecordEntity> latestDeployRecord(String taskId) {
        List<AiDeployRecordEntity> records = deployRecordRepository.findByTaskIdOrderByCreateTimeAsc(taskId);
        if (records == null || records.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(records.get(records.size() - 1));
    }

    private AiTaskEntity findTask(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private AiTaskEntity findTaskWithRetry(String taskId) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return findTask(taskId);
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt >= 5) {
                    throw e;
                }
                try {
                    Thread.sleep(attempt * 100L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastFailure == null ? new IllegalArgumentException("Task not found: " + taskId) : lastFailure;
    }

    private Long nextSequence(String taskId) {
        return (long) (taskEventService.listEvents(taskId).size() + 1);
    }

    private Map<String, Object> eventPayload(AiTaskEntity task, AiDeployRecordEntity deployRecord,
                                             String status, String currentStage, String errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("sessionId", task.getSessionId());
        payload.put("deployId", deployRecord.getDeployId());
        payload.put("targetNodeId", task.getTargetNodeId());
        payload.put("deployMode", deployRecord.getDeployMode());
        payload.put("status", status);
        payload.put("currentStage", currentStage);
        payload.put("errorCode", errorCode);
        payload.put("scriptPath", deployRecord.getScriptPath());
        payload.put("artifactPath", deployRecord.getArtifactPath());
        payload.put("exitCode", deployRecord.getExitCode());
        payload.put("stdoutSummary", summarize(deployRecord.getStdoutSummary()));
        payload.put("stderrSummary", summarize(deployRecord.getStderrSummary()));
        Map<String, Object> requestPayload = readJson(task.getRequestPayloadJson());
        putIfNotBlank(payload, "grantId", linkedGrantId(requestPayload));
        putIfNotBlank(payload, "accessRequestId", readString(requestPayload, "accessRequestId", null));
        return payload;
    }

    private RelayAccessDecisionResponse activateLinkedGrantIfReady(AiTaskEntity task, Map<String, Object> result) {
        String grantId = linkedGrantId(readJson(task.getRequestPayloadJson()));
        if (isBlank(grantId)) {
            return null;
        }
        result.put("grantId", grantId);
        if (relayGrantService == null) {
            result.put("grantActivationStatus", "SKIPPED");
            result.put("grantActivationMessage", "relayGrantService is not configured");
            return null;
        }
        try {
            RelayAccessDecisionResponse decision = relayGrantService.activateGrantIfReady(grantId);
            result.put("grantActivationStatus", decision == null ? "UNKNOWN" : decision.getDecision());
            if (decision != null) {
                result.put("grantSignedTokenIssued", !isBlank(decision.getSignedToken()));
                putIfNotBlank(result, "grantTargetRelayEndpoint", decision.getTargetRelayEndpoint());
                putIfNotBlank(result, "grantAuditId", decision.getAuditId());
            }
            return decision;
        } catch (Exception e) {
            result.put("grantActivationStatus", "FAILED");
            result.put("grantActivationError", summarize(e.getMessage()));
            return null;
        }
    }

    private Map<String, Object> grantActivationEvent(RelayAccessDecisionResponse decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grantId", decision.getGrantId());
        payload.put("decision", decision.getDecision());
        payload.put("expiresAt", decision.getExpiresAt());
        payload.put("allowedCapabilities", decision.getAllowedCapabilities());
        payload.put("signedTokenIssued", !isBlank(decision.getSignedToken()));
        putIfNotBlank(payload, "targetRelayEndpoint", decision.getTargetRelayEndpoint());
        putIfNotBlank(payload, "auditId", decision.getAuditId());
        return payload;
    }

    private String linkedGrantId(Map<String, Object> payload) {
        String grantId = readString(payload, "grantId", null);
        if (!isBlank(grantId)) {
            return grantId;
        }
        grantId = readString(payload, "accessGrantId", null);
        if (!isBlank(grantId)) {
            return grantId;
        }
        return readString(payload, "relayGrantId", null);
    }

    private void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        if (!isBlank(value)) {
            payload.put(key, value);
        }
    }

    private Map<String, Object> deployDetail(AiTaskCreateRequest request, AiDeployRecordEntity deployRecord) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deployId", deployRecord.getDeployId());
        detail.put("taskId", deployRecord.getTaskId());
        detail.put("targetNodeId", deployRecord.getTargetNodeId());
        detail.put("deployMode", deployRecord.getDeployMode());
        detail.put("scriptPath", deployRecord.getScriptPath());
        detail.put("artifactPath", deployRecord.getArtifactPath());
        if (request != null && request.getPayload() != null) {
            detail.put("host", readString(request.getPayload(), "host", readString(request.getPayload(), "sshHost", readString(request.getPayload(), "targetHost", null))));
            detail.put("port", readInteger(request.getPayload(), "port", readInteger(request.getPayload(), "sshPort", readInteger(request.getPayload(), "targetPort", 22))));
            detail.put("remoteDirectory", readString(request.getPayload(), "remoteDirectory", readString(request.getPayload(), "remoteWorkDir", null)));
        }
        return detail;
    }

    private Map<String, Object> deployResultDetail(AiTaskCreateRequest request, AiDeployRecordEntity deployRecord, SshDeployResult result) {
        Map<String, Object> detail = deployDetail(request, deployRecord);
        detail.put("success", result.isSuccess());
        detail.put("exitCode", result.getExitCode());
        detail.put("stdoutSummary", summarize(result.getStdoutSummary()));
        detail.put("stderrSummary", summarize(result.getStderrSummary()));
        detail.put("resolvedRelayPort", result.getResolvedRelayPort());
        detail.put("resolvedRemoteDirectory", result.getResolvedRemoteDirectory());
        return detail;
    }

    private String readRequiredString(Map<String, Object> payload, String key) {
        String value = readString(payload, key, null);
        if (isBlank(value)) {
            throw new IllegalArgumentException(key + " is required in task payload");
        }
        return value;
    }

    private String readString(Map<String, Object> payload, String key, String defaultValue) {
        if (payload == null || !payload.containsKey(key) || payload.get(key) == null) {
            return defaultValue;
        }
        return String.valueOf(payload.get(key));
    }

    private boolean readBoolean(Map<String, Object> payload, String key, boolean defaultValue) {
        if (payload == null || !payload.containsKey(key) || payload.get(key) == null) {
            return defaultValue;
        }
        Object value = payload.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Integer readInteger(Map<String, Object> payload, String key, Integer defaultValue) {
        if (payload == null || !payload.containsKey(key) || payload.get(key) == null) {
            return defaultValue;
        }
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private List<String> readStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        String text = String.valueOf(value);
        if (text.trim().isEmpty()) {
            return result;
        }
        for (String item : text.split(",")) {
            String trimmed = item == null ? "" : item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private void syncParentTaskFromChild(AiTaskEntity childTask, String eventType, String status, String currentStage,
                                         Map<String, Object> childPayload, String errorCode, String errorMessage, String updateTime) {
        if (childTask == null || isBlank(childTask.getParentTaskId())) {
            return;
        }
        AiTaskEntity parentTask = taskRepository.findByTaskId(childTask.getParentTaskId()).orElse(null);
        if (parentTask == null) {
            return;
        }
        Map<String, Object> parentResult = readJson(parentTask.getResultJson());
        parentResult.put("latestChildTaskId", childTask.getTaskId());
        parentResult.put("latestChildTaskType", childTask.getTaskType());
        parentResult.put("latestChildStatus", status);
        parentResult.put("latestChildStage", currentStage);
        parentResult.put("latestChildEventType", eventType);
        if (childPayload != null && !childPayload.isEmpty()) {
            parentResult.put("latestChildPayload", new LinkedHashMap<>(childPayload));
            copyIfPresent(childPayload, parentResult, "grantId", "latestChildGrantId");
            copyIfPresent(childPayload, parentResult, "grantActivationStatus", "latestChildGrantActivationStatus");
            copyIfPresent(childPayload, parentResult, "grantTargetRelayEndpoint", "latestChildGrantTargetRelayEndpoint");
            copyIfPresent(childPayload, parentResult, "relayEndpoint", "latestChildRelayEndpoint");
        }
        if (!isTerminal(parentTask.getStatus())) {
            if ("FAILED".equals(status)) {
                parentTask.setStatus("FAILED");
                parentTask.setCurrentStage("CHILD_" + currentStage);
                parentTask.setErrorCode(errorCode);
                parentTask.setErrorMessage(errorMessage);
                parentTask.setEndTime(updateTime);
            } else if ("SUCCESS".equals(status)
                    || "GRANT_ACTIVATED".equals(currentStage)
                    || "ALLOW".equals(String.valueOf(parentResult.get("latestChildGrantActivationStatus")))) {
                parentTask.setStatus("PARTIAL_SUCCESS");
                parentTask.setCurrentStage("CHILD_" + currentStage);
                parentTask.setErrorCode(null);
                parentTask.setErrorMessage(null);
            } else {
                parentTask.setStatus("WAITING_DEPLOY");
                parentTask.setCurrentStage("CHILD_" + currentStage);
            }
        }
        parentTask.setUpdateTime(updateTime);
        parentTask.setResultJson(writeJson(parentResult));
        taskRepository.save(parentTask);

        Map<String, Object> parentEvent = new LinkedHashMap<>();
        parentEvent.put("parentTaskId", parentTask.getTaskId());
        parentEvent.put("childTaskId", childTask.getTaskId());
        parentEvent.put("childTaskType", childTask.getTaskType());
        parentEvent.put("childStatus", status);
        parentEvent.put("childStage", currentStage);
        parentEvent.put("childEventType", eventType);
        if (childPayload != null && !childPayload.isEmpty()) {
            parentEvent.putAll(childPayload);
        }
        if (!isBlank(errorCode)) {
            parentEvent.put("errorCode", errorCode);
        }
        if (!isBlank(errorMessage)) {
            parentEvent.put("errorMessage", errorMessage);
        }
        taskEventService.appendEvent(parentTask.getTaskId(), parentTask.getSessionId(), "CHILD_TASK_UPDATED",
                nextSequence(parentTask.getTaskId()), parentEvent);
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        if (source == null || target == null || !source.containsKey(sourceKey) || source.get(sourceKey) == null) {
            return;
        }
        target.put(targetKey, source.get(sourceKey));
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) || "TIMEOUT".equals(status);
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize deploy payload", e);
        }
    }

    private String summarize(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean booleanConfig(String propertyKey, String envKey, boolean defaultValue) {
        String property = System.getProperty(propertyKey);
        if (property != null && !property.trim().isEmpty()) {
            return Boolean.parseBoolean(property.trim());
        }
        String environment = System.getenv(envKey);
        return environment == null || environment.trim().isEmpty() ? defaultValue : Boolean.parseBoolean(environment.trim());
    }

    private static List<String> listConfig(String propertyKey, String envKey, List<String> defaultValue) {
        String property = System.getProperty(propertyKey);
        if (property != null && !property.trim().isEmpty()) {
            return parseList(property);
        }
        String environment = System.getenv(envKey);
        if (environment != null && !environment.trim().isEmpty()) {
            return parseList(environment);
        }
        return defaultValue;
    }

    private static List<String> parseList(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return values;
        }
        for (String item : raw.split(",")) {
            if (item != null && !item.trim().isEmpty()) {
                values.add(item.trim());
            }
        }
        return values;
    }

    private List<String> normalizeAllowedNodeIds(List<String> nodeIds) {
        List<String> values = new ArrayList<>();
        if (nodeIds == null || nodeIds.isEmpty()) {
            values.add("*");
            return values;
        }
        for (String nodeId : nodeIds) {
            if (nodeId != null && !nodeId.trim().isEmpty()) {
                values.add(nodeId.trim());
            }
        }
        if (values.isEmpty()) {
            values.add("*");
        }
        return values;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }
}








