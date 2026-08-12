package com.webank.wedatasphere.wdsavs.aiagent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillPackageSupportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void sha256IsIndependentFromCreationOrderAndAbsolutePath() throws Exception {
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        Files.createDirectories(first.resolve("references"));
        Files.writeString(first.resolve("SKILL.md"), skillMarkdown(), StandardCharsets.UTF_8);
        Files.writeString(first.resolve("references/knowledge.md"), "knowledge", StandardCharsets.UTF_8);

        Files.createDirectories(second.resolve("references"));
        Files.writeString(second.resolve("references/knowledge.md"), "knowledge", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("SKILL.md"), skillMarkdown(), StandardCharsets.UTF_8);

        assertEquals(SkillPackageSupport.sha256(first), SkillPackageSupport.sha256(second));
        Files.writeString(second.resolve("references/knowledge.md"), "changed", StandardCharsets.UTF_8);
        assertNotEquals(SkillPackageSupport.sha256(first), SkillPackageSupport.sha256(second));
    }

    @Test
    void rejectsRelativePathSegmentsEvenWhenTheyRemainInsideDestination() throws Exception {
        Path archive = temporaryDirectory.resolve("invalid.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("nested/../SKILL.md"));
            output.write(skillMarkdown().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertThrows(IllegalArgumentException.class,
                () -> SkillPackageSupport.extract(archive, temporaryDirectory.resolve("extracted")));
    }

    private String skillMarkdown() {
        return "---\nname: sample-skill\ndescription: sample\n---\n\n# Sample\n";
    }
}
