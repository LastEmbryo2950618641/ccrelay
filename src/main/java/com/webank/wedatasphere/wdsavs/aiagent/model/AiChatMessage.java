package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class AiChatMessage {

    private String role;
    private String content;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public AiChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
