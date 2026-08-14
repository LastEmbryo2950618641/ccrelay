package com.webank.wedatasphere.wdsavs.aiagent.remote;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class PromptSnapshotProvider {

    private static final Comparator<RelayPromptMetadata> ORDER = Comparator
            .comparing(RelayPromptMetadata::getOrder)
            .thenComparing(RelayPromptMetadata::getPromptId);

    private final RelayPromptMetadataStore metadataStore;

    PromptSnapshotProvider(RemoteCcRelayProperties properties) {
        this(new RelayPromptMetadataStore(properties));
    }

    PromptSnapshotProvider(RelayPromptMetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    PromptSnapshot snapshot() {
        RelayPromptCatalogState state = metadataStore.load();
        List<RelayPromptMetadata> installed = state.getPrompts().stream()
                .filter(metadata -> "INSTALLED".equals(metadata.getStatus()))
                .toList();
        List<String> unified = content(installed, "UNIFIED");
        List<String> pre = content(installed, "PRE");
        List<String> post = content(installed, "POST");
        String unifiedDigest = digest(installed, "UNIFIED");
        String preDigest = digest(installed, "PRE");
        String postDigest = digest(installed, "POST");
        String revision = state.getCatalogSha256();
        if (revision == null || revision.isBlank()) {
            revision = digestParts(unifiedDigest, preDigest, postDigest);
        }
        return new PromptSnapshot(revision, unifiedDigest, preDigest, postDigest, unified, pre, post);
    }

    private List<String> content(List<RelayPromptMetadata> metadata, String type) {
        List<String> result = new ArrayList<>();
        metadata.stream()
                .filter(item -> type.equals(item.getType()))
                .sorted(ORDER)
                .forEach(item -> result.add(read(item)));
        return result;
    }

    private String read(RelayPromptMetadata metadata) {
        try {
            Path path = Path.of(metadata.getContentPath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Installed Prompt content is unavailable: " + metadata.getPromptId());
            }
            byte[] content = Files.readAllBytes(path);
            String actualSha256 = sha256(content);
            if (!actualSha256.equals(metadata.getInstalledSha256())) {
                throw new IllegalStateException("Installed Prompt SHA-256 mismatch: " + metadata.getPromptId());
            }
            return new String(content, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read installed Prompt: " + metadata.getPromptId(), e);
        }
    }

    private String digest(List<RelayPromptMetadata> metadata, String type) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            metadata.stream()
                    .filter(item -> type.equals(item.getType()))
                    .sorted(ORDER)
                    .forEach(item -> update(digest, item));
            return hex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate Prompt snapshot digest", e);
        }
    }

    private void update(MessageDigest digest, RelayPromptMetadata metadata) {
        byte[] bytes = (metadata.getPromptId() + "\n" + metadata.getType() + "\n"
                + metadata.getOrder() + "\n" + metadata.getInstalledSha256() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String digestParts(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return hex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate Prompt revision", e);
        }
    }

    private String sha256(byte[] content) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate installed Prompt SHA-256", e);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
