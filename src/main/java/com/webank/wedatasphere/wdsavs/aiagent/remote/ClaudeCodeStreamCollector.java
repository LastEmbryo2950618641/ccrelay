package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClaudeCodeStreamCollector {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReactEventWriter eventWriter;
    private final boolean privateDraft;
    private final StringBuilder rawOutput = new StringBuilder();
    private final List<String> assistantMessages = new ArrayList<>();
    private final Map<String, Long> toolStartTimes = new LinkedHashMap<>();
    private final Set<String> emittedBlocks = new LinkedHashSet<>();
    private String result;
    private boolean structuredOutput;

    public ClaudeCodeStreamCollector(ReactEventWriter eventWriter) {
        this(eventWriter, false);
    }

    public ClaudeCodeStreamCollector(ReactEventWriter eventWriter, boolean privateDraft) {
        this.eventWriter = eventWriter;
        this.privateDraft = privateDraft;
    }

    public void acceptLine(String line) {
        if (line == null) {
            return;
        }
        if (!rawOutput.isEmpty()) {
            rawOutput.append('\n');
        }
        rawOutput.append(line);
        Map<String, Object> event = parse(line);
        String type = stringValue(event.get("type"));
        if (type == null) {
            return;
        }
        switch (type.toLowerCase()) {
            case "system" -> structuredOutput = true;
            case "assistant" -> acceptAssistant(event);
            case "user" -> acceptUser(event);
            case "result" -> acceptResult(event);
            default -> {
            }
        }
    }

    public String answer() {
        if (notBlank(result)) {
            return result;
        }
        if (!assistantMessages.isEmpty()) {
            return assistantMessages.get(assistantMessages.size() - 1);
        }
        return rawOutput.toString();
    }

    public boolean isStructuredOutput() {
        return structuredOutput;
    }

    private void acceptAssistant(Map<String, Object> event) {
        structuredOutput = true;
        Map<String, Object> message = mapValue(event.get("message"));
        String messageId = firstNonBlank(stringValue(message.get("id")), stringValue(event.get("uuid")), "assistant");
        List<Object> content = listValue(message.get("content"));
        for (int index = 0; index < content.size(); index++) {
            Map<String, Object> block = mapValue(content.get(index));
            String blockType = stringValue(block.get("type"));
            String blockKey = messageId + ":" + index + ":" + blockType;
            if (!emittedBlocks.add(blockKey)) {
                continue;
            }
            if ("text".equalsIgnoreCase(blockType)) {
                String text = stringValue(block.get("text"));
                if (notBlank(text)) {
                    assistantMessages.add(text);
                    if (!privateDraft) {
                        emit("AGENT_MESSAGE", Map.of("text", text, "messageId", messageId));
                    }
                }
            } else if ("tool_use".equalsIgnoreCase(blockType)) {
                String toolUseId = firstNonBlank(stringValue(block.get("id")), messageId + ":tool:" + index);
                String toolName = firstNonBlank(stringValue(block.get("name")), "tool");
                toolStartTimes.put(toolUseId, System.currentTimeMillis());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolUseId", toolUseId);
                payload.put("toolName", toolName);
                payload.put("input", structuredValue(block.get("input")));
                emit("AGENT_TOOL_STARTED", payload);
            }
        }
    }

    private void acceptUser(Map<String, Object> event) {
        structuredOutput = true;
        Map<String, Object> message = mapValue(event.get("message"));
        for (Object item : listValue(message.get("content"))) {
            Map<String, Object> block = mapValue(item);
            if (!"tool_result".equalsIgnoreCase(stringValue(block.get("type")))) {
                continue;
            }
            String toolUseId = firstNonBlank(stringValue(block.get("tool_use_id")), stringValue(block.get("toolUseId")), "tool");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolUseId", toolUseId);
            payload.put("status", Boolean.TRUE.equals(block.get("is_error")) ? "FAILED" : "SUCCESS");
            payload.put("output", contentText(block.get("content")));
            Long startedAt = toolStartTimes.remove(toolUseId);
            if (startedAt != null) {
                payload.put("durationMs", Math.max(0L, System.currentTimeMillis() - startedAt));
            }
            emit("AGENT_TOOL_FINISHED", payload);
        }
    }

    private void acceptResult(Map<String, Object> event) {
        structuredOutput = true;
        result = firstNonBlank(stringValue(event.get("result")), stringValue(event.get("answer")));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", Boolean.TRUE.equals(event.get("is_error")) ? "FAILED" : "SUCCESS");
        putIfPresent(payload, "answer", result);
        putIfPresent(payload, "durationMs", event.get("duration_ms"));
        putIfPresent(payload, "apiDurationMs", event.get("duration_api_ms"));
        putIfPresent(payload, "numTurns", event.get("num_turns"));
        if (!privateDraft) {
            emit("AGENT_RESULT", payload);
        }
    }

    private Map<String, Object> parse(String line) {
        if (line.trim().isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(line, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Object structuredValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return mapValue(map);
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return value == null ? Map.of() : value;
    }

    private String contentText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> block = mapValue(item);
                String text = firstNonBlank(stringValue(block.get("text")), stringValue(block.get("content")));
                if (notBlank(text)) {
                    parts.add(text);
                }
            }
            return String.join("\n", parts);
        }
        return String.valueOf(content);
    }

    private void emit(String eventType, Map<String, Object> payload) {
        if (eventWriter == null) {
            return;
        }
        try {
            eventWriter.appendEvent(eventType, payload);
        } catch (RuntimeException ignored) {
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private List<Object> listValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return new ArrayList<>(list);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || notBlank(text))) {
            target.put(key, value);
        }
    }
}
