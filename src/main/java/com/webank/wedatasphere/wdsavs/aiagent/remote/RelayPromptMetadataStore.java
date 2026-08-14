package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

final class RelayPromptMetadataStore {

    private final Path metadataPath;
    private final ObjectMapper objectMapper;

    RelayPromptMetadataStore(RemoteCcRelayProperties properties) {
        this(resolveMetadataPath(properties), new ObjectMapper());
    }

    RelayPromptMetadataStore(Path metadataPath, ObjectMapper objectMapper) {
        this.metadataPath = metadataPath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    synchronized RelayPromptCatalogState load() {
        if (!Files.isRegularFile(metadataPath)) {
            return new RelayPromptCatalogState();
        }
        try {
            RelayPromptCatalogState state = objectMapper.readValue(metadataPath.toFile(), RelayPromptCatalogState.class);
            return state == null ? new RelayPromptCatalogState() : state;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read Relay Prompt metadata", e);
        }
    }

    synchronized void save(RelayPromptCatalogState state) {
        Path parent = metadataPath.getParent();
        Path staging = metadataPath.resolveSibling(metadataPath.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(staging.toFile(), state);
            try {
                Files.move(staging, metadataPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                Files.move(staging, metadataPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save Relay Prompt metadata", e);
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (Exception ignored) {
            }
        }
    }

    private static Path resolveMetadataPath(RemoteCcRelayProperties properties) {
        if (properties != null && !blank(properties.getPromptMetadataPath())) {
            return Path.of(properties.getPromptMetadataPath());
        }
        Path root = properties != null && !blank(properties.getWorkingDirectory())
                ? Path.of(properties.getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
        String suffix = String.valueOf(properties == null ? 18091 : properties.getPort());
        return root.resolve("runtime/relay-prompts-" + suffix + ".json");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
