package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webank.wedatasphere.wdsavs.aiagent.skill.SkillPackageSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteSkillSyncCoordinatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void remainsInactiveWithoutCenterConfiguration() {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setWorkingDirectory(temporaryDirectory.toString());
        properties.setSkillDirectory(temporaryDirectory.resolve("skills").toString());
        RemoteSkillSyncCoordinator coordinator = new RemoteSkillSyncCoordinator(properties, new RestTemplate());
        try {
            coordinator.trigger();
            assertTrue(coordinator.summary().isEmpty());
            assertFalse(Files.exists(temporaryDirectory.resolve("skills")));
            assertFalse(Files.exists(temporaryDirectory.resolve("runtime")));
        } finally {
            coordinator.close();
        }
    }

    @Test
    void installsAndInvalidatesSkillFromCenterCatalog() throws Exception {
        byte[] artifact = skillArchive();
        Path source = temporaryDirectory.resolve("source");
        Files.createDirectories(source.resolve("references"));
        Files.writeString(source.resolve("SKILL.md"), skillMarkdown(), StandardCharsets.UTF_8);
        Files.writeString(source.resolve("references/knowledge.md"), "knowledge", StandardCharsets.UTF_8);
        String skillSha256 = SkillPackageSupport.sha256(source);
        AtomicReference<byte[]> servedArtifact = new AtomicReference<>(new byte[]{1, 2, 3});
        AtomicReference<String> status = new AtomicReference<>("ACTIVE");
        AtomicReference<String> catalogDigest = new AtomicReference<>("digest-active");
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skill/catalog/digest", exchange -> writeJson(exchange, objectMapper,
                Map.of("catalogSha256", catalogDigest.get())));
        server.createContext("/api/skill/catalog", exchange -> writeJson(exchange, objectMapper, Map.of(
                "complete", true,
                "catalogSha256", catalogDigest.get(),
                "skills", java.util.List.of(Map.of(
                        "skillId", "sample-skill",
                        "sha256", skillSha256,
                        "status", status.get(),
                        "artifactSize", artifact.length,
                        "artifactUrl", "/api/skill/catalog/sample-skill/artifact")))));
        server.createContext("/api/skill/catalog/sample-skill/artifact", exchange -> {
            byte[] responseArtifact = servedArtifact.get();
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, responseArtifact.length);
            exchange.getResponseBody().write(responseArtifact);
            exchange.close();
        });
        server.start();

        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setPort(19192);
        properties.setWorkingDirectory(temporaryDirectory.toString());
        properties.setSkillDirectory(temporaryDirectory.resolve("skills").toString());
        properties.setSkillMetadataDbPath(temporaryDirectory.resolve("relay-skills.db").toString());
        properties.setCenterHeartbeatEndpoint("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/skill/relay/heartbeat");
        RemoteSkillSyncCoordinator coordinator = new RemoteSkillSyncCoordinator(properties, new RestTemplate());
        try {
            coordinator.synchronize();
            Path installed = temporaryDirectory.resolve("skills/sample-skill");
            assertFalse(Files.exists(installed));
            assertEquals("INSTALLING", coordinator.summary().get(0).get("status"));

            servedArtifact.set(artifact);
            coordinator.synchronize();
            assertTrue(Files.isRegularFile(installed.resolve("SKILL.md")));
            assertEquals("INSTALLED", coordinator.summary().get(0).get("status"));

            status.set("INVALID");
            catalogDigest.set("digest-invalid");
            coordinator.synchronize();
            assertFalse(Files.exists(installed));
            assertEquals("INVALID", coordinator.summary().get(0).get("status"));
        } finally {
            coordinator.close();
            server.stop(0);
        }
    }

    private byte[] skillArchive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            output.putNextEntry(new ZipEntry("SKILL.md"));
            output.write(skillMarkdown().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("references/knowledge.md"));
            output.write("knowledge".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private String skillMarkdown() {
        return "---\nname: sample-skill\ndescription: sample\n---\n\n# Sample\n";
    }

    private void writeJson(HttpExchange exchange, ObjectMapper objectMapper, Object value) throws java.io.IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
