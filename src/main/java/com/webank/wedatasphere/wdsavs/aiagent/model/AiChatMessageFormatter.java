package com.webank.wedatasphere.wdsavs.aiagent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AiChatMessageFormatter {

    private static final List<String> SOURCE_KEYS = List.of(
            "eventId",
            "taskId",
            "senderType",
            "senderId",
            "targetNodeIds",
            "cursor",
            "createdTime",
            "contentType",
            "contentRef"
    );

    private AiChatMessageFormatter() {
    }

    public static String modelContent(AiChatMessage message) {
        if (message == null) {
            return "";
        }
        String content = value(message.getContent());
        Map<String, Object> metadata = message.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return content;
        }
        List<String> attributes = new ArrayList<>();
        for (String key : SOURCE_KEYS) {
            String attribute = metadataValue(metadata.get(key));
            if (!attribute.isBlank()) {
                attributes.add(key + ": " + attribute);
            }
        }
        if (attributes.isEmpty()) {
            return content;
        }
        StringBuilder formatted = new StringBuilder("[CC_CONTEXT_EVENT]\n");
        attributes.forEach(attribute -> formatted.append(attribute).append('\n'));
        formatted.append("[/CC_CONTEXT_EVENT]\n").append(content);
        return formatted.toString();
    }

    private static String metadataValue(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                String text = singleLine(item);
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
            return String.join(",", values);
        }
        return singleLine(value);
    }

    private static String singleLine(Object value) {
        return value == null ? "" : String.valueOf(value).replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
