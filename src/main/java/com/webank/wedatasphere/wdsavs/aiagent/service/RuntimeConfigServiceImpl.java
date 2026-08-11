package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRuntimeConfigEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiAuditLogEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AuditEventType;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigHistoryView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigReloadResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiAuditLogRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiRuntimeConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class RuntimeConfigServiceImpl implements RuntimeConfigService {

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 200;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiRuntimeConfigRepository configRepository;
    private final AiAuditLogRepository auditLogRepository;
    private final AiAuditService auditService;
    private final Map<String, RuntimeConfigDefinition> definitions = buildDefinitions();

    public RuntimeConfigServiceImpl(AiRuntimeConfigRepository configRepository,
                                    AiAuditLogRepository auditLogRepository,
                                    AiAuditService auditService) {
        this.configRepository = configRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
    }

    @PostConstruct
    public void initializeDefaults() {
        for (RuntimeConfigDefinition definition : definitions.values()) {
            ensureDefinitionVisibility(definition);
        }
    }

    @Override
    public String getString(String key, String defaultValue) {
        String value = rawValue(key);
        return isBlank(value) ? defaultValue : value;
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = rawValue(key);
        return isBlank(value) ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    @Override
    public long getLong(String key, long defaultValue) {
        String value = rawValue(key);
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String value = rawValue(key);
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    @Override
    public List<String> getList(String key, List<String> defaultValue) {
        String value = rawValue(key);
        if (isBlank(value)) {
            return defaultValue == null ? List.of() : new ArrayList<>(defaultValue);
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                List<String> parsed = OBJECT_MAPPER.readValue(trimmed, new TypeReference<List<String>>() {});
                return normalizeList(parsed);
            } catch (Exception ignored) {
                return splitList(trimmed);
            }
        }
        return splitList(trimmed);
    }

    @Override
    public RuntimeConfigView getConfig(String key) {
        RuntimeConfigDefinition definition = definition(key);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown runtime config key: " + key);
        }
        return toView(findEntity(key).orElseGet(() -> defaultEntity(definition)));
    }

    @Override
    public List<RuntimeConfigView> listConfigs(String prefix) {
        String normalizedPrefix = isBlank(prefix) ? "" : prefix.trim();
        Map<String, RuntimeConfigView> views = new TreeMap<>();
        for (RuntimeConfigDefinition definition : definitions.values()) {
            if (!normalizedPrefix.isEmpty() && !definition.key.startsWith(normalizedPrefix)) {
                continue;
            }
            views.put(definition.key, toView(findEntity(definition.key).orElseGet(() -> defaultEntity(definition))));
        }
        for (AiRuntimeConfigEntity entity : configRepository.findByOrderByConfigKeyAsc()) {
            if (entity == null || isBlank(entity.getConfigKey())) {
                continue;
            }
            if (!normalizedPrefix.isEmpty() && !entity.getConfigKey().startsWith(normalizedPrefix)) {
                continue;
            }
            views.putIfAbsent(entity.getConfigKey(), toView(entity));
        }
        return new ArrayList<>(views.values());
    }

    @Override
    public RuntimeConfigView setConfig(String key, String value, String operatorId, String comment) {
        RuntimeConfigDefinition definition = requireDefinition(key);
        if (definition.secret) {
            throw new IllegalArgumentException("Secret config key requires config secret set");
        }
        return upsert(definition, value, operatorId, comment, false);
    }

    @Override
    public RuntimeConfigView setSecretConfig(String key, String value, String operatorId, String comment) {
        RuntimeConfigDefinition definition = requireDefinition(key);
        if (!definition.secret) {
            throw new IllegalArgumentException("Config key is not a secret");
        }
        return upsert(definition, value, operatorId, comment, true);
    }

    @Override
    public RuntimeConfigView unsetConfig(String key, String operatorId, String comment) {
        RuntimeConfigDefinition definition = requireDefinition(key);
        if (definition.secret) {
            throw new IllegalArgumentException("Secret config key requires a dedicated secret command family");
        }
        Optional<AiRuntimeConfigEntity> existing = findEntity(key);
        if (existing.isEmpty()) {
            return getConfig(key);
        }
        AiRuntimeConfigEntity before = existing.get();
        configRepository.delete(before);
        configRepository.flush();
        recordAudit(AuditEventType.CONFIG_UNSET, definition, before, null, operatorId, comment, "UNSET");
        return toView(defaultEntity(definition));
    }

    @Override
    public RuntimeConfigReloadResponse reload(String operatorId) {
        RuntimeConfigReloadResponse response = new RuntimeConfigReloadResponse();
        response.setReloaded(true);
        response.setItemCount((int) configRepository.count());
        response.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        auditService.record(null, null, null, null, AuditEventType.CONFIG_RELOADED, "RELOADED",
                buildAuditDetail("*", null, null, null, null, operatorId, "RELOAD"), "SYSTEM", operatorId);
        return response;
    }

    @Override
    public List<RuntimeConfigHistoryView> history(String key, int limit) {
        int effectiveLimit = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        List<AiAuditLogEntity> records = new ArrayList<>();
        records.addAll(auditLogRepository.findByEventTypeOrderByCreatedTimeDesc(AuditEventType.CONFIG_UPDATED.name()));
        records.addAll(auditLogRepository.findByEventTypeOrderByCreatedTimeDesc(AuditEventType.CONFIG_UNSET.name()));
        records.addAll(auditLogRepository.findByEventTypeOrderByCreatedTimeDesc(AuditEventType.CONFIG_RELOADED.name()));
        return records.stream()
                .filter(record -> matchesKey(record, key))
                .sorted(Comparator.comparingLong((AiAuditLogEntity record) -> parseLong(record.getCreatedTime())).reversed())
                .limit(effectiveLimit)
                .map(this::toHistoryView)
                .collect(Collectors.toList());
    }

    @Override
    public boolean isSecret(String key) {
        RuntimeConfigDefinition definition = definition(key);
        return definition != null && definition.secret;
    }

    @Override
    public boolean isDynamic(String key) {
        RuntimeConfigDefinition definition = definition(key);
        return definition != null && definition.dynamic;
    }

    @Override
    public boolean isRestartRequired(String key) {
        RuntimeConfigDefinition definition = definition(key);
        return definition != null && definition.restartRequired;
    }

    private RuntimeConfigView upsert(RuntimeConfigDefinition definition, String value, String operatorId, String comment, boolean secretCommand) {
        if (definition == null) {
            throw new IllegalArgumentException("Unknown runtime config key");
        }
        if (definition.secret && !secretCommand) {
            throw new IllegalArgumentException("Secret config key requires a dedicated secret command family");
        }
        AiRuntimeConfigEntity before = findEntity(definition.key).orElse(null);
        AiRuntimeConfigEntity entity = before == null ? new AiRuntimeConfigEntity() : before;
        String normalizedValue = value == null ? null : value.trim();
        entity.setConfigKey(definition.key);
        entity.setConfigValue(normalizedValue);
        entity.setMaskedValue(maskValue(normalizedValue, definition.secret));
        entity.setValueFingerprint(fingerprint(normalizedValue));
        entity.setConfigType(definition.configType);
        entity.setDynamicEnabled(definition.dynamic);
        entity.setRestartRequired(definition.restartRequired);
        entity.setSecret(definition.secret);
        entity.setDescription(definition.description);
        entity.setVersion(before == null || before.getVersion() == null ? 1L : before.getVersion() + 1L);
        String now = String.valueOf(System.currentTimeMillis());
        if (before == null) {
            entity.setCreateTime(now);
        }
        entity.setUpdateTime(now);
        entity.setModifyUser(operatorId);
        if (before == null) {
            entity.setCreateUser(operatorId);
        }
        configRepository.save(entity);
        if (!definition.secret) {
            if (normalizedValue == null) {
                System.clearProperty(definition.key);
            } else {
                System.setProperty(definition.key, normalizedValue);
            }
        }
        recordAudit(AuditEventType.CONFIG_UPDATED, definition, before, entity, operatorId, comment, "UPDATED");
        return toView(entity);
    }

    private void recordAudit(AuditEventType eventType,
                             RuntimeConfigDefinition definition,
                             AiRuntimeConfigEntity before,
                             AiRuntimeConfigEntity after,
                             String operatorId,
                             String comment,
                             String action) {
        Map<String, Object> detail = buildAuditDetail(definition.key, before, after, operatorId, comment, null, action);
        auditService.record(null, null, null, null, eventType, action, detail, "SYSTEM", operatorId);
    }

    private Map<String, Object> buildAuditDetail(String key,
                                                 AiRuntimeConfigEntity before,
                                                 AiRuntimeConfigEntity after,
                                                 String operatorId,
                                                 String comment,
                                                 String updateTime,
                                                 String action) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("key", key);
        detail.put("action", action);
        detail.put("operatorId", operatorId);
        detail.put("comment", comment);
        detail.put("oldValueMasked", before == null ? null : before.getMaskedValue());
        detail.put("newValueMasked", after == null ? null : after.getMaskedValue());
        detail.put("oldFingerprint", before == null ? null : before.getValueFingerprint());
        detail.put("newFingerprint", after == null ? null : after.getValueFingerprint());
        detail.put("oldVersion", before == null ? null : before.getVersion());
        detail.put("newVersion", after == null ? null : after.getVersion());
        detail.put("dynamic", after == null ? null : after.getDynamicEnabled());
        detail.put("restartRequired", after == null ? null : after.getRestartRequired());
        detail.put("secret", after == null ? null : after.getSecret());
        detail.put("updateTime", updateTime == null ? String.valueOf(System.currentTimeMillis()) : updateTime);
        return detail;
    }

    private List<String> splitList(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!isBlank(part)) {
                result.add(part.trim());
            }
        }
        return normalizeList(result);
    }

    private List<String> normalizeList(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            if (!isBlank(value)) {
                normalized.add(value.trim());
            }
        }
        return new ArrayList<>(normalized);
    }

    private RuntimeConfigView toView(AiRuntimeConfigEntity entity) {
        RuntimeConfigView view = new RuntimeConfigView();
        if (entity == null) {
            return view;
        }
        view.setKey(entity.getConfigKey());
        view.setValue(entity.getSecret() == Boolean.TRUE ? null : entity.getConfigValue());
        view.setMaskedValue(entity.getSecret() == Boolean.TRUE ? maskedSecret(entity.getConfigValue()) : entity.getMaskedValue());
        view.setFingerprint(entity.getValueFingerprint());
        view.setConfigType(entity.getConfigType());
        view.setDynamic(Boolean.TRUE.equals(entity.getDynamicEnabled()));
        view.setRestartRequired(Boolean.TRUE.equals(entity.getRestartRequired()));
        view.setSecret(Boolean.TRUE.equals(entity.getSecret()));
        view.setVersion(entity.getVersion());
        view.setDescription(entity.getDescription());
        view.setUpdateTime(entity.getUpdateTime());
        view.setCreateTime(entity.getCreateTime());
        view.setModifyUser(entity.getModifyUser());
        return view;
    }

    private RuntimeConfigHistoryView toHistoryView(AiAuditLogEntity entity) {
        RuntimeConfigHistoryView view = new RuntimeConfigHistoryView();
        view.setEventType(entity.getEventType());
        view.setDecision(entity.getDecision());
        view.setUpdateTime(entity.getCreatedTime());
        view.setDetail(readDetail(entity.getDetailJson()));
        view.setKey(stringValue(view.getDetail().get("key")));
        view.setAction(stringValue(view.getDetail().get("action")));
        view.setOperatorId(stringValue(view.getDetail().get("operatorId")));
        view.setVersion(longValue(view.getDetail().get("newVersion")));
        return view;
    }

    private boolean matchesKey(AiAuditLogEntity entity, String key) {
        if (isBlank(key)) {
            return true;
        }
        Map<String, Object> detail = readDetail(entity.getDetailJson());
        return key.equals(detail.get("key")) || key.equals(detail.get("configKey"));
    }

    private Map<String, Object> readDetail(String json) {
        if (isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private Optional<AiRuntimeConfigEntity> findEntity(String key) {
        if (isBlank(key)) {
            return Optional.empty();
        }
        return configRepository.findByConfigKey(key.trim());
    }

    private RuntimeConfigDefinition requireDefinition(String key) {
        RuntimeConfigDefinition definition = definition(key);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown runtime config key: " + key);
        }
        return definition;
    }

    private RuntimeConfigDefinition definition(String key) {
        if (isBlank(key)) {
            return null;
        }
        return definitions.get(key.trim());
    }

    private void ensureDefinitionVisibility(RuntimeConfigDefinition definition) {
        if (definition == null || definition.secret || definition.defaultValue == null || definition.defaultValue.trim().isEmpty()) {
            return;
        }
        if (System.getProperty(definition.key) == null || System.getProperty(definition.key).trim().isEmpty()) {
            System.setProperty(definition.key, definition.defaultValue);
        }
    }

    private AiRuntimeConfigEntity defaultEntity(RuntimeConfigDefinition definition) {
        AiRuntimeConfigEntity entity = new AiRuntimeConfigEntity();
        entity.setConfigKey(definition.key);
        entity.setConfigValue(definition.defaultValue);
        entity.setMaskedValue(maskValue(definition.defaultValue, definition.secret));
        entity.setValueFingerprint(fingerprint(definition.defaultValue));
        entity.setConfigType(definition.configType);
        entity.setDynamicEnabled(definition.dynamic);
        entity.setRestartRequired(definition.restartRequired);
        entity.setSecret(definition.secret);
        entity.setVersion(0L);
        entity.setDescription(definition.description);
        entity.setCreateTime(String.valueOf(System.currentTimeMillis()));
        entity.setUpdateTime(entity.getCreateTime());
        return entity;
    }

    private String rawValue(String key) {
        RuntimeConfigDefinition definition = definition(key);
        if (definition == null) {
            return null;
        }
        return findEntity(definition.key).map(AiRuntimeConfigEntity::getConfigValue).orElse(definition.defaultValue);
    }

    private String maskValue(String value, boolean secret) {
        if (!secret) {
            return value;
        }
        if (isBlank(value)) {
            return "[REDACTED]";
        }
        String trimmed = value.trim();
        String suffix = trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4);
        return "****" + suffix;
    }

    private String maskedSecret(String value) {
        if (isBlank(value)) {
            return "[REDACTED]";
        }
        String trimmed = value.trim();
        String suffix = trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4);
        return "****" + suffix;
    }

    private String fingerprint(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.trim().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fingerprint runtime config", e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private long parseLong(String value) {
        if (isBlank(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Map<String, RuntimeConfigDefinition> buildDefinitions() {
        Map<String, RuntimeConfigDefinition> definitions = new LinkedHashMap<>();
        definitions.put("wdsavs.ai.relay.grant.auto-approve-enabled", def("wdsavs.ai.relay.grant.auto-approve-enabled", "true", "dynamic", false, false,
                "Auto approve relay access grant"));
        definitions.put("wdsavs.ai.relay.grant.default-ttl-ms", def("wdsavs.ai.relay.grant.default-ttl-ms", "1800000", "dynamic", false, false,
                "Default relay grant ttl in milliseconds"));
        definitions.put("wdsavs.ai.relay.grant.renew-enabled", def("wdsavs.ai.relay.grant.renew-enabled", "true", "dynamic", false, false,
                "Whether relay grant renew is enabled"));
        definitions.put("wdsavs.ai.relay.grant.revoke-immediate", def("wdsavs.ai.relay.grant.revoke-immediate", "true", "dynamic", false, false,
                "Whether relay grant revocation is immediate"));
        definitions.put("wdsavs.ai.relay.node-whitelist-enabled", def("wdsavs.ai.relay.node-whitelist-enabled", "true", "dynamic", false, false,
                "Whether relay node whitelist is enabled"));
        definitions.put("wdsavs.ai.relay.allowed-node-ids", def("wdsavs.ai.relay.allowed-node-ids", "*", "dynamic", false, false,
                "Allowed relay node ids"));
        definitions.put("wdsavs.ai.agent.default-max-steps", def("wdsavs.ai.agent.default-max-steps", "10", "dynamic", false, false,
                "Default max steps for agent execution"));
        definitions.put("wdsavs.ai.agent.default-command-whitelist", def("wdsavs.ai.agent.default-command-whitelist", "ccrelay-cli,ccrelay-cli.cmd", "dynamic", false, false,
                "Default command whitelist for agent collaboration"));
        definitions.put("wdsavs.ai.agent.default-step-timeout-ms", def("wdsavs.ai.agent.default-step-timeout-ms", "60000", "dynamic", false, false,
                "Default step timeout in milliseconds"));
        definitions.put("wdsavs.ai.agent.default-task-timeout-ms", def("wdsavs.ai.agent.default-task-timeout-ms", "600000", "dynamic", false, false,
                "Default task timeout in milliseconds"));
        definitions.put("wdsavs.ai.agent.default-allow-ai", def("wdsavs.ai.agent.default-allow-ai", "true", "dynamic", false, false,
                "Whether remote agent can call AI"));
        definitions.put("wdsavs.ai.agent.default-audit-level", def("wdsavs.ai.agent.default-audit-level", "SUMMARY", "dynamic", false, false,
                "Default audit level"));
        definitions.put("wdsavs.ai.observation.default-limit", def("wdsavs.ai.observation.default-limit", "50", "dynamic", false, false,
                "Default observation limit"));
        definitions.put("wdsavs.ai.observation.max-limit", def("wdsavs.ai.observation.max-limit", "500", "dynamic", false, false,
                "Maximum observation limit"));
        definitions.put("wdsavs.ai.observation.default-max-bytes", def("wdsavs.ai.observation.default-max-bytes", "65536", "dynamic", false, false,
                "Default observation max bytes"));
        definitions.put("wdsavs.ai.observation.per-event-max-bytes", def("wdsavs.ai.observation.per-event-max-bytes", "8192", "dynamic", false, false,
                "Observation per-event max bytes"));
        definitions.put("wdsavs.ai.a2a.large-file-threshold-bytes", def("wdsavs.ai.a2a.large-file-threshold-bytes", "5242880", "dynamic", false, false,
                "A2A large file threshold bytes"));
        definitions.put("wdsavs.ai.a2a.allowed-work-roots", def("wdsavs.ai.a2a.allowed-work-roots", "*", "dynamic", false, false,
                "Allowed work roots"));
        definitions.put("wdsavs.ai.a2a.allowed-log-roots", def("wdsavs.ai.a2a.allowed-log-roots", "*", "dynamic", false, false,
                "Allowed log roots"));
        definitions.put("wdsavs.ai.a2a.allowed-code-roots", def("wdsavs.ai.a2a.allowed-code-roots", "*", "dynamic", false, false,
                "Allowed code roots"));
        definitions.put("wdsavs.ai.relay.remote-directory-template", def("wdsavs.ai.relay.remote-directory-template",
                "/home/${sshUser}/${productName}/${host}-${relayPort}", "dynamic", false, false,
                "Default remote relay deployment directory template"));
        definitions.put("wdsavs.ai.relay.product-name", def("wdsavs.ai.relay.product-name", "ccrelay", "dynamic", false, false,
                "Relay product name used in path templates"));
        definitions.put("wdsavs.ai.relay.port-range", def("wdsavs.ai.relay.port-range", "18091-18191", "dynamic", false, false,
                "Preferred relay port range for auto selection"));
        definitions.put("wdsavs.ai.relay.auto-port-selection-enabled", def("wdsavs.ai.relay.auto-port-selection-enabled", "true", "dynamic", false, false,
                "Whether relay port auto selection is enabled"));
        definitions.put("wdsavs.ai.relay.hmac-secret", def("wdsavs.ai.relay.hmac-secret", null, "secret", true, true,
                "Relay HMAC secret"));
        definitions.put("wdsavs.ai.model.api-key", def("wdsavs.ai.model.api-key", null, "secret", false, true,
                "Model api key"));
        definitions.put("wdsavs.ai.model.base-url", def("wdsavs.ai.model.base-url", null, "restartRequired", false, false,
                "Model base url"));
        return definitions;
    }

    private static RuntimeConfigDefinition def(String key, String defaultValue, String configType,
                                               boolean restartRequired, boolean secret, String description) {
        return new RuntimeConfigDefinition(key, defaultValue, configType, "dynamic".equalsIgnoreCase(configType), restartRequired, secret, description);
    }

    private static final class RuntimeConfigDefinition {
        private final String key;
        private final String defaultValue;
        private final String configType;
        private final boolean dynamic;
        private final boolean restartRequired;
        private final boolean secret;
        private final String description;

        private RuntimeConfigDefinition(String key, String defaultValue, String configType,
                                        boolean dynamic, boolean restartRequired, boolean secret, String description) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.configType = configType;
            this.dynamic = dynamic;
            this.restartRequired = restartRequired;
            this.secret = secret;
            this.description = description;
        }
    }
}
