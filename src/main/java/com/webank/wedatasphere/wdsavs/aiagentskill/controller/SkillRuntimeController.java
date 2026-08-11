package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCancelRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskStatusView;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskView;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployReportRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployReportResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.CenterSshPreflightRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.CenterSshPreflightResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.CenterSshPublicKeyView;
import com.webank.wedatasphere.wdsavs.aiagent.model.SshIdentityStateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantRenewRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantRevokeRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatScanResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskEventView;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayDeployService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayGrantService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayHeartbeatService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayRegistryService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiTaskEventService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiTaskLifecycleService;
import com.webank.wedatasphere.wdsavs.aiagent.service.CenterSshAccessService;
import com.webank.wedatasphere.wdsavs.aiagent.service.SshIdentityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skill")
public class SkillRuntimeController {

    private final AiRelayRegistryService relayRegistryService;
    private final AiRelayHeartbeatService heartbeatService;
    private final AiRelayGrantService relayGrantService;
    private final AiRelayDeployService relayDeployService;
    private final AiTaskLifecycleService taskLifecycleService;
    private final AiTaskEventService taskEventService;
    private final CenterSshAccessService centerSshAccessService;
    private final SshIdentityService sshIdentityService;

    public SkillRuntimeController(AiRelayRegistryService relayRegistryService,
                                  AiRelayHeartbeatService heartbeatService,
                                  AiRelayGrantService relayGrantService,
                                  AiRelayDeployService relayDeployService,
                                  AiTaskLifecycleService taskLifecycleService,
                                  AiTaskEventService taskEventService,
                                  CenterSshAccessService centerSshAccessService,
                                  SshIdentityService sshIdentityService) {
        this.relayRegistryService = relayRegistryService;
        this.heartbeatService = heartbeatService;
        this.relayGrantService = relayGrantService;
        this.relayDeployService = relayDeployService;
        this.taskLifecycleService = taskLifecycleService;
        this.taskEventService = taskEventService;
        this.centerSshAccessService = centerSshAccessService;
        this.sshIdentityService = sshIdentityService;
    }

    @PostMapping("/relay/register")
    public RelayRegisterResponse register(@RequestBody RelayRegisterRequest request) {
        return relayRegistryService.register(request);
    }

    @PostMapping("/relay/heartbeat")
    public RelayHeartbeatResponse heartbeat(@RequestBody RelayHeartbeatRequest request) {
        return heartbeatService.heartbeat(request);
    }

    @GetMapping("/relay/heartbeat/scan")
    public RelayHeartbeatScanResponse scanAvailability() {
        return heartbeatService.scanNodeAvailability();
    }

    @GetMapping("/relay/nodes/{nodeId}")
    public RelayNodeView getNode(@PathVariable String nodeId) {
        return relayRegistryService.getNode(nodeId);
    }

    @GetMapping("/relay/nodes")
    public List<RelayNodeView> listNodes() {
        return relayRegistryService.listNodes();
    }

    @PostMapping("/relay/access/request")
    public RelayAccessDecisionResponse requestAccess(@RequestBody RelayAccessRequest request) {
        return relayGrantService.requestAccess(request);
    }

    @PostMapping("/relay/access/renew")
    public RelayGrantView renew(@RequestBody RelayGrantRenewRequest request) {
        return relayGrantService.renewGrant(request);
    }

    @PostMapping("/relay/access/revoke")
    public Boolean revoke(@RequestBody RelayGrantRevokeRequest request) {
        return relayGrantService.revokeGrant(request);
    }

    @PostMapping("/relay/access/validate")
    public RelayGrantValidateResponse validate(@RequestBody RelayGrantValidateRequest request) {
        return relayGrantService.validateGrant(request);
    }

    @GetMapping("/relay/access/{grantId}")
    public RelayGrantView getGrant(@PathVariable String grantId) {
        return relayGrantService.getGrant(grantId);
    }

    @PostMapping("/relay/deploy/report")
    public DeployReportResponse deployReport(@RequestBody DeployReportRequest request) {
        return relayDeployService.report(request);
    }

    @GetMapping("/ssh/center-key")
    public CenterSshPublicKeyView centerSshPublicKey() {
        return centerSshAccessService.getPublicKey();
    }

    @PostMapping("/ssh/preflight")
    public CenterSshPreflightResponse centerSshPreflight(@RequestBody CenterSshPreflightRequest request) {
        return centerSshAccessService.preflight(request);
    }

    @GetMapping("/ssh/identity")
    public Map<String, Object> sshIdentityState(@RequestParam(required = false, defaultValue = "default") String clusterId) {
        return sshIdentityService.getState(clusterId);
    }

    @PostMapping("/ssh/identity/state")
    public Map<String, Object> saveSshIdentityState(@RequestBody SshIdentityStateRequest request) {
        return sshIdentityService.saveState(request);
    }

    @PostMapping("/relay/deploy/{taskId}/resume")
    public Boolean resumeDeploy(@PathVariable String taskId) {
        return relayDeployService.resumeCenterDeploy(taskId);
    }

    @PostMapping("/tasks/create")
    public AiTaskCreateResponse createTask(@RequestBody AiTaskCreateRequest request) {
        return taskLifecycleService.createTask(request);
    }

    @GetMapping("/tasks/{taskId}")
    public AiTaskView getTask(@PathVariable String taskId) {
        return taskLifecycleService.getTask(taskId);
    }

    @GetMapping("/tasks/{taskId}/status")
    public AiTaskStatusView getTaskStatus(@PathVariable String taskId) {
        return taskLifecycleService.getTaskStatus(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Boolean cancelTask(@PathVariable String taskId, @RequestBody(required = false) AiTaskCancelRequest request) {
        return taskLifecycleService.cancelTask(taskId, request == null ? new AiTaskCancelRequest() : request);
    }

    @GetMapping("/tasks/{taskId}/events")
    public List<AiTaskEventView> listTaskEvents(@PathVariable String taskId,
                                                @RequestParam(required = false) Long sinceSequenceNo,
                                                @RequestParam(required = false) Long sinceCreatedTimeMs,
                                                @RequestParam(required = false) Long lastMs,
                                                @RequestParam(required = false) Integer limit,
                                                @RequestParam(required = false) List<String> eventTypes) {
        Long effectiveSinceCreatedTimeMs = sinceCreatedTimeMs;
        if (effectiveSinceCreatedTimeMs == null && lastMs != null && lastMs > 0L) {
            effectiveSinceCreatedTimeMs = Math.max(0L, System.currentTimeMillis() - lastMs);
        }
        List<AiTaskEventView> events = (sinceSequenceNo != null || effectiveSinceCreatedTimeMs != null || limit != null)
                ? taskEventService.listEvents(taskId, sinceSequenceNo, effectiveSinceCreatedTimeMs, limit)
                : taskEventService.listEvents(taskId);
        if (eventTypes == null || eventTypes.isEmpty()) {
            return events;
        }
        List<String> normalizedEventTypes = new ArrayList<>();
        for (String eventType : eventTypes) {
            if (eventType != null && !eventType.trim().isEmpty()) {
                normalizedEventTypes.add(eventType.trim().toUpperCase());
            }
        }
        return events.stream()
                .filter(event -> event != null && event.getEventType() != null && normalizedEventTypes.contains(event.getEventType().trim().toUpperCase()))
                .toList();
    }

    @GetMapping("/tasks/{taskId}/events/stream")
    public ResponseEntity<StreamingResponseBody> streamTaskEvents(@PathVariable String taskId) {
        return taskEventService.streamEvents(taskId);
    }
}
