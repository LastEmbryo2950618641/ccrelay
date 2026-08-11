package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiModelConfigEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessageFormatter;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class OpenAiCompatibleRelayService implements AiRelayService {

    private final RestTemplate restTemplate;
    private final AiModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleRelayService(RestTemplate restTemplate, AiModelConfigService modelConfigService) {
        this.restTemplate = restTemplate;
        this.modelConfigService = modelConfigService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        validateRequest(request);
        AiModelConfig requestConfig = request.getModelConfig();
        AiModelConfigEntity storedConfig = modelConfigService.resolve(requestConfig.getId(), requestConfig.getProvider());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", normalizeModel(requestConfig.getModel(), storedConfig.getModelName()));
        payload.put("messages", buildMessages(request));
        payload.put("stream", false);
        putIfNotNull(payload, "temperature", storedConfig.getTemperature());
        putIfNotNull(payload, "top_p", storedConfig.getTopP());
        putIfNotNull(payload, "max_tokens", storedConfig.getMaxTokens());
        putIfNotNull(payload, "presence_penalty", storedConfig.getPresencePenalty());
        putIfNotNull(payload, "frequency_penalty", storedConfig.getFrequencyPenalty());
        payload.putAll(parseJsonObject(storedConfig.getExtraBodyJson(), "extraBodyJson"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!isBlank(storedConfig.getApiKey())) {
            headers.setBearerAuth(storedConfig.getApiKey().trim());
        }
        parseJsonObject(storedConfig.getExtraHeadersJson(), "extraHeadersJson")
                .forEach((key, value) -> headers.set(key, String.valueOf(value)));

        String response = restTemplate.postForObject(chatCompletionsUrl(storedConfig),
                new HttpEntity<>(payload, headers), String.class);
        Map<String, Object> responseMap = parseResponse(response);
        AiChatResponse chatResponse = new AiChatResponse(
                answerValue(responseMap),
                stringValue(responseMap, "status", "SUCCESS"),
                traceIdValue(responseMap)
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
    }

    private List<Map<String, Object>> buildMessages(AiChatRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (!isBlank(request.getSystemPrompt())) {
            messages.add(message("system", request.getSystemPrompt()));
        }
        if (request.getMessages() != null) {
            for (AiChatMessage message : request.getMessages()) {
                messages.add(message(
                        isBlank(message.getRole()) ? "user" : message.getRole(),
                        AiChatMessageFormatter.modelContent(message)
                ));
            }
        }
        return messages;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private String normalizeModel(String requestModel, String defaultModel) {
        return isBlank(requestModel) || "backend-default".equalsIgnoreCase(requestModel.trim())
                ? defaultModel.trim()
                : requestModel.trim();
    }

    private String chatCompletionsUrl(AiModelConfigEntity storedConfig) {
        String url = storedConfig.getBaseUrl() == null ? "" : storedConfig.getBaseUrl().trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("AI模型Base URL不能为空");
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String apiPath = isBlank(storedConfig.getApiPath()) ? "/chat/completions" : storedConfig.getApiPath().trim();
        if (apiPath.startsWith("http://") || apiPath.startsWith("https://")) {
            return apiPath;
        }
        if (!apiPath.startsWith("/")) {
            apiPath = "/" + apiPath;
        }
        if (url.endsWith(apiPath)) {
            return url;
        }
        return url + apiPath;
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private Map<String, Object> parseJsonObject(String json, String fieldName) {
        if (isBlank(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("AI模型配置" + fieldName + "不是合法JSON对象");
        }
    }

    private Map<String, Object> parseResponse(String response) {
        if (isBlank(response)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("AI model response was not JSON");
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("answer", response);
            fallback.put("status", "SUCCESS");
            return fallback;
        }
    }

    private String answerValue(Map<String, Object> responseMap) {
        Object choices = responseMap.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> choiceMap) {
                Object message = choiceMap.get("message");
                if (message instanceof Map<?, ?> messageMap) {
                    Object content = messageMap.get("content");
                    if (!isBlank(String.valueOf(content))) {
                        return String.valueOf(content);
                    }
                }
                Object delta = choiceMap.get("delta");
                if (delta instanceof Map<?, ?> deltaMap) {
                    Object content = deltaMap.get("content");
                    if (!isBlank(String.valueOf(content))) {
                        return String.valueOf(content);
                    }
                }
            }
        }
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


    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listValue(Map<String, Object> source, String key, List<Map<String, Object>> defaultValue) {
        Object value = source.get(key);
        if (!(value instanceof List<?> list)) {
            return defaultValue == null ? List.of() : defaultValue;
        }
        List<Map<String, Object>> result = new ArrayList<>();
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

    private String traceIdValue(Map<String, Object> responseMap) {
        String traceId = stringValue(responseMap, "traceId", null);
        if (traceId != null) {
            return traceId;
        }
        traceId = stringValue(responseMap, "trace_id", null);
        if (traceId != null) {
            return traceId;
        }
        traceId = stringValue(responseMap, "id", null);
        if (traceId != null) {
            return traceId;
        }
        return nestedStringValue(responseMap, "data", "traceId", null);
    }

    private String stringValue(Map<String, Object> source, String key, String defaultValue) {
        Object value = source.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? defaultValue : text;
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
