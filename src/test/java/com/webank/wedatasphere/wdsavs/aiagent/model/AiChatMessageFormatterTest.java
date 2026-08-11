package com.webank.wedatasphere.wdsavs.aiagent.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatMessageFormatterTest {

    @Test
    void rendersOnlyAllowlistedContextSourceMetadata() {
        AiChatMessage message = new AiChatMessage("assistant", "节点结论");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("senderType", "RELAY");
        metadata.put("senderId", "node-a:19192");
        metadata.put("targetNodeIds", List.of("node-b:19193", "node-c:19194"));
        metadata.put("taskId", "task-1");
        metadata.put("signedToken", "must-not-leak");
        message.setMetadata(metadata);

        String content = AiChatMessageFormatter.modelContent(message);

        assertTrue(content.contains("senderId: node-a:19192"));
        assertTrue(content.contains("targetNodeIds: node-b:19193,node-c:19194"));
        assertTrue(content.contains("taskId: task-1"));
        assertTrue(content.endsWith("节点结论"));
        assertFalse(content.contains("must-not-leak"));
        assertFalse(content.contains("signedToken"));
    }

    @Test
    void keepsLegacyMessageContentUnchanged() {
        assertEquals("旧消息", AiChatMessageFormatter.modelContent(new AiChatMessage("user", "旧消息")));
    }
}
