package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemotePromptSyncCoordinatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void remainsInactiveWithoutCenterConfiguration() {
        RemoteCcRelayProperties properties = properties();
        RemotePromptSyncCoordinator coordinator = new RemotePromptSyncCoordinator(properties, new RestTemplate());
        try {
            coordinator.trigger();
            assertTrue(coordinator.summary().isEmpty());
            assertFalse(Files.exists(temporaryDirectory.resolve("prompts")));
        } finally {
            coordinator.close();
        }
    }

    @Test
    void synchronizesAtomicallyAndRetainsPreviousRevisionOnFailure() throws Exception {
        byte[] firstContent = "统一检查事实".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "更新后的统一规则".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> digestOverride = new AtomicReference<>();
        AtomicReference<String> status = new AtomicReference<>("ACTIVE");
        AtomicReference<String> contentSha = new AtomicReference<>(sha256(firstContent));
        AtomicReference<byte[]> servedContent = new AtomicReference<>(firstContent);
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/prompt/catalog/digest", exchange -> writeJson(exchange, objectMapper,
                Map.of("catalogSha256", currentDigest(digestOverride, status, contentSha))));
        server.createContext("/api/prompt/catalog", exchange -> writeJson(exchange, objectMapper, Map.of(
                "complete", true,
                "catalogSha256", currentDigest(digestOverride, status, contentSha),
                "prompts", List.of(Map.of(
                        "promptId", "shared-rules",
                        "type", "UNIFIED",
                        "order", 10,
                        "sha256", contentSha.get(),
                        "status", status.get(),
                        "contentSize", servedContent.get().length,
                        "contentUrl", "/api/prompt/catalog/shared-rules/content")))));
        server.createContext("/api/prompt/catalog/shared-rules/content", exchange -> {
            byte[] content = servedContent.get();
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();

        RemoteCcRelayProperties properties = properties();
        properties.setCenterHeartbeatEndpoint("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/skill/relay/heartbeat");
        RemotePromptSyncCoordinator coordinator = new RemotePromptSyncCoordinator(properties, new RestTemplate());
        try {
            coordinator.synchronize();
            String firstRevision = catalogDigest(status.get(), contentSha.get());
            assertEquals(firstRevision, coordinator.snapshot().getCatalogSha256());
            assertEquals("统一检查事实", Files.readString(coordinator.snapshot().getPrompts().get(0).contentPath()));
            assertEquals("INSTALLED", coordinator.summary().get(0).get("status"));

            digestOverride.set("tampered-catalog-digest");
            coordinator.synchronize();
            assertEquals(firstRevision, coordinator.snapshot().getCatalogSha256());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, coordinator::requireCurrent);

            digestOverride.set(null);
            contentSha.set(sha256(secondContent));
            servedContent.set("损坏内容".getBytes(StandardCharsets.UTF_8));
            coordinator.synchronize();
            assertEquals(firstRevision, coordinator.snapshot().getCatalogSha256());
            assertEquals("统一检查事实", Files.readString(coordinator.snapshot().getPrompts().get(0).contentPath()));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, coordinator::requireCurrent);

            servedContent.set(secondContent);
            coordinator.requireCurrent();
            String secondRevision = catalogDigest(status.get(), contentSha.get());
            assertEquals(secondRevision, coordinator.snapshot().getCatalogSha256());
            assertEquals("更新后的统一规则", Files.readString(coordinator.snapshot().getPrompts().get(0).contentPath()));

            status.set("INVALID");
            coordinator.synchronize();
            assertEquals(catalogDigest(status.get(), contentSha.get()), coordinator.snapshot().getCatalogSha256());
            assertTrue(coordinator.snapshot().getPrompts().isEmpty());
            assertEquals("INVALID", coordinator.summary().get(0).get("status"));
        } finally {
            coordinator.close();
            server.stop(0);
        }
    }

    private RemoteCcRelayProperties properties() {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setPort(19192);
        properties.setWorkingDirectory(temporaryDirectory.toString());
        properties.setPromptDirectory(temporaryDirectory.resolve("prompts").toString());
        properties.setPromptMetadataPath(temporaryDirectory.resolve("relay-prompts.json").toString());
        return properties;
    }

    private String sha256(byte[] content) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(content)) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private String currentDigest(AtomicReference<String> override,
                                 AtomicReference<String> status,
                                 AtomicReference<String> contentSha) {
        return override.get() == null ? catalogDigest(status.get(), contentSha.get()) : override.get();
    }

    private String catalogDigest(String status, String contentSha) {
        try {
            byte[] record = ("shared-rules\nUNIFIED\n10\n" + contentSha + "\n" + status + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(record.length).array());
            digest.update(record);
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeJson(HttpExchange exchange, ObjectMapper objectMapper, Object value) throws java.io.IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
