package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.service.A2aAgentService;
import com.webank.wedatasphere.wdsavs.aiagent.service.A2aTaskService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiTaskLifecycleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/skill/a2a")
public class SkillA2aController {

    private final A2aAgentService a2aAgentService;
    private final A2aTaskService a2aTaskService;
    private final AiTaskLifecycleService taskLifecycleService;

    public SkillA2aController(A2aAgentService a2aAgentService,
                              A2aTaskService a2aTaskService,
                              AiTaskLifecycleService taskLifecycleService) {
        this.a2aAgentService = a2aAgentService;
        this.a2aTaskService = a2aTaskService;
        this.taskLifecycleService = taskLifecycleService;
    }

    @GetMapping("/agent-card")
    public Object agentCard() {
        return a2aAgentService.agentCard();
    }

    @PostMapping("/message/send")
    public A2aJsonRpcResponse messageSend(@RequestBody A2aJsonRpcRequest request) {
        return a2aAgentService.handle(request);
    }

    @PostMapping("/tasks/create")
    public A2aTaskCreateResponse createTask(@RequestBody A2aTaskCreateRequest request) {
        return a2aTaskService.createTask(ensureShadowTask(request));
    }

    @GetMapping("/tasks/{taskId}")
    public Object getTask(@PathVariable String taskId,
                          @RequestParam(required = false) String sessionId,
                          @RequestParam(required = false) String grantId,
                          @RequestParam(required = false) String signedToken,
                          @RequestParam(required = false) String sourceNodeId,
                          @RequestParam(required = false) String targetNodeId,
                          @RequestParam(required = false) String targetRelayEndpoint,
                          @RequestParam(required = false) String centerGrantValidateEndpoint) {
        return a2aTaskService.getTask(taskId,
                context(sessionId, grantId, signedToken, sourceNodeId, targetNodeId, targetRelayEndpoint, centerGrantValidateEndpoint, taskId));
    }

    @GetMapping("/tasks/{taskId}/events")
    public ResponseEntity<StreamingResponseBody> streamTaskEvents(@PathVariable String taskId,
                                                                  @RequestParam(required = false) String sessionId,
                                                                  @RequestParam(required = false) String grantId,
                                                                  @RequestParam(required = false) String signedToken,
                                                                  @RequestParam(required = false) String sourceNodeId,
                                                                  @RequestParam(required = false) String targetNodeId,
                                                                  @RequestParam(required = false) String targetRelayEndpoint,
                                                                  @RequestParam(required = false) String centerGrantValidateEndpoint) {
        return a2aTaskService.streamTaskEvents(taskId,
                context(sessionId, grantId, signedToken, sourceNodeId, targetNodeId, targetRelayEndpoint, centerGrantValidateEndpoint, taskId));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Object cancelTask(@PathVariable String taskId,
                             @RequestParam(required = false) String sessionId,
                             @RequestParam(required = false) String grantId,
                             @RequestParam(required = false) String signedToken,
                             @RequestParam(required = false) String sourceNodeId,
                             @RequestParam(required = false) String targetNodeId,
                             @RequestParam(required = false) String targetRelayEndpoint,
                             @RequestParam(required = false) String centerGrantValidateEndpoint) {
        return a2aTaskService.cancelTask(taskId,
                context(sessionId, grantId, signedToken, sourceNodeId, targetNodeId, targetRelayEndpoint, centerGrantValidateEndpoint, taskId));
    }

    private A2aTaskCreateRequest ensureShadowTask(A2aTaskCreateRequest request) {
        if (request == null) {
            return null;
        }
        Map<String, Object> params = request.getParams() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getParams());
        String sessionId = stringValue(params.get("sessionId"));
        if (isBlank(sessionId)) {
            request.setParams(params);
            return request;
        }
        String taskId = firstNonBlank(
                stringValue(params.get("taskId")),
                stringValue(params.get("requestId")),
                stringValue(params.get("idempotencyKey")),
                request.getId(),
                UUID.randomUUID().toString());
        params.put("taskId", taskId);
        params.put("idempotencyKey", firstNonBlank(
                stringValue(params.get("idempotencyKey")),
                stringValue(params.get("requestId")),
                taskId,
                request.getId()));
        if (isBlank(stringValue(params.get("parentTaskId")))) {
            createShadowTask(taskId, sessionId, params);
            params.put("parentTaskId", taskId);
        }
        request.setParams(params);
        return request;
    }

    private void createShadowTask(String taskId, String sessionId, Map<String, Object> params) {
        AiTaskCreateRequest shadowRequest = new AiTaskCreateRequest();
        shadowRequest.setTaskId(taskId);
        shadowRequest.setSessionId(sessionId);
        shadowRequest.setRequestId("skill-shadow:" + taskId);
        shadowRequest.setTaskType("A2A_TASK");
        shadowRequest.setSourceNodeId(stringValue(params.get("sourceNodeId")));
        shadowRequest.setTargetNodeId(stringValue(params.get("targetNodeId")));
        shadowRequest.setPayload(new LinkedHashMap<>(params));
        taskLifecycleService.createTask(shadowRequest);
    }

    private Map<String, Object> context(String sessionId,
                                        String grantId,
                                        String signedToken,
                                        String sourceNodeId,
                                        String targetNodeId,
                                        String targetRelayEndpoint,
                                        String centerGrantValidateEndpoint,
                                        String parentTaskId) {
        Map<String, Object> context = new LinkedHashMap<>();
        putIfNotBlank(context, "sessionId", sessionId);
        putIfNotBlank(context, "grantId", grantId);
        putIfNotBlank(context, "signedToken", signedToken);
        putIfNotBlank(context, "sourceNodeId", sourceNodeId);
        putIfNotBlank(context, "targetNodeId", targetNodeId);
        putIfNotBlank(context, "targetRelayEndpoint", targetRelayEndpoint);
        putIfNotBlank(context, "centerGrantValidateEndpoint", centerGrantValidateEndpoint);
        putIfNotBlank(context, "parentTaskId", parentTaskId);
        return context;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
