package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ReactExecutionPolicy {

    public static final String DEFAULT_MODE = "ReAct";
    public static final int DEFAULT_MAX_STEPS = 12;
    public static final long DEFAULT_STEP_TIMEOUT_MS = 60_000L;
    public static final long DEFAULT_TASK_TIMEOUT_MS = 600_000L;
    public static final String DEFAULT_AUDIT_LEVEL = "FULL";
    public static final List<String> DEFAULT_COMMAND_WHITELIST = List.of(
            "ccrelay-cli",
            "ccrelay-cli.cmd"
    );
    public static final String ENFORCEMENT_WEAK_PROMPT = "WEAK_PROMPT";
    public static final String ENFORCEMENT_STATUS = "JAVA_REACT_RUNNER_NOT_ENABLED";
    public static final String ENFORCEMENT_JAVA = "JAVA_ENFORCED_REACT";
    public static final String ENFORCEMENT_JAVA_STATUS = "JAVA_REACT_RUNNER_ENABLED";

    private String executionMode;
    private Boolean enabled;
    private String mode;
    private Integer maxSteps;
    private List<String> commandWhitelist = new ArrayList<>();
    private Long stepTimeoutMs;
    private Long taskTimeoutMs;
    private String auditLevel;
    private Boolean allowAi;
    private String enforcementMode;
    private String enforcementStatus;

    public static ReactExecutionPolicy fromParams(Map<String, Object> params) {
        Map<String, Object> source = params == null ? Map.of() : params;
        Map<String, Object> react = mapValue(source.get("react"));
        String executionMode = firstNonBlank(stringValue(source.get("executionMode")), stringValue(react.get("mode")), DEFAULT_MODE);
        String mode = firstNonBlank(stringValue(react.get("mode")), executionMode, DEFAULT_MODE);

        ReactExecutionPolicy policy = new ReactExecutionPolicy();
        policy.setExecutionMode(executionMode);
        policy.setMode(mode);
        policy.setEnabled(booleanValue(react.get("enabled"), DEFAULT_MODE.equalsIgnoreCase(mode)));
        policy.setMaxSteps(integerValue(firstPresent(react.get("maxSteps"), source.get("maxSteps")), DEFAULT_MAX_STEPS));
        List<String> commandWhitelist = listValue(firstPresent(react.get("commandWhitelist"), source.get("commandWhitelist")));
        if (commandWhitelist.isEmpty()) {
            commandWhitelist = new ArrayList<>(DEFAULT_COMMAND_WHITELIST);
        }
        policy.setCommandWhitelist(commandWhitelist);
        policy.setStepTimeoutMs(longValue(firstPresent(react.get("stepTimeoutMs"), source.get("stepTimeoutMs")), DEFAULT_STEP_TIMEOUT_MS));
        policy.setTaskTimeoutMs(longValue(firstPresent(react.get("taskTimeoutMs"), source.get("taskTimeoutMs")), DEFAULT_TASK_TIMEOUT_MS));
        policy.setAuditLevel(firstNonBlank(stringValue(firstPresent(react.get("auditLevel"), source.get("auditLevel"))), DEFAULT_AUDIT_LEVEL));
        policy.setAllowAi(booleanValue(firstPresent(react.get("allowAi"), source.get("allowAi")), Boolean.TRUE));
        policy.setEnforcementMode(firstNonBlank(stringValue(react.get("enforcementMode")), ENFORCEMENT_WEAK_PROMPT));
        policy.setEnforcementStatus(firstNonBlank(stringValue(react.get("enforcementStatus")), ENFORCEMENT_STATUS));
        return policy;
    }

    public Map<String, Object> toSummaryMap() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enabled", enabled);
        summary.put("mode", mode);
        summary.put("maxSteps", maxSteps);
        summary.put("commandWhitelist", commandWhitelist == null ? List.of() : new ArrayList<>(commandWhitelist));
        summary.put("stepTimeoutMs", stepTimeoutMs);
        summary.put("taskTimeoutMs", taskTimeoutMs);
        summary.put("auditLevel", auditLevel);
        summary.put("allowAi", allowAi);
        summary.put("enforcementMode", enforcementMode);
        summary.put("enforcementStatus", enforcementStatus);
        return summary;
    }

    public void applyToParams(Map<String, Object> params) {
        if (params == null) {
            return;
        }
        params.put("executionMode", executionMode);
        params.put("react", toSummaryMap());
    }

    private static Object firstPresent(Object first, Object second) {
        return first == null ? second : first;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static List<String> listValue(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                addCsv(result, stringValue(item));
            }
            return result;
        }
        addCsv(result, stringValue(value));
        return result;
    }

    private static void addCsv(List<String> result, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static Boolean booleanValue(Object value, Boolean defaultValue) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer integerValue(Object value, Integer defaultValue) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static Long longValue(Object value, Long defaultValue) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
