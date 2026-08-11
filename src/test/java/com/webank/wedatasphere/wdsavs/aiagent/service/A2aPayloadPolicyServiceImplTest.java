package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aPayloadPolicyServiceImplTest {

    @Test
    void rejectsInlineLargeFileContentForTaskPayload() {
        A2aPayloadPolicyServiceImpl service = new A2aPayloadPolicyServiceImpl(10L, List.of("*"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("attachments", List.of(Map.of(
                "name", "large.txt",
                "content", "01234567890",
                "sizeBytes", 11
        )));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.validateTaskParams(params));
        assertTrue(error.getMessage().contains("does not allow inline large file content"));
        assertTrue(error.getMessage().contains("threshold=10 bytes") || error.getMessage().contains("10 bytes"));
    }

    @Test
    void allowsLargeReferenceWithinConfiguredWorkspaceBoundary() {
        A2aPayloadPolicyServiceImpl service = new A2aPayloadPolicyServiceImpl(
                10L,
                List.of(),
                List.of("/workspace/allowed"),
                List.of("/logs/allowed"),
                List.of("/code/allowed"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("attachments", List.of(Map.of(
                "name", "large-ref.txt",
                "sizeBytes", 99,
                "fileRef", Map.of(
                        "path", "/workspace/allowed/run/result.txt",
                        "category", "workspace"
                )
        )));

        service.validateTaskParams(params);
    }

    @Test
    void rejectsReferenceOutsideConfiguredCodeBoundary() {
        A2aPayloadPolicyServiceImpl service = new A2aPayloadPolicyServiceImpl(
                10L,
                List.of(),
                List.of("/workspace/allowed"),
                List.of("/logs/allowed"),
                List.of("/code/allowed"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("attachments", List.of(Map.of(
                "name", "source.zip",
                "sizeBytes", 99,
                "fileRef", Map.of(
                        "path", "/tmp/forbidden/source.zip",
                        "category", "code"
                )
        )));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.validateMessageParams(params));
        assertTrue(error.getMessage().contains("outside allowed boundaries"));
        assertTrue(error.getMessage().contains("/tmp/forbidden/source.zip"));
    }

    @Test
    void normalizesStructuredResultByOmittingLargeInlineContentAndKeepingReference() {
        A2aPayloadPolicyServiceImpl service = new A2aPayloadPolicyServiceImpl(10L, List.of("*"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifacts", List.of(Map.of(
                "name", "artifact.bin",
                "content", "01234567890",
                "path", "/workspace/run/artifact.bin"
        )));

        Map<String, Object> normalized = service.normalizeStructuredResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) ((List<?>) normalized.get("artifacts")).get(0);
        assertFalse(artifact.containsKey("content"));
        assertEquals(true, artifact.get("inlineContentOmitted"));
        assertEquals(true, artifact.get("referenceOnly"));
        assertEquals(11L, ((Number) artifact.get("sizeBytes")).longValue());
        assertNotNull(artifact.get("sha256"));
        assertEquals("/workspace/run/artifact.bin", artifact.get("path"));
    }

    @Test
    void usesDefaultFiveMbThresholdWhenConfiguredThresholdIsNonPositive() {
        A2aPayloadPolicyServiceImpl service = new A2aPayloadPolicyServiceImpl(0L, List.of("*"));
        String largeContent = "a".repeat(5 * 1024 * 1024 + 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", List.of(Map.of(
                "name", "huge.log",
                "content", largeContent,
                "path", "/logs/huge.log"
        )));

        Map<String, Object> normalized = service.normalizeStructuredResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> file = (Map<String, Object>) ((List<?>) normalized.get("files")).get(0);
        assertFalse(file.containsKey("content"));
        assertEquals(true, file.get("inlineContentOmitted"));
        assertEquals(true, file.get("referenceOnly"));
        assertEquals((long) largeContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                ((Number) file.get("sizeBytes")).longValue());
    }

    @Test
    void redactsSecretsFromNormalizedChatResponse() {
        A2aPayloadPolicyServiceImpl service = new A2aPayloadPolicyServiceImpl(10L, List.of("*"));
        AiChatResponse response = new AiChatResponse();
        response.setAnswer("apiKey: sk-1234567890abcdef should not leak");
        response.setSummary("signedToken=abc.def and secret fake-token");
        response.setMetadata(Map.of("apiKey", "sk-abcdef123456", "nested", Map.of("secret", "value")));
        response.setDiagnostics(Map.of("taskId", "relay-task-parent-sync-1"));

        AiChatResponse normalized = service.normalizeChatResponse(response);

        assertFalse(normalized.getAnswer().contains("sk-1234567890abcdef"));
        assertFalse(normalized.getSummary().contains("abc.def"));
        assertFalse(normalized.getSummary().contains("fake-token"));
        assertEquals("relay-task-parent-sync-1", normalized.getDiagnostics().get("taskId"));
        assertEquals("[REDACTED]", normalized.getMetadata().get("apiKey"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) normalized.getMetadata().get("nested");
        assertEquals("[REDACTED]", nested.get("secret"));
    }
}
