package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSkillEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillInstallResponse;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillCatalogServiceImplTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void installPublishesCatalogAndInvalidateKeepsTombstone() throws Exception {
        AiSkillRepository repository = mock(AiSkillRepository.class);
        AtomicReference<AiSkillEntity> saved = new AtomicReference<>();
        when(repository.findBySkillId("sample-skill")).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(repository.findAllByOrderBySkillIdAsc()).thenAnswer(invocation ->
                saved.get() == null ? List.of() : List.of(saved.get()));
        when(repository.save(any(AiSkillEntity.class))).thenAnswer(invocation -> {
            AiSkillEntity entity = invocation.getArgument(0);
            saved.set(entity);
            return entity;
        });

        SkillCatalogServiceImpl service = new SkillCatalogServiceImpl(repository, temporaryDirectory.resolve("skills"));
        SkillInstallResponse installed = service.install(skillArchive());

        assertEquals("sample-skill", installed.getSkillId());
        assertEquals("ACTIVE", installed.getStatus());
        assertEquals(64, installed.getSha256().length());
        assertNotNull(service.artifact("sample-skill"));
        SkillCatalogResponse catalog = service.catalog();
        assertEquals(1, catalog.getSkills().size());
        assertEquals(64, catalog.getCatalogSha256().length());

        SkillInstallResponse invalid = service.invalidate("sample-skill");
        assertEquals("INVALID", invalid.getStatus());
        assertEquals("INVALID", service.catalog().getSkills().get(0).getStatus());
    }

    private byte[] skillArchive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            output.putNextEntry(new ZipEntry("SKILL.md"));
            output.write("---\nname: sample-skill\ndescription: sample\n---\n\n# Sample\n"
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("references/knowledge.md"));
            output.write("knowledge".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
