package com.webank.wedatasphere.wdsavs.aiagent.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromptSnapshotProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsImmutableDeterministicallyOrderedTaskSnapshot() throws Exception {
        RemoteCcRelayProperties properties = properties();
        RelayPromptMetadataStore store = new RelayPromptMetadataStore(properties);
        Path unified = write("unified.md", "统一规则");
        Path preB = write("pre-b.md", "第二条前置规则");
        Path preA = write("pre-a.md", "第一条前置规则");
        Path post = write("post.md", "最终检查规则");
        store.save(state("catalog-one", List.of(
                metadata("pre-b", "PRE", 10, sha256(preB), preB),
                metadata("post-check", "POST", 1, sha256(post), post),
                metadata("shared", "UNIFIED", 20, sha256(unified), unified),
                metadata("pre-a", "PRE", 10, sha256(preA), preA))));

        PromptSnapshotProvider provider = new PromptSnapshotProvider(properties);
        PromptSnapshot first = provider.snapshot();

        assertEquals("catalog-one", first.getRevision());
        assertEquals(List.of("统一规则"), first.getUnified());
        assertEquals(List.of("第一条前置规则", "第二条前置规则"), first.getPre());
        assertEquals(List.of("最终检查规则"), first.getPost());
        assertEquals(64, first.getUnifiedDigest().length());

        Files.writeString(unified, "被修改的规则", StandardCharsets.UTF_8);
        store.save(state("catalog-two", List.of(metadata("shared", "UNIFIED", 20, sha256(unified), unified))));

        assertEquals(List.of("统一规则"), first.getUnified());
        PromptSnapshot second = provider.snapshot();
        assertEquals(List.of("被修改的规则"), second.getUnified());
        assertNotEquals(first.getRevision(), second.getRevision());
    }

    @Test
    void rejectsLocallyModifiedContentBeforeTaskExecution() throws Exception {
        RemoteCcRelayProperties properties = properties();
        RelayPromptMetadataStore store = new RelayPromptMetadataStore(properties);
        Path unified = write("protected.md", "受保护的规则");
        store.save(state("catalog-protected", List.of(
                metadata("protected", "UNIFIED", 1, sha256(unified), unified))));
        PromptSnapshotProvider provider = new PromptSnapshotProvider(properties);
        Files.writeString(unified, "被篡改的规则", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, provider::snapshot);
    }

    private RemoteCcRelayProperties properties() {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setWorkingDirectory(temporaryDirectory.toString());
        properties.setPromptMetadataPath(temporaryDirectory.resolve("relay-prompts.json").toString());
        return properties;
    }

    private Path write(String name, String content) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private RelayPromptCatalogState state(String revision, List<RelayPromptMetadata> prompts) {
        RelayPromptCatalogState state = new RelayPromptCatalogState();
        state.setCatalogSha256(revision);
        state.setPrompts(prompts);
        return state;
    }

    private RelayPromptMetadata metadata(String id, String type, int order, String sha256, Path path) {
        RelayPromptMetadata metadata = new RelayPromptMetadata();
        metadata.setPromptId(id);
        metadata.setType(type);
        metadata.setOrder(order);
        metadata.setCenterSha256(sha256);
        metadata.setInstalledSha256(sha256);
        metadata.setStatus("INSTALLED");
        metadata.setContentPath(path.toString());
        metadata.setUpdatedAt(System.currentTimeMillis());
        return metadata;
    }

    private String sha256(Path path) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
