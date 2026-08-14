package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiPromptEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogDigestResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogDigestSupport;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogEntry;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptInstallResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptType;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiPromptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PromptCatalogServiceImpl implements PromptCatalogService {

    static final int MAX_CONTENT_BYTES = 1024 * 1024;
    private static final Pattern PROMPT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Comparator<AiPromptEntity> CATALOG_ORDER = Comparator
            .comparing((AiPromptEntity entity) -> PromptType.valueOf(entity.getType()))
            .thenComparing(AiPromptEntity::getPromptOrder)
            .thenComparing(AiPromptEntity::getPromptId);

    private final AiPromptRepository repository;
    private final Path contentDirectory;

    @Autowired
    public PromptCatalogServiceImpl(AiPromptRepository repository,
                                    @Value("${wdsavs.ai.prompt.directory:./prompts}") String contentDirectory) {
        this(repository, Path.of(contentDirectory));
    }

    PromptCatalogServiceImpl(AiPromptRepository repository, Path contentDirectory) {
        this.repository = repository;
        this.contentDirectory = contentDirectory.toAbsolutePath().normalize();
    }

    @Override
    public synchronized PromptInstallResponse install(String promptId, PromptType type, int order, byte[] content) {
        validate(promptId, type, order, content);
        String sha256 = sha256(content);
        Path promptDirectory = contentDirectory.resolve(promptId);
        Path target = promptDirectory.resolve(sha256 + ".md");
        Path staging = promptDirectory.resolve("." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(promptDirectory);
            Files.write(staging, content);
            move(staging, target);
            long now = System.currentTimeMillis();
            AiPromptEntity entity = repository.findByPromptId(promptId).orElseGet(AiPromptEntity::new);
            entity.setPromptId(promptId);
            entity.setType(type.name());
            entity.setPromptOrder(order);
            entity.setSha256(sha256);
            entity.setStatus("ACTIVE");
            entity.setContentPath(target.toString());
            entity.setContentSize((long) content.length);
            entity.setCreatedAt(entity.getCreatedAt() == null ? now : entity.getCreatedAt());
            entity.setUpdatedAt(now);
            repository.save(entity);
            return response(entity);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to install Prompt content", e);
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public PromptCatalogDigestResponse digest() {
        return new PromptCatalogDigestResponse(catalogSha256(entities()));
    }

    @Override
    public PromptCatalogResponse catalog() {
        List<AiPromptEntity> entities = entities();
        PromptCatalogResponse response = new PromptCatalogResponse();
        response.setCatalogSha256(catalogSha256(entities));
        response.setPrompts(entities.stream().map(this::entry).toList());
        return response;
    }

    @Override
    public AiPromptEntity getActive(String promptId) {
        AiPromptEntity entity = repository.findByPromptId(promptId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId));
        if (!"ACTIVE".equals(entity.getStatus())) {
            throw new IllegalArgumentException("Prompt is not active: " + promptId);
        }
        return entity;
    }

    @Override
    public Path content(String promptId) {
        Path path = Path.of(getActive(promptId).getContentPath()).toAbsolutePath().normalize();
        if (!path.startsWith(contentDirectory) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Prompt content is unavailable: " + promptId);
        }
        return path;
    }

    @Override
    public synchronized PromptInstallResponse invalidate(String promptId) {
        AiPromptEntity entity = repository.findByPromptId(promptId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId));
        entity.setStatus("INVALID");
        entity.setUpdatedAt(System.currentTimeMillis());
        repository.save(entity);
        return response(entity);
    }

    private void validate(String promptId, PromptType type, int order, byte[] content) {
        if (promptId == null || !PROMPT_ID.matcher(promptId).matches()) {
            throw new IllegalArgumentException("Invalid Prompt ID: " + promptId);
        }
        if (type == null) {
            throw new IllegalArgumentException("Prompt type is required");
        }
        if (order < 0) {
            throw new IllegalArgumentException("Prompt order must be non-negative");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Prompt content is required");
        }
        if (content.length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("Prompt content exceeds the maximum size");
        }
        for (byte value : content) {
            if (value == 0) {
                throw new IllegalArgumentException("Prompt content must not contain NUL characters");
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (Exception e) {
            throw new IllegalArgumentException("Prompt content must be valid UTF-8", e);
        }
    }

    private List<AiPromptEntity> entities() {
        return repository.findAll().stream().sorted(CATALOG_ORDER).toList();
    }

    private PromptCatalogEntry entry(AiPromptEntity entity) {
        PromptCatalogEntry entry = new PromptCatalogEntry();
        entry.setPromptId(entity.getPromptId());
        entry.setType(PromptType.valueOf(entity.getType()));
        entry.setOrder(entity.getPromptOrder());
        entry.setSha256(entity.getSha256());
        entry.setStatus(entity.getStatus());
        entry.setContentSize(entity.getContentSize());
        entry.setUpdatedAt(entity.getUpdatedAt());
        if ("ACTIVE".equals(entity.getStatus())) {
            entry.setContentUrl("/api/prompt/catalog/" + entity.getPromptId() + "/content");
        }
        return entry;
    }

    private PromptInstallResponse response(AiPromptEntity entity) {
        return new PromptInstallResponse(
                entity.getPromptId(), PromptType.valueOf(entity.getType()), entity.getPromptOrder(),
                entity.getSha256(), entity.getStatus(), entity.getContentSize());
    }

    private String catalogSha256(List<AiPromptEntity> entities) {
        return PromptCatalogDigestSupport.calculate(entities.stream().map(this::entry).toList());
    }

    private String sha256(byte[] content) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate Prompt SHA-256", e);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private void move(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
