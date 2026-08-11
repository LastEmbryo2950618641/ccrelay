package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AgentControlState;
import com.webank.wedatasphere.wdsavs.aiagent.model.ReactExecutionPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReactAgentRunner {

    private final RemoteCcRelayService relayService;
    private final ReactCommandExecutor commandExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReactAgentRunner(RemoteCcRelayService relayService, ReactCommandExecutor commandExecutor) {
        this.relayService = relayService;
        this.commandExecutor = commandExecutor;
    }

    public AiChatResponse run(AiChatRequest request,
                              Map<String, Object> params,
                              ReactExecutionPolicy policy,
                              ReactEventWriter eventWriter) {
        return run(request, params, policy, eventWriter, null);
    }

    public AiChatResponse run(AiChatRequest request,
                              Map<String, Object> params,
                              ReactExecutionPolicy policy,
                              ReactEventWriter eventWriter,
                              ReactControlStateReader controlStateReader) {
        ReactExecutionPolicy effectivePolicy = policy == null ? ReactExecutionPolicy.fromParams(params) : policy;
        List<Map<String, Object>> actions = plannedActions(params, request);
        if (actions.isEmpty()) {
            return runModelFallback(request, effectivePolicy, eventWriter, controlStateReader);
        }
        int consumedInjectCount = 0;
        int maxSteps = effectivePolicy.getMaxSteps() == null || effectivePolicy.getMaxSteps() <= 0
                ? ReactExecutionPolicy.DEFAULT_MAX_STEPS
                : effectivePolicy.getMaxSteps();
        append(eventWriter, "POLICY_RESOLVED", policyPayload(effectivePolicy));
        Map<String, Object> lastObservation = new LinkedHashMap<>();
        for (int index = 0; index < actions.size(); index++) {
            int stepNo = index + 1;
            AgentControlState controlState = controlState(controlStateReader);
            AiChatResponse stopped = stopIfRequested(controlState, effectivePolicy, eventWriter, stepNo);
            if (stopped != null) {
                return stopped;
            }
            if (controlState != null) {
                effectivePolicy = applyControlPatch(effectivePolicy, controlState, eventWriter, stepNo);
                int injectedCount = controlState.getInjectedPrompts() == null ? 0 : controlState.getInjectedPrompts().size();
                if (injectedCount > consumedInjectCount) {
                    List<Map<String, Object>> injected = new ArrayList<>(controlState.getInjectedPrompts().subList(consumedInjectCount, injectedCount));
                    append(eventWriter, "CONTROL_INJECTION_CONSUMED", Map.of("stepNo", stepNo, "injectedPrompts", injected));
                    consumedInjectCount = injectedCount;
                }
            }
            maxSteps = effectivePolicy.getMaxSteps() == null || effectivePolicy.getMaxSteps() <= 0
                    ? ReactExecutionPolicy.DEFAULT_MAX_STEPS
                    : effectivePolicy.getMaxSteps();
            if (stepNo > maxSteps) {
                append(eventWriter, "TASK_FAILED", Map.of(
                        "status", "FAILED",
                        "errorCode", "REACT_MAX_STEPS_EXCEEDED",
                        "maxSteps", maxSteps
                ));
                AiChatResponse response = new AiChatResponse("ReAct maxSteps exceeded", "FAILED", UUID.randomUUID().toString());
                response.setMetadata(policyMetadata(effectivePolicy));
                response.setDiagnostics(Map.of("lastObservation", lastObservation));
                return response;
            }
            append(eventWriter, "STEP_STARTED", Map.of("stepNo", stepNo, "maxSteps", maxSteps));
            Map<String, Object> action = normalizeAction(actions.get(index));
            append(eventWriter, "ACTION_PROPOSED", Map.of("stepNo", stepNo, "action", sanitizedAction(action)));
            String type = actionType(action);
            try {
                switch (type) {
                    case "RUN_COMMAND" -> {
                        commandExecutor.validate(action, effectivePolicy);
                        append(eventWriter, "ACTION_APPROVED", Map.of("stepNo", stepNo, "type", type));
                        append(eventWriter, "COMMAND_STARTED", Map.of(
                                "stepNo", stepNo,
                                "command", String.valueOf(action.get("command")),
                                "args", action.getOrDefault("args", List.of())
                        ));
                        ReactCommandResult commandResult = commandExecutor.execute(action, effectivePolicy);
                        Map<String, Object> commandPayload = commandResultPayload(stepNo, commandResult);
                        append(eventWriter, "COMMAND_FINISHED", commandPayload);
                        lastObservation = new LinkedHashMap<>(commandPayload);
                        append(eventWriter, "OBSERVATION_RECORDED", Map.of("stepNo", stepNo, "observation", lastObservation));
                        if (!"SUCCESS".equals(commandResult.getStatus())) {
                            AiChatResponse response = new AiChatResponse(firstNonBlank(commandResult.getErrorMessage(), commandResult.getStderr(), "Command failed"),
                                    commandResult.getStatus(), UUID.randomUUID().toString());
                            response.setDiagnostics(Map.of("lastObservation", lastObservation));
                            response.setMetadata(policyMetadata(effectivePolicy));
                            return response;
                        }
                    }
                    case "REPORT" -> {
                        lastObservation = new LinkedHashMap<>(action);
                        append(eventWriter, "OBSERVATION_RECORDED", Map.of("stepNo", stepNo, "observation", lastObservation));
                    }
                    case "FINISH" -> {
                        String answer = firstNonBlank(stringValue(action.get("answer")), stringValue(action.get("summary")), "ReAct task finished");
                        AiChatResponse response = new AiChatResponse(answer, "SUCCESS", UUID.randomUUID().toString());
                        response.setSummary(stringValue(action.get("summary")));
                        response.setDiagnostics(Map.of("lastObservation", lastObservation));
                        response.setMetadata(policyMetadata(effectivePolicy));
                        append(eventWriter, "TASK_FINISHED", Map.of("status", "SUCCESS", "stepNo", stepNo, "answer", answer));
                        return response;
                    }
                    case "ASK_AI" -> {
                        if (!Boolean.TRUE.equals(effectivePolicy.getAllowAi())) {
                            throw new SecurityException("ASK_AI is rejected because allowAi=false");
                        }
                        append(eventWriter, "MODEL_REQUESTED", Map.of("stepNo", stepNo));
                        AiChatResponse response = relayService.relay(request, eventWriter, policy.getTaskTimeoutMs());
                        append(eventWriter, "MODEL_RESPONDED", Map.of("stepNo", stepNo, "status", response.getStatus(), "traceId", response.getTraceId()));
                        attachPolicyMetadata(response, effectivePolicy);
                        return response;
                    }
                    case "NOOP" -> {
                        waitIfRequested(action);
                        append(eventWriter, "OBSERVATION_RECORDED", Map.of("stepNo", stepNo, "observation", "NOOP"));
                    }
                    default -> throw new IllegalArgumentException("Unsupported ReAct action type: " + type);
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    AiChatResponse response = new AiChatResponse("Task cancelled by interruption", "CANCELLED", UUID.randomUUID().toString());
                    response.setMetadata(policyMetadata(effectivePolicy));
                    append(eventWriter, "TASK_CANCELLED", Map.of("status", "CANCELLED", "stepNo", stepNo));
                    return response;
                }
                append(eventWriter, "ACTION_REJECTED", Map.of(
                        "stepNo", stepNo,
                        "type", type,
                        "errorMessage", e.getMessage()
                ));
                AiChatResponse response = new AiChatResponse(e.getMessage(), "FAILED", UUID.randomUUID().toString());
                response.setMetadata(policyMetadata(effectivePolicy));
                response.setDiagnostics(Map.of("rejectedAction", sanitizedAction(action)));
                return response;
            }
        }
        AiChatResponse response = new AiChatResponse("ReAct actions exhausted without FINISH", "FAILED", UUID.randomUUID().toString());
        response.setMetadata(policyMetadata(effectivePolicy));
        response.setDiagnostics(Map.of("lastObservation", lastObservation));
        append(eventWriter, "TASK_FAILED", Map.of("status", "FAILED", "errorCode", "REACT_ACTIONS_EXHAUSTED"));
        return response;
    }

    private AiChatResponse runModelFallback(AiChatRequest request,
                                            ReactExecutionPolicy policy,
                                            ReactEventWriter eventWriter,
                                            ReactControlStateReader controlStateReader) {
        append(eventWriter, "POLICY_RESOLVED", policyPayload(policy));
        AiChatResponse stopped = stopIfRequested(controlState(controlStateReader), policy, eventWriter, 1);
        if (stopped != null) {
            return stopped;
        }
        append(eventWriter, "STEP_STARTED", Map.of("stepNo", 1, "maxSteps", policy.getMaxSteps()));
        if (!Boolean.TRUE.equals(policy.getAllowAi())) {
            append(eventWriter, "ACTION_REJECTED", Map.of("stepNo", 1, "type", "ASK_AI", "errorMessage", "allowAi=false"));
            AiChatResponse response = new AiChatResponse("No structured ReAct action was provided and allowAi=false", "FAILED", UUID.randomUUID().toString());
            response.setMetadata(policyMetadata(policy));
            return response;
        }
        append(eventWriter, "MODEL_REQUESTED", Map.of("stepNo", 1, "fallback", true));
        AiChatResponse response = relayService.relay(request, eventWriter, policy.getTaskTimeoutMs());
        append(eventWriter, "MODEL_RESPONDED", Map.of("stepNo", 1, "status", response.getStatus(), "traceId", response.getTraceId()));
        attachPolicyMetadata(response, policy);
        return response;
    }

    private AgentControlState controlState(ReactControlStateReader controlStateReader) {
        return controlStateReader == null ? null : controlStateReader.readControlState();
    }

    private AiChatResponse stopIfRequested(AgentControlState controlState,
                                           ReactExecutionPolicy policy,
                                           ReactEventWriter eventWriter,
                                           int stepNo) {
        if (controlState == null || !Boolean.TRUE.equals(controlState.getStopRequested())) {
            return null;
        }
        append(eventWriter, "TASK_STOP_REQUESTED", Map.of("stepNo", stepNo, "status", "CANCELLED"));
        AiChatResponse response = new AiChatResponse("Task cancelled by control state", "CANCELLED", UUID.randomUUID().toString());
        response.setMetadata(policyMetadata(policy));
        return response;
    }

    private ReactExecutionPolicy applyControlPatch(ReactExecutionPolicy policy,
                                                  AgentControlState controlState,
                                                  ReactEventWriter eventWriter,
                                                  int stepNo) {
        if (controlState.getPolicyPatch() == null || controlState.getPolicyPatch().isEmpty()) {
            return policy;
        }
        Map<String, Object> patch = controlState.getPolicyPatch();
        Map<String, Object> mergedReact = new LinkedHashMap<>(policy.toSummaryMap());
        Object nestedReact = patch.get("react");
        if (nestedReact instanceof Map<?, ?> nested) {
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                if (entry.getKey() != null) {
                    mergedReact.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } else {
            for (Map.Entry<String, Object> entry : patch.entrySet()) {
                mergedReact.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("executionMode", policy.getExecutionMode());
        source.put("react", mergedReact);
        ReactExecutionPolicy adjusted = ReactExecutionPolicy.fromParams(source);
        adjusted.setEnforcementMode(policy.getEnforcementMode());
        adjusted.setEnforcementStatus(policy.getEnforcementStatus());
        append(eventWriter, "CONTROL_ADJUSTMENT_CONSUMED", Map.of("stepNo", stepNo, "react", adjusted.toSummaryMap()));
        return adjusted;
    }

    private List<Map<String, Object>> plannedActions(Map<String, Object> params, AiChatRequest request) {
        Object direct = params == null ? null : params.get("actions");
        List<Map<String, Object>> actions = listOfMaps(direct);
        if (!actions.isEmpty()) {
            return actions;
        }
        Map<String, Object> singleAction = parseActionFromMessages(request);
        return singleAction.isEmpty() ? List.of() : List.of(singleAction);
    }

    private Map<String, Object> parseActionFromMessages(AiChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return Map.of();
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            AiChatMessage message = request.getMessages().get(i);
            String content = message == null ? null : message.getContent();
            if (content == null || content.trim().isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {
                });
                if (parsed.containsKey("action") || parsed.containsKey("type")) {
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    private Map<String, Object> normalizeAction(Map<String, Object> source) {
        Map<String, Object> action = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        Object nestedAction = action.get("action");
        if (nestedAction instanceof Map<?, ?> nested) {
            action = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                if (entry.getKey() != null) {
                    action.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return action;
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                result.add(normalized);
            }
        }
        return result;
    }

    private Map<String, Object> commandResultPayload(int stepNo, ReactCommandResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepNo", stepNo);
        payload.put("status", result.getStatus());
        payload.put("exitCode", result.getExitCode());
        payload.put("timedOut", result.getTimedOut());
        putIfNotBlank(payload, "stdout", result.getStdout());
        putIfNotBlank(payload, "stderr", result.getStderr());
        putIfNotBlank(payload, "errorMessage", result.getErrorMessage());
        return payload;
    }

    private void waitIfRequested(Map<String, Object> action) throws InterruptedException {
        Object waitMsValue = action.get("waitMs");
        if (waitMsValue == null || String.valueOf(waitMsValue).trim().isEmpty()) {
            return;
        }
        long waitMs = waitMsValue instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(waitMsValue));
        if (waitMs > 0L) {
            Thread.sleep(waitMs);
        }
    }

    private Map<String, Object> policyPayload(ReactExecutionPolicy policy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionMode", policy.getExecutionMode());
        payload.put("react", policy.toSummaryMap());
        return payload;
    }

    private Map<String, Object> policyMetadata(ReactExecutionPolicy policy) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("executionMode", policy.getExecutionMode());
        metadata.put("react", policy.toSummaryMap());
        return metadata;
    }

    private void attachPolicyMetadata(AiChatResponse response, ReactExecutionPolicy policy) {
        if (response == null) {
            return;
        }
        Map<String, Object> metadata = response.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.getMetadata());
        metadata.put("executionMode", policy.getExecutionMode());
        metadata.put("react", policy.toSummaryMap());
        response.setMetadata(metadata);
    }

    private Map<String, Object> sanitizedAction(Map<String, Object> action) {
        Map<String, Object> sanitized = new LinkedHashMap<>(action == null ? Map.of() : action);
        sanitized.remove("apiKey");
        sanitized.remove("signedToken");
        sanitized.remove("authorization");
        return sanitized;
    }

    private String actionType(Map<String, Object> action) {
        String type = stringValue(action.get("type"));
        return type == null || type.trim().isEmpty() ? "" : type.trim().toUpperCase();
    }

    private void append(ReactEventWriter eventWriter, String eventType, Map<String, Object> payload) {
        if (eventWriter != null) {
            eventWriter.appendEvent(eventType, payload == null ? Map.of() : payload);
        }
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.put(key, value);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
