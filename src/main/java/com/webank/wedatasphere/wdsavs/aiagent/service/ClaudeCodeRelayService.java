package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ClaudeCodeRelayService implements AiRelayService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ClaudeCodeRelayService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        validateRequest(request);
        AiModelConfig modelConfig = request.getModelConfig();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemPrompt", request.getSystemPrompt());
        payload.put("messages", request.getMessages());
        payload.put("model", modelConfig.getModel());
        payload.put("endpoint", relayEndpoint(modelConfig));
        payload.put("centerEndpoint", modelConfig.getEndpoint());
        payload.put("remoteEndpoint", modelConfig.getRemoteEndpoint());
        payload.put("provider", modelConfig.getProvider());
        payload.put("claudeCode", modelConfig.getClaudeCode());
        payload.put("convergencePolicy", modelConfig.getConvergencePolicy());
        payload.put("metadata", request.getMetadata());

        String response = restTemplate.postForObject(relayEndpoint(modelConfig), payload, String.class);
        Map<String, Object> responseMap = parseResponse(response);
        AiChatResponse chatResponse = new AiChatResponse(
                answerValue(responseMap),
                stringValue(responseMap, "status", "SUCCESS"),
                stringValue(responseMap, "traceId", nestedStringValue(responseMap, "data", "traceId", null))
        );
        chatResponse.setSummary(stringValue(responseMap, "summary", nestedStringValue(responseMap, "data", "summary", null)));
        chatResponse.setArtifacts(listValue(responseMap, "artifacts", nestedListValue(responseMap, "data", "artifacts")));
        chatResponse.setDiagnostics(mapValue(responseMap, "diagnostics", nestedMapValue(responseMap, "data", "diagnostics")));
        chatResponse.setMetadata(mapValue(responseMap, "metadata", nestedMapValue(responseMap, "data", "metadata")));
        return chatResponse;
    }

    private void validateRequest(AiChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AiChatRequest is required");
        }
        if (request.getModelConfig() == null) {
            throw new IllegalArgumentException("AiChatRequest.modelConfig is required");
        }
        if (!"CLAUDE_CODE".equalsIgnoreCase(request.getModelConfig().getProvider())) {
            throw new IllegalArgumentException("Only CLAUDE_CODE provider is supported");
        }
        if (relayEndpoint(request.getModelConfig()).trim().isEmpty()) {
            throw new IllegalArgumentException("AiChatRequest.modelConfig.remoteEndpoint is required");
        }
    }

    private String relayEndpoint(AiModelConfig modelConfig) {
        if (modelConfig.getRemoteEndpoint() != null && !modelConfig.getRemoteEndpoint().trim().isEmpty()) {
            return modelConfig.getRemoteEndpoint().trim();
        }
        return modelConfig.getEndpoint() == null ? "" : modelConfig.getEndpoint().trim();
    }

    private String stringValue(Map<String, Object> source, String key, String defaultValue) {
        Object value = source.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? defaultValue : text;
    }

    private String answerValue(Map<String, Object> responseMap) {
        String dataAnswer = nestedStringValue(responseMap, "data", "answer", null);
        if (dataAnswer != null) {
            return dataAnswer;
        }
        String dataContent = nestedStringValue(responseMap, "data", "content", null);
        if (dataContent != null) {
            return dataContent;
        }
        String answer = stringValue(responseMap, "answer", null);
        if (answer != null) {
            return answer;
        }
        String content = stringValue(responseMap, "content", null);
        if (content != null) {
            return content;
        }
        return stringValue(responseMap, "message", "");
    }

    private Map<String, Object> parseResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Remote Claude Code relay returned non-json response");
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("answer", response);
            fallback.put("status", "SUCCESS");
            return fallback;
        }
    }


    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listValue(Map<String, Object> source, String key, List<Map<String, Object>> defaultValue) {
        Object value = source.get(key);
        if (!(value instanceof List<?> list)) {
            return defaultValue == null ? List.of() : defaultValue;
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> entry = new LinkedHashMap<>();
                for (Map.Entry<?, ?> element : map.entrySet()) {
                    entry.put(String.valueOf(element.getKey()), element.getValue());
                }
                result.add(entry);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Map<String, Object> source, String key, Map<String, Object> defaultValue) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return defaultValue == null ? Map.of() : defaultValue;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nestedListValue(Map<String, Object> source, String nestedKey, String leafKey) {
        Object nested = source.get(nestedKey);
        if (!(nested instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object value = ((Map<String, Object>) map).get(leafKey);
        if (!(value instanceof List<?>)) {
            return List.of();
        }
        return listValue(Map.of(leafKey, value), leafKey, List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMapValue(Map<String, Object> source, String nestedKey, String leafKey) {
        Object nested = source.get(nestedKey);
        if (!(nested instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Object value = ((Map<String, Object>) map).get(leafKey);
        if (!(value instanceof Map<?, ?>)) {
            return Map.of();
        }
        return mapValue(Map.of(leafKey, value), leafKey, Map.of());
    }

    @SuppressWarnings("unchecked")
    private String nestedStringValue(Map<String, Object> source, String nestedKey, String leafKey, String defaultValue) {
        Object nested = source.get(nestedKey);
        if (!(nested instanceof Map)) {
            return defaultValue;
        }
        Object value = ((Map<String, Object>) nested).get(leafKey);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? defaultValue : text;
    }
}
