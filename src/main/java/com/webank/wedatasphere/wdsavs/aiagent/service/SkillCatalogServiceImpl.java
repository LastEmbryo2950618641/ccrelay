package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSkillEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogDigestResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogEntry;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillInstallResponse;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSkillRepository;
import com.webank.wedatasphere.wdsavs.aiagent.skill.SkillPackageSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@Service
public class SkillCatalogServiceImpl implements SkillCatalogService {

    private final AiSkillRepository repository;
    private final Path skillDirectory;
    private final Path artifactDirectory;

    @Autowired
    public SkillCatalogServiceImpl(AiSkillRepository repository,
                                   @Value("${wdsavs.ai.skill.directory:./skills}") String skillDirectory) {
        this(repository, Path.of(skillDirectory));
    }

    SkillCatalogServiceImpl(AiSkillRepository repository, Path skillDirectory) {
        this.repository = repository;
        this.skillDirectory = skillDirectory.toAbsolutePath().normalize();
        Path parent = this.skillDirectory.getParent();
        this.artifactDirectory = (parent == null ? Path.of(".") : parent)
                .resolve(".ccrelay-skill-artifacts").toAbsolutePath().normalize();
    }

    @Override
    public synchronized SkillInstallResponse install(byte[] artifact) {
        if (artifact == null || artifact.length == 0) {
            throw new IllegalArgumentException("Skill artifact is required");
        }
        if (artifact.length > SkillPackageSupport.MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException("Skill artifact exceeds the maximum package size");
        }
        Path operationDirectory = artifactDirectory.resolve(".install-" + UUID.randomUUID());
        Path uploadedArchive = operationDirectory.resolve("skill.zip");
        try {
            Files.createDirectories(operationDirectory);
            Files.write(uploadedArchive, artifact);
            Path extractedRoot = SkillPackageSupport.extract(uploadedArchive, operationDirectory.resolve("extracted"));
            String skillId = SkillPackageSupport.readSkillId(extractedRoot);
            String sha256 = SkillPackageSupport.sha256(extractedRoot);
            Path targetDirectory = artifactDirectory.resolve(skillId);
            Files.createDirectories(targetDirectory);
            Path targetArchive = targetDirectory.resolve(sha256 + ".zip");
            if (!Files.exists(targetArchive)) {
                move(uploadedArchive, targetArchive);
            }
            long now = System.currentTimeMillis();
            AiSkillEntity entity = repository.findBySkillId(skillId).orElseGet(AiSkillEntity::new);
            entity.setSkillId(skillId);
            entity.setSha256(sha256);
            entity.setStatus("ACTIVE");
            entity.setArtifactPath(targetArchive.toString());
            entity.setArtifactSize(Files.size(targetArchive));
            entity.setCreatedAt(entity.getCreatedAt() == null ? now : entity.getCreatedAt());
            entity.setUpdatedAt(now);
            repository.save(entity);
            return response(entity);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to install Skill artifact", e);
        } finally {
            try {
                SkillPackageSupport.deleteRecursively(operationDirectory);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public SkillCatalogDigestResponse digest() {
        return new SkillCatalogDigestResponse(catalogSha256(repository.findAllByOrderBySkillIdAsc()));
    }

    @Override
    public SkillCatalogResponse catalog() {
        List<AiSkillEntity> entities = repository.findAllByOrderBySkillIdAsc();
        SkillCatalogResponse response = new SkillCatalogResponse();
        response.setCatalogSha256(catalogSha256(entities));
        response.setSkills(entities.stream().map(this::entry).toList());
        return response;
    }

    @Override
    public AiSkillEntity getActive(String skillId) {
        AiSkillEntity entity = repository.findBySkillId(skillId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
        if (!"ACTIVE".equals(entity.getStatus())) {
            throw new IllegalArgumentException("Skill is not active: " + skillId);
        }
        return entity;
    }

    @Override
    public Path artifact(String skillId) {
        Path path = Path.of(getActive(skillId).getArtifactPath()).toAbsolutePath().normalize();
        if (!path.startsWith(artifactDirectory) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Skill artifact is unavailable: " + skillId);
        }
        return path;
    }

    @Override
    public synchronized SkillInstallResponse invalidate(String skillId) {
        AiSkillEntity entity = repository.findBySkillId(skillId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
        entity.setStatus("INVALID");
        entity.setUpdatedAt(System.currentTimeMillis());
        repository.save(entity);
        return response(entity);
    }

    private SkillCatalogEntry entry(AiSkillEntity entity) {
        SkillCatalogEntry entry = new SkillCatalogEntry();
        entry.setSkillId(entity.getSkillId());
        entry.setSha256(entity.getSha256());
        entry.setStatus(entity.getStatus());
        entry.setArtifactSize(entity.getArtifactSize());
        entry.setUpdatedAt(entity.getUpdatedAt());
        if ("ACTIVE".equals(entity.getStatus())) {
            entry.setArtifactUrl("/api/skill/catalog/" + entity.getSkillId() + "/artifact");
        }
        return entry;
    }

    private SkillInstallResponse response(AiSkillEntity entity) {
        return new SkillInstallResponse(entity.getSkillId(), entity.getSha256(), entity.getStatus(), entity.getArtifactSize());
    }

    private String catalogSha256(List<AiSkillEntity> entities) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AiSkillEntity entity : entities) {
                byte[] bytes = (entity.getSkillId() + "\n" + entity.getSha256() + "\n" + entity.getStatus() + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate Skill catalog SHA-256", e);
        }
    }

    private void move(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
