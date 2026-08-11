package com.webank.wedatasphere.wdsavs.aiagent.remote;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeCodeStreamCollectorTest {

    @Test
    void collectsVisibleAgentMessagesAndToolResultsInOrder() {
        List<String> types = new ArrayList<>();
        List<Map<String, Object>> payloads = new ArrayList<>();
        ClaudeCodeStreamCollector collector = new ClaudeCodeStreamCollector((type, payload) -> {
            types.add(type);
            payloads.add(payload);
        });

        collector.acceptLine("{\"type\":\"system\",\"subtype\":\"init\"}");
        collector.acceptLine("{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[{\"type\":\"text\",\"text\":\"先检查日志。\"},{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"Bash\",\"input\":{\"command\":\"tail -n 20 app.log\"}}]}}");
        collector.acceptLine("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tool-1\",\"content\":\"ERROR timeout\",\"is_error\":false}]}}");
        collector.acceptLine("{\"type\":\"assistant\",\"message\":{\"id\":\"m2\",\"content\":[{\"type\":\"text\",\"text\":\"日志显示任务因超时失败。\"}]}}");
        collector.acceptLine("{\"type\":\"result\",\"is_error\":false,\"result\":\"已确认失败原因。\",\"duration_ms\":1200}");

        assertTrue(collector.isStructuredOutput());
        assertEquals("已确认失败原因。", collector.answer());
        assertEquals(List.of("AGENT_MESSAGE", "AGENT_TOOL_STARTED", "AGENT_TOOL_FINISHED", "AGENT_MESSAGE", "AGENT_RESULT"), types);
        assertEquals("ERROR timeout", payloads.get(2).get("output"));
        assertEquals("已确认失败原因。", payloads.get(4).get("answer"));
    }

    @Test
    void doesNotExposeHiddenThinkingBlocks() {
        List<String> types = new ArrayList<>();
        ClaudeCodeStreamCollector collector = new ClaudeCodeStreamCollector((type, payload) -> types.add(type));

        collector.acceptLine("{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"private chain of thought\"},{\"type\":\"text\",\"text\":\"开始执行。\"}]}}");

        assertEquals(List.of("AGENT_MESSAGE"), types);
        assertFalse(collector.answer().contains("private chain of thought"));
    }

    @Test
    void preservesPlainTextForNonStructuredCommandOutput() {
        ClaudeCodeStreamCollector collector = new ClaudeCodeStreamCollector(null);
        collector.acceptLine("plain final answer");

        assertFalse(collector.isStructuredOutput());
        assertEquals("plain final answer", collector.answer());
    }
}
