package com.webank.wedatasphere.wdsavs.aiagentskill;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObserverMarkdownRenderingTest {

    @Test
    void rendersFinalAgentRepliesAsLocalSafeMarkdown() throws IOException {
        String page;
        try (InputStream input = getClass().getResourceAsStream("/static/ccrelay-observer.html")) {
            assertTrue(input != null);
            page = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(page.contains("function addMarkdown(parent, value, className)"));
        assertTrue(page.contains("if (item.kind === \"answer\") addMarkdown(content, item.text, \"message-text\")"));
        assertTrue(page.contains("element.textContent = token.slice"));
        assertTrue(page.contains("https?:\\/\\/"));
        assertFalse(page.contains("marked.min.js"));
        assertFalse(page.contains("markdown-it"));
    }

    @Test
    void doesNotSynchronizeClosedHistoricalSessions() throws IOException {
        String page;
        try (InputStream input = getClass().getResourceAsStream("/static/ccrelay-observer.html")) {
            assertTrue(input != null);
            page = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(page.contains("if (session.status === \"OPEN\")"));
        assertTrue(page.contains("/sync`"));
    }
}
