package com.webank.wedatasphere.wdsavs.aiagent.model;

public enum AiCollaborationMode {
    DIRECT,
    INDEPENDENT_FANOUT,
    DISCUSSION,
    SESSION_FOLLOW_UP;

    public static AiCollaborationMode from(String value, AiCollaborationMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Unsupported collaborationMode: " + value);
        }
    }
}
