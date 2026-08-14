package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogDigestResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogDigestSupport;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogEntry;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogResponse;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class RemotePromptSyncCoordinator implements AutoCloseable {

    private static final Comparator<RelayPromptMetadata> ORDER = Comparator
            .comparing(RelayPromptMetadata::getType)
            .thenComparing(RelayPromptMetadata::getOrder)
            .thenComparing(RelayPromptMetadata::getPromptId);

    private final RemoteCcRelayProperties properties;
    private final RestTemplate restTemplate;
    private final Path promptDirectory;
    private final Path stagingDirectory;
    private final RelayPromptMetadataStore metadataStore;
    private final boolean enabled;
    private final ExecutorService executor;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile String lastSynchronizationError;

    RemotePromptSyncCoordinator(RemoteCcRelayProperties properties, RestTemplate restTemplate) {
        this(properties, restTemplate, null);
    }

    RemotePromptSyncCoordinator(RemoteCcRelayProperties properties,
                                RestTemplate restTemplate,
                                RelayPromptMetadataStore metadataStore) {
        this.properties = properties == null ? new RemoteCcRelayProperties() : properties;
        this.restTemplate = restTemplate;
        this.promptDirectory = resolvePromptDirectory(this.properties);
        Path parent = promptDirectory.getParent();
        this.stagingDirectory = (parent == null ? Path.of(".") : parent)
                .resolve(".ccrelay-prompt-staging").toAbsolutePath().normalize();
        this.enabled = restTemplate != null && centerBaseUrl() != null;
        this.metadataStore = enabled
                ? (metadataStore == null ? new RelayPromptMetadataStore(this.properties) : metadataStore)
                : metadataStore;
        this.executor = enabled ? Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ccrelay-prompt-sync-" + this.properties.getPort());
            thread.setDaemon(true);
            return thread;
        }) : null;
        if (enabled) {
            try {
                Files.createDirectories(promptDirectory);
                Files.createDirectories(stagingDirectory);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to initialize Relay Prompt directory", e);
            }
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
                System.err.println("CC Relay Prompt synchronization failed: " + rootMessage(e));
            } finally {
                scheduled.set(false);
            }
        });
    }

    void synchronize() throws Exception {
        String baseUrl = centerBaseUrl();
        PromptCatalogDigestResponse digest = restTemplate.getForObject(
                baseUrl + "/api/prompt/catalog/digest", PromptCatalogDigestResponse.class);
        RelayPromptCatalogState current = metadataStore.load();
        if (digest == null || blank(digest.getCatalogSha256())
                || digest.getCatalogSha256().equals(current.getCatalogSha256())) {
            return;
        }
        PromptCatalogResponse catalog = restTemplate.getForObject(
                baseUrl + "/api/prompt/catalog", PromptCatalogResponse.class);
        if (catalog == null || !catalog.isComplete() || blank(catalog.getCatalogSha256())) {
            return;
        }
        String calculatedCatalogSha256 = PromptCatalogDigestSupport.calculate(catalog.getPrompts());
        if (!digest.getCatalogSha256().equals(catalog.getCatalogSha256())
                || !calculatedCatalogSha256.equals(catalog.getCatalogSha256())) {
            lastSynchronizationError = "Prompt catalog SHA-256 mismatch";
            return;
        }

        Map<String, RelayPromptMetadata> previous = new HashMap<>();
        for (RelayPromptMetadata metadata : current.getPrompts()) {
            previous.put(metadata.getPromptId(), metadata);
        }
        List<RelayPromptMetadata> next = new ArrayList<>();
        Set<String> centerIds = new HashSet<>();
        List<Path> invalidatedPaths = new ArrayList<>();
        for (PromptCatalogEntry entry : catalog.getPrompts()) {
            if (entry == null || blank(entry.getPromptId())) {
                continue;
            }
            centerIds.add(entry.getPromptId());
            RelayPromptMetadata old = previous.get(entry.getPromptId());
            if ("ACTIVE".equalsIgnoreCase(entry.getStatus())) {
                try {
                    next.add(prepareActive(baseUrl, entry, old));
                } catch (Exception e) {
                    lastSynchronizationError = rootMessage(e);
                    return;
                }
            } else {
                next.add(invalid(entry, old, "CENTER_INVALID"));
                addPath(invalidatedPaths, old);
            }
        }
        for (RelayPromptMetadata old : current.getPrompts()) {
            if (!centerIds.contains(old.getPromptId())) {
                next.add(invalid(null, old, "CENTER_ABSENT"));
                addPath(invalidatedPaths, old);
            }
        }
        next.sort(ORDER);
        RelayPromptCatalogState committed = new RelayPromptCatalogState();
        committed.setCatalogSha256(catalog.getCatalogSha256());
        committed.setPrompts(next);
        metadataStore.save(committed);
        lastSynchronizationError = null;
        for (Path path : invalidatedPaths) {
            Files.deleteIfExists(path);
        }
    }

    void requireCurrent() throws Exception {
        if (!enabled) {
            return;
        }
        synchronize();
        if (!blank(lastSynchronizationError)) {
            throw new IllegalStateException("Prompt catalog is not ready: " + lastSynchronizationError);
        }
    }

    RelayPromptCatalogSnapshot snapshot() {
        if (metadataStore == null) {
            return new RelayPromptCatalogSnapshot(null, List.of());
        }
        RelayPromptCatalogState state = metadataStore.load();
        List<RelayPromptContent> prompts = state.getPrompts().stream()
                .filter(metadata -> "INSTALLED".equals(metadata.getStatus()))
                .sorted(ORDER)
                .map(metadata -> new RelayPromptContent(
                        metadata.getPromptId(), metadata.getType(), metadata.getOrder(),
                        metadata.getInstalledSha256(), Path.of(metadata.getContentPath())))
                .toList();
        return new RelayPromptCatalogSnapshot(state.getCatalogSha256(), prompts);
    }

    List<Map<String, Object>> summary() {
        if (metadataStore == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (RelayPromptMetadata metadata : metadataStore.load().getPrompts()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("promptId", metadata.getPromptId());
            item.put("type", metadata.getType());
            item.put("order", metadata.getOrder());
            item.put("centerSha256", metadata.getCenterSha256());
            item.put("installedSha256", metadata.getInstalledSha256());
            item.put("status", metadata.getStatus());
            item.put("lastError", metadata.getLastError());
            item.put("synchronizationError", lastSynchronizationError);
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

    private RelayPromptMetadata prepareActive(String baseUrl,
                                              PromptCatalogEntry entry,
                                              RelayPromptMetadata old) throws Exception {
        if (old != null && "INSTALLED".equals(old.getStatus())
                && entry.getSha256().equals(old.getInstalledSha256())) {
            Path existing = Path.of(old.getContentPath()).toAbsolutePath().normalize();
            if (existing.startsWith(promptDirectory) && Files.isRegularFile(existing)
                    && entry.getSha256().equals(sha256(Files.readAllBytes(existing)))) {
                return installed(entry, existing);
            }
        }
        if (blank(entry.getContentUrl())) {
            throw new IllegalArgumentException("Prompt content URL is missing: " + entry.getPromptId());
        }
        byte[] content = restTemplate.getForObject(baseUrl + entry.getContentUrl(), byte[].class);
        if (content == null || content.length == 0) {
            throw new IllegalStateException("Center returned empty Prompt content: " + entry.getPromptId());
        }
        String actualSha256 = sha256(content);
        if (!entry.getSha256().equals(actualSha256)) {
            throw new IllegalArgumentException("Prompt SHA-256 mismatch for " + entry.getPromptId());
        }
        Path targetDirectory = promptDirectory.resolve(entry.getPromptId()).normalize();
        ensureInsidePromptDirectory(targetDirectory);
        Path target = targetDirectory.resolve(actualSha256 + ".md");
        Path staging = stagingDirectory.resolve(entry.getPromptId() + "-" + UUID.randomUUID() + ".tmp");
        Files.write(staging, content);
        Files.createDirectories(targetDirectory);
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
        return installed(entry, target);
    }

    private RelayPromptMetadata installed(PromptCatalogEntry entry, Path path) {
        RelayPromptMetadata metadata = base(entry);
        metadata.setInstalledSha256(entry.getSha256());
        metadata.setStatus("INSTALLED");
        metadata.setContentPath(path.toString());
        return metadata;
    }

    private RelayPromptMetadata invalid(PromptCatalogEntry entry, RelayPromptMetadata old, String reason) {
        RelayPromptMetadata metadata = entry == null ? new RelayPromptMetadata() : base(entry);
        if (entry == null && old != null) {
            metadata.setPromptId(old.getPromptId());
            metadata.setType(old.getType());
            metadata.setOrder(old.getOrder());
            metadata.setCenterSha256(old.getCenterSha256());
        }
        metadata.setStatus("INVALID");
        metadata.setContentPath(old == null ? null : old.getContentPath());
        metadata.setLastError(reason);
        metadata.setUpdatedAt(System.currentTimeMillis());
        return metadata;
    }

    private RelayPromptMetadata base(PromptCatalogEntry entry) {
        RelayPromptMetadata metadata = new RelayPromptMetadata();
        metadata.setPromptId(entry.getPromptId());
        metadata.setType(entry.getType().name());
        metadata.setOrder(entry.getOrder());
        metadata.setCenterSha256(entry.getSha256());
        metadata.setUpdatedAt(System.currentTimeMillis());
        return metadata;
    }

    private void addPath(List<Path> paths, RelayPromptMetadata metadata) {
        if (metadata != null && !blank(metadata.getContentPath())) {
            Path path = Path.of(metadata.getContentPath()).toAbsolutePath().normalize();
            if (path.startsWith(promptDirectory)) {
                paths.add(path);
            }
        }
    }

    private String sha256(byte[] content) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(content)) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private Path resolvePromptDirectory(RemoteCcRelayProperties properties) {
        String configured = properties.getPromptDirectory();
        Path path = blank(configured) ? Path.of("prompts") : Path.of(configured);
        if (!path.isAbsolute()) {
            Path root = !blank(properties.getWorkingDirectory())
                    ? Path.of(properties.getWorkingDirectory()) : Path.of(System.getProperty("user.dir"));
            path = root.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private String centerBaseUrl() {
        String endpoint = !blank(properties.getCenterHeartbeatEndpoint())
                ? properties.getCenterHeartbeatEndpoint() : properties.getCenterRegisterEndpoint();
        if (blank(endpoint)) {
            return null;
        }
        int marker = endpoint.indexOf("/api/skill/");
        return marker < 0 ? endpoint.replaceAll("/+$", "") : endpoint.substring(0, marker);
    }

    private void ensureInsidePromptDirectory(Path path) {
        if (!path.startsWith(promptDirectory) || path.equals(promptDirectory)) {
            throw new IllegalArgumentException("Prompt path escapes configured directory: " + path);
        }
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
