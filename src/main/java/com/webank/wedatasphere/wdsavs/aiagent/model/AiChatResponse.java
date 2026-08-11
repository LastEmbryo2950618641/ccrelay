package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class AiChatResponse {

    private String answer;
    private String status;
    private String traceId;
    private String summary;
    private List<Map<String, Object>> artifacts = new ArrayList<>();
    private Map<String, Object> diagnostics = new LinkedHashMap<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public AiChatResponse(String answer, String status, String traceId) {
        this.answer = answer;
        this.status = status;
        this.traceId = traceId;
    }

    public AiChatResponse(String answer,
                          String status,
                          String traceId,
                          String summary,
                          List<Map<String, Object>> artifacts,
                          Map<String, Object> diagnostics,
                          Map<String, Object> metadata) {
        this.answer = answer;
        this.status = status;
        this.traceId = traceId;
        this.summary = summary;
        this.artifacts = artifacts == null ? new ArrayList<>() : new ArrayList<>(artifacts);
        this.diagnostics = diagnostics == null ? new LinkedHashMap<>() : new LinkedHashMap<>(diagnostics);
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }
}
