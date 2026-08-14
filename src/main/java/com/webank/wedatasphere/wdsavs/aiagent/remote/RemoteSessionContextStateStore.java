package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class RemoteSessionContextStateStore {

    private final ObjectMapper objectMapper;
    private final Path statePath;
    private final ConcurrentMap<String, Long> cursors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> modelSessionIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> promptRevisions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> unifiedDigests = new ConcurrentHashMap<>();
    private final java.util.Set<String> startedModelSessions = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> initializedSessions = ConcurrentHashMap.newKeySet();

    RemoteSessionContextStateStore(RemoteCcRelayProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.statePath = resolveStatePath(properties);
        load();
    }

    long cursor(String sessionId) {
        return cursors.getOrDefault(sessionId, 0L);
    }

    String modelSessionId(String sessionId, String localNodeId) {
        return modelSessionIds.computeIfAbsent(sessionId, key -> UUID.nameUUIDFromBytes(
                (value(localNodeId) + "|" + key).getBytes(StandardCharsets.UTF_8)).toString());
    }

    boolean initialized(String sessionId) {
        return initializedSessions.contains(sessionId);
    }

    boolean modelSessionStarted(String sessionId) {
        return startedModelSessions.contains(sessionId);
    }

    boolean modelSessionStarted(String sessionId, String modelSessionId) {
        return startedModelSessions.contains(sessionId)
                && modelSessionId != null && modelSessionId.equals(modelSessionIds.get(sessionId));
    }

    String rotatedModelSessionId(String sessionId, String localNodeId, String unifiedDigest) {
        return UUID.nameUUIDFromBytes((value(localNodeId) + "|" + sessionId + "|UNIFIED|" + value(unifiedDigest))
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    String appliedUnifiedDigest(String sessionId) {
        return unifiedDigests.get(sessionId);
    }

    synchronized void markModelSessionStarted(String sessionId) {
        startedModelSessions.add(sessionId);
        persist();
    }

    synchronized void markModelSessionStarted(String sessionId, String modelSessionId) {
        String previous = modelSessionIds.put(sessionId, modelSessionId);
        if (previous != null && !previous.equals(modelSessionId)) {
            initializedSessions.remove(sessionId);
        }
        startedModelSessions.add(sessionId);
        persist();
    }

    synchronized void markApplied(String sessionId, long cursor) {
        cursors.merge(sessionId, cursor, Math::max);
        startedModelSessions.add(sessionId);
        initializedSessions.add(sessionId);
        persist();
    }

    synchronized void markApplied(String sessionId,
                                  long cursor,
                                  String modelSessionId,
                                  String promptRevision,
                                  String unifiedDigest) {
        String previous = modelSessionIds.put(sessionId, modelSessionId);
        if (previous != null && !previous.equals(modelSessionId)) {
            cursors.put(sessionId, cursor);
        } else {
            cursors.merge(sessionId, cursor, Math::max);
        }
        if (!isBlank(promptRevision)) {
            promptRevisions.put(sessionId, promptRevision);
        }
        if (!isBlank(unifiedDigest)) {
            unifiedDigests.put(sessionId, unifiedDigest);
        }
        startedModelSessions.add(sessionId);
        initializedSessions.add(sessionId);
        persist();
    }

    private void load() {
        if (statePath == null || !Files.exists(statePath)) {
            return;
        }
        try {
            Map<?, ?> state = objectMapper.readValue(Files.readString(statePath, StandardCharsets.UTF_8), Map.class);
            for (Map.Entry<?, ?> entry : state.entrySet()) {
                String sessionId = String.valueOf(entry.getKey());
                Map<String, Object> sessionState = mapValue(entry.getValue());
                long cursor = longValue(sessionState.get("cursor"));
                String modelSessionId = stringValue(sessionState.get("modelSessionId"));
                String promptRevision = stringValue(sessionState.get("appliedPromptRevision"));
                String unifiedDigest = stringValue(sessionState.get("appliedUnifiedDigest"));
                if (cursor > 0L) {
                    cursors.put(sessionId, cursor);
                }
                if (!isBlank(modelSessionId)) {
                    modelSessionIds.put(sessionId, modelSessionId);
                }
                if (!isBlank(promptRevision)) {
                    promptRevisions.put(sessionId, promptRevision);
                }
                if (!isBlank(unifiedDigest)) {
                    unifiedDigests.put(sessionId, unifiedDigest);
                }
                boolean initialized = Boolean.parseBoolean(String.valueOf(sessionState.get("initialized")));
                if (initialized || Boolean.parseBoolean(String.valueOf(sessionState.get("modelSessionStarted")))) {
                    startedModelSessions.add(sessionId);
                }
                if (initialized) {
                    initializedSessions.add(sessionId);
                }
            }
        } catch (Exception ignored) {
            cursors.clear();
            modelSessionIds.clear();
            promptRevisions.clear();
            unifiedDigests.clear();
            startedModelSessions.clear();
            initializedSessions.clear();
        }
    }

    private void persist() {
        if (statePath == null) {
            return;
        }
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : modelSessionIds.entrySet()) {
                Map<String, Object> sessionState = new LinkedHashMap<>();
                sessionState.put("cursor", cursors.getOrDefault(entry.getKey(), 0L));
                sessionState.put("modelSessionId", entry.getValue());
                sessionState.put("modelSessionStarted", startedModelSessions.contains(entry.getKey()));
                sessionState.put("initialized", initializedSessions.contains(entry.getKey()));
                sessionState.put("appliedPromptRevision", promptRevisions.get(entry.getKey()));
                sessionState.put("appliedUnifiedDigest", unifiedDigests.get(entry.getKey()));
                state.put(entry.getKey(), sessionState);
            }
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporaryPath = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            Files.writeString(temporaryPath, objectMapper.writeValueAsString(state), StandardCharsets.UTF_8);
            moveIntoPlace(temporaryPath);
        } catch (Exception ignored) {
        }
    }

    private void moveIntoPlace(Path temporaryPath) throws Exception {
        try {
            Files.move(temporaryPath, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryPath, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path resolveStatePath(RemoteCcRelayProperties properties) {
        if (properties == null) {
            return null;
        }
        if (!isBlank(properties.getContextStateFilePath())) {
            return Path.of(properties.getContextStateFilePath());
        }
        if (!isBlank(properties.getNodeIdFilePath())) {
            Path nodeIdPath = Path.of(properties.getNodeIdFilePath());
            Path parent = nodeIdPath.getParent();
            String nodeKey = sanitize(firstNonBlank(properties.getNodeId(), String.valueOf(properties.getPort())));
            return parent == null
                    ? Path.of(".ccrelay-context-sessions-" + nodeKey + ".json")
                    : parent.resolve("context-sessions-" + nodeKey + ".json");
        }
        if (!isBlank(properties.getWorkingDirectory())) {
            return Path.of(properties.getWorkingDirectory()).resolve(".ccrelay-context-sessions.json");
        }
        return null;
    }

    private Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return result;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String firstNonBlank(String first, String fallback) {
        return isBlank(first) ? fallback : first.trim();
    }

    private String sanitize(String value) {
        return value == null ? "default" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
