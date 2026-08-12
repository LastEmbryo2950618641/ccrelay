package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogDigestResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogEntry;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogResponse;
import com.webank.wedatasphere.wdsavs.aiagent.skill.SkillPackageSupport;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class RemoteSkillSyncCoordinator implements AutoCloseable {

    private final RemoteCcRelayProperties properties;
    private final RestTemplate restTemplate;
    private final RelaySkillMetadataStore metadataStore;
    private final Path skillDirectory;
    private final Path stagingDirectory;
    private final ExecutorService executor;
    private final boolean enabled;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile String lastCatalogSha256;

    RemoteSkillSyncCoordinator(RemoteCcRelayProperties properties, RestTemplate restTemplate) {
        this(properties, restTemplate, null);
    }

    RemoteSkillSyncCoordinator(RemoteCcRelayProperties properties,
                               RestTemplate restTemplate,
                               RelaySkillMetadataStore metadataStore) {
        this.properties = properties == null ? new RemoteCcRelayProperties() : properties;
        this.restTemplate = restTemplate;
        this.skillDirectory = resolveSkillDirectory(this.properties);
        Path parent = skillDirectory.getParent();
        this.stagingDirectory = (parent == null ? Path.of(".") : parent)
                .resolve(".ccrelay-skill-staging").toAbsolutePath().normalize();
        this.enabled = restTemplate != null && centerBaseUrl() != null;
        this.metadataStore = enabled
                ? (metadataStore == null ? new RelaySkillMetadataStore(this.properties) : metadataStore)
                : metadataStore;
        this.executor = enabled ? Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ccrelay-skill-sync-" + this.properties.getPort());
                thread.setDaemon(true);
                return thread;
            }) : null;
        if (enabled) {
            initializeDirectories();
        }
    }

    void trigger() {
        if (!enabled || !scheduled.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                synchronize();
            } catch (Exception e) {
                System.err.println("CC Relay Skill synchronization failed: " + rootMessage(e));
            } finally {
                scheduled.set(false);
            }
        });
    }

    List<Map<String, Object>> summary() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (metadataStore == null) {
            return result;
        }
        for (RelaySkillMetadata metadata : metadataStore.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillId", metadata.getSkillId());
            item.put("centerSha256", metadata.getCenterSha256());
            item.put("installedSha256", metadata.getInstalledSha256());
            item.put("status", metadata.getStatus());
            item.put("lastError", metadata.getLastError());
            item.put("updatedAt", metadata.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void synchronize() throws Exception {
        String baseUrl = centerBaseUrl();
        SkillCatalogDigestResponse digest = restTemplate.getForObject(
                baseUrl + "/api/skill/catalog/digest", SkillCatalogDigestResponse.class);
        if (digest == null || isBlank(digest.getCatalogSha256())
                || digest.getCatalogSha256().equals(lastCatalogSha256)) {
            return;
        }
        SkillCatalogResponse catalog = restTemplate.getForObject(
                baseUrl + "/api/skill/catalog", SkillCatalogResponse.class);
        if (catalog == null || !catalog.isComplete() || isBlank(catalog.getCatalogSha256())) {
            return;
        }
        boolean failed = false;
        Set<String> centerSkillIds = new HashSet<>();
        for (SkillCatalogEntry entry : catalog.getSkills()) {
            if (entry == null || isBlank(entry.getSkillId())) {
                continue;
            }
            centerSkillIds.add(entry.getSkillId());
            try {
                if ("ACTIVE".equalsIgnoreCase(entry.getStatus())) {
                    installIfRequired(baseUrl, entry);
                } else {
                    invalidate(entry.getSkillId(), entry.getSha256(), "CENTER_INVALID");
                }
            } catch (Exception e) {
                failed = true;
                recordFailure(entry, e);
            }
        }
        for (RelaySkillMetadata local : metadataStore.list()) {
            if (!centerSkillIds.contains(local.getSkillId())) {
                try {
                    invalidate(local.getSkillId(), local.getCenterSha256(), "CENTER_ABSENT");
                } catch (Exception e) {
                    failed = true;
                }
            }
        }
        if (!failed) {
            lastCatalogSha256 = catalog.getCatalogSha256();
        }
    }

    private void installIfRequired(String baseUrl, SkillCatalogEntry entry) throws Exception {
        Path target = skillDirectory.resolve(entry.getSkillId()).normalize();
        ensureInsideSkillDirectory(target);
        RelaySkillMetadata local = metadataStore.find(entry.getSkillId());
        if (isInstalledAndValid(local, entry, target)) {
            return;
        }
        RelaySkillMetadata installing = local == null ? new RelaySkillMetadata() : local;
        installing.setSkillId(entry.getSkillId());
        installing.setCenterSha256(entry.getSha256());
        installing.setStatus("INSTALLING");
        installing.setInstallPath(target.toString());
        installing.setLastError(null);
        installing.setUpdatedAt(System.currentTimeMillis());
        metadataStore.save(installing);
        SkillPackageSupport.deleteRecursively(target);

        Path operation = stagingDirectory.resolve(entry.getSkillId() + "-" + UUID.randomUUID());
        try {
            Files.createDirectories(operation);
            byte[] artifact = restTemplate.getForObject(baseUrl + entry.getArtifactUrl(), byte[].class);
            if (artifact == null || artifact.length == 0) {
                throw new IllegalStateException("Center returned an empty Skill artifact");
            }
            Path archive = operation.resolve("skill.zip");
            Files.write(archive, artifact);
            Path extractedRoot = SkillPackageSupport.extract(archive, operation.resolve("extracted"));
            String extractedSkillId = SkillPackageSupport.readSkillId(extractedRoot);
            if (!entry.getSkillId().equals(extractedSkillId)) {
                throw new IllegalArgumentException("Skill identity mismatch: " + extractedSkillId);
            }
            String actualSha256 = SkillPackageSupport.sha256(extractedRoot);
            if (!entry.getSha256().equals(actualSha256)) {
                throw new IllegalArgumentException("Skill SHA-256 mismatch for " + entry.getSkillId());
            }
            move(extractedRoot, target);
            RelaySkillMetadata installed = new RelaySkillMetadata();
            installed.setSkillId(entry.getSkillId());
            installed.setCenterSha256(entry.getSha256());
            installed.setInstalledSha256(actualSha256);
            installed.setStatus("INSTALLED");
            installed.setInstallPath(target.toString());
            installed.setUpdatedAt(System.currentTimeMillis());
            metadataStore.save(installed);
        } finally {
            SkillPackageSupport.deleteRecursively(operation);
        }
    }

    private boolean isInstalledAndValid(RelaySkillMetadata local, SkillCatalogEntry entry, Path target) throws Exception {
        if (local == null || !"INSTALLED".equals(local.getStatus())
                || !entry.getSha256().equals(local.getInstalledSha256()) || !Files.isDirectory(target)) {
            return false;
        }
        return entry.getSha256().equals(SkillPackageSupport.sha256(target));
    }

    private void invalidate(String skillId, String centerSha256, String reason) throws Exception {
        Path target = skillDirectory.resolve(skillId).normalize();
        ensureInsideSkillDirectory(target);
        SkillPackageSupport.deleteRecursively(target);
        RelaySkillMetadata metadata = metadataStore.find(skillId);
        if (metadata == null) {
            metadata = new RelaySkillMetadata();
            metadata.setSkillId(skillId);
        }
        metadata.setCenterSha256(centerSha256);
        metadata.setInstalledSha256(null);
        metadata.setStatus("INVALID");
        metadata.setInstallPath(target.toString());
        metadata.setLastError(reason);
        metadata.setUpdatedAt(System.currentTimeMillis());
        metadataStore.save(metadata);
    }

    private void recordFailure(SkillCatalogEntry entry, Exception failure) {
        RelaySkillMetadata metadata = metadataStore.find(entry.getSkillId());
        if (metadata == null) {
            metadata = new RelaySkillMetadata();
            metadata.setSkillId(entry.getSkillId());
        }
        metadata.setCenterSha256(entry.getSha256());
        metadata.setInstalledSha256(null);
        metadata.setStatus("INSTALLING");
        metadata.setInstallPath(skillDirectory.resolve(entry.getSkillId()).toString());
        metadata.setLastError(rootMessage(failure));
        metadata.setUpdatedAt(System.currentTimeMillis());
        metadataStore.save(metadata);
    }

    private void initializeDirectories() {
        try {
            Files.createDirectories(skillDirectory);
            Files.createDirectories(stagingDirectory);
            ensureClaudeSkillDirectory();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize Relay Skill directory", e);
        }
    }

    private void ensureClaudeSkillDirectory() throws Exception {
        if (isBlank(properties.getClaudeSettingsFilePath())) {
            return;
        }
        Path settingsPath = Path.of(properties.getClaudeSettingsFilePath()).toAbsolutePath().normalize();
        Path configDirectory = settingsPath.getParent();
        if (configDirectory == null) {
            return;
        }
        Path claudeSkills = configDirectory.resolve("skills");
        if (claudeSkills.equals(skillDirectory)) {
            return;
        }
        Files.createDirectories(configDirectory);
        if (Files.exists(claudeSkills, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (Files.isSameFile(claudeSkills, skillDirectory)) {
                    return;
                }
            } catch (Exception ignored) {
            }
            if (Files.isSymbolicLink(claudeSkills)) {
                Files.delete(claudeSkills);
            } else {
                try (var children = Files.list(claudeSkills)) {
                    if (Files.isDirectory(claudeSkills) && children.findAny().isEmpty()) {
                        Files.delete(claudeSkills);
                    } else {
                        throw new IllegalStateException("Claude Skill directory already exists and is not managed by CC Relay: " + claudeSkills);
                    }
                }
            }
        }
        if (isWindows()) {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    claudeSkills.toString(), skillDirectory.toString()).redirectErrorStream(true).start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("Unable to create Claude Skill directory junction: " + claudeSkills);
            }
        } else {
            Files.createSymbolicLink(claudeSkills, skillDirectory);
        }
    }

    private Path resolveSkillDirectory(RemoteCcRelayProperties properties) {
        String configured = properties.getSkillDirectory();
        Path path = isBlank(configured) ? Path.of("skills") : Path.of(configured);
        if (!path.isAbsolute()) {
            Path root = !isBlank(properties.getWorkingDirectory())
                    ? Path.of(properties.getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
            path = root.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private String centerBaseUrl() {
        String endpoint = !isBlank(properties.getCenterHeartbeatEndpoint())
                ? properties.getCenterHeartbeatEndpoint() : properties.getCenterRegisterEndpoint();
        if (isBlank(endpoint)) {
            return null;
        }
        int marker = endpoint.indexOf("/api/skill/");
        return marker < 0 ? endpoint.replaceAll("/+$", "") : endpoint.substring(0, marker);
    }

    private void ensureInsideSkillDirectory(Path path) {
        if (!path.startsWith(skillDirectory) || path.equals(skillDirectory)) {
            throw new IllegalArgumentException("Skill path escapes configured directory: " + path);
        }
    }

    private void move(Path source, Path target) throws Exception {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
