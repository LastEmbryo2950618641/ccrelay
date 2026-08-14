package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiPromptEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptInstallResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptType;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiPromptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptCatalogServiceImplTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void installsReplacesSortsAndInvalidatesPrompts() throws Exception {
        List<AiPromptEntity> saved = new ArrayList<>();
        AiPromptRepository repository = repository(saved);
        PromptCatalogServiceImpl service = new PromptCatalogServiceImpl(repository, temporaryDirectory.resolve("prompts"));

        PromptInstallResponse unified = service.install(
                "shared-rules", PromptType.UNIFIED, 20, "共享规则".getBytes(StandardCharsets.UTF_8));
        service.install("pre-check", PromptType.PRE, 10, "先检查证据".getBytes(StandardCharsets.UTF_8));
        service.install("pre-alpha", PromptType.PRE, 10, "先确认目标".getBytes(StandardCharsets.UTF_8));

        assertEquals("ACTIVE", unified.getStatus());
        assertEquals(64, unified.getSha256().length());
        assertEquals("共享规则", Files.readString(service.content("shared-rules"), StandardCharsets.UTF_8));

        PromptCatalogResponse firstCatalog = service.catalog();
        assertEquals(List.of("shared-rules", "pre-alpha", "pre-check"), firstCatalog.getPrompts().stream()
                .map(entry -> entry.getPromptId()).toList());
        assertEquals(64, firstCatalog.getCatalogSha256().length());
        assertEquals(firstCatalog.getCatalogSha256(), service.digest().getCatalogSha256());

        PromptInstallResponse replaced = service.install(
                "shared-rules", PromptType.POST, 5, "最终精炼".getBytes(StandardCharsets.UTF_8));
        assertEquals(PromptType.POST, replaced.getType());
        assertEquals(5, replaced.getOrder());
        assertEquals("最终精炼", Files.readString(service.content("shared-rules"), StandardCharsets.UTF_8));

        PromptInstallResponse invalid = service.invalidate("shared-rules");
        assertEquals("INVALID", invalid.getStatus());
        assertThrows(IllegalArgumentException.class, () -> service.content("shared-rules"));
        assertTrue(service.catalog().getPrompts().stream()
                .filter(entry -> "shared-rules".equals(entry.getPromptId()))
                .allMatch(entry -> entry.getContentUrl() == null));
    }

    @Test
    void rejectsInvalidPromptInputWithoutChangingCatalog() {
        List<AiPromptEntity> saved = new ArrayList<>();
        PromptCatalogServiceImpl service = new PromptCatalogServiceImpl(repository(saved), temporaryDirectory.resolve("prompts"));

        assertThrows(IllegalArgumentException.class,
                () -> service.install("Invalid Id", PromptType.PRE, 0, "content".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> service.install("empty", PromptType.PRE, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> service.install("nul", PromptType.PRE, 0, new byte[]{'a', 0, 'b'}));
        assertThrows(IllegalArgumentException.class,
                () -> service.install("negative", PromptType.PRE, -1, "content".getBytes(StandardCharsets.UTF_8)));
        assertTrue(saved.isEmpty());
    }

    private AiPromptRepository repository(List<AiPromptEntity> saved) {
        AiPromptRepository repository = mock(AiPromptRepository.class);
        when(repository.findByPromptId(any())).thenAnswer(invocation -> saved.stream()
                .filter(entity -> invocation.getArgument(0).equals(entity.getPromptId()))
                .findFirst());
        when(repository.findAll()).thenAnswer(invocation -> saved.stream()
                .sorted(Comparator.comparing(AiPromptEntity::getType)
                        .thenComparing(AiPromptEntity::getPromptOrder)
                        .thenComparing(AiPromptEntity::getPromptId))
                .toList());
        when(repository.save(any(AiPromptEntity.class))).thenAnswer(invocation -> {
            AiPromptEntity entity = invocation.getArgument(0);
            Optional<AiPromptEntity> existing = saved.stream()
                    .filter(value -> value.getPromptId().equals(entity.getPromptId()))
                    .findFirst();
            if (existing.isEmpty()) {
                saved.add(entity);
            }
            return entity;
        });
        return repository;
    }
}
