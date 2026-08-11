package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiModelConfigEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiModelProviderConfig;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiModelConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AiModelConfigService {

    private static final String DEFAULT_PROTOCOL = "OPENAI_COMPATIBLE";
    private static final String DEFAULT_API_PATH = "/chat/completions";

    private final AiModelConfigRepository repository;

    public AiModelConfigService(AiModelConfigRepository repository) {
        this.repository = repository;
    }

    public List<AiModelProviderConfig> listAll() {
        return repository.findAllByOrderByDefaultConfigDescDisplayNameAsc().stream()
                .map(entity -> toDto(entity, false))
                .collect(Collectors.toList());
    }

    public List<AiModelProviderConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByDefaultConfigDescDisplayNameAsc().stream()
                .map(entity -> toDto(entity, false))
                .collect(Collectors.toList());
    }

    public AiModelConfigEntity resolve(Long id, String providerCode) {
        if (id != null) {
            return repository.findById(id)
                    .filter(entity -> Boolean.TRUE.equals(entity.getEnabled()))
                    .orElseThrow(() -> new IllegalArgumentException("AI模型配置不存在或已禁用"));
        }
        if (!isBlank(providerCode)) {
            return repository.findByProviderCode(providerCode.trim())
                    .filter(entity -> Boolean.TRUE.equals(entity.getEnabled()))
                    .orElseThrow(() -> new IllegalArgumentException("AI模型配置不存在或已禁用: " + providerCode));
        }
        return repository.findFirstByDefaultConfigTrueAndEnabledTrue()
                .orElseThrow(() -> new IllegalArgumentException("请先维护可用的AI模型配置"));
    }

    @Transactional
    public AiModelProviderConfig save(AiModelProviderConfig request) {
        validate(request);
        AiModelConfigEntity entity = resolveForSave(request);
        boolean isCreate = entity.getId() == null;
        String now = String.valueOf(System.currentTimeMillis());
        entity.setProviderCode(normalizeProviderCode(request.getProviderCode()));
        entity.setDisplayName(trim(request.getDisplayName()));
        entity.setProtocol(isBlank(request.getProtocol()) ? DEFAULT_PROTOCOL : trim(request.getProtocol()).toUpperCase(Locale.ROOT));
        entity.setBaseUrl(trim(request.getBaseUrl()));
        entity.setApiPath(isBlank(request.getApiPath()) ? DEFAULT_API_PATH : trim(request.getApiPath()));
        if (!isBlank(request.getApiKey()) || isCreate) {
            entity.setApiKey(trim(request.getApiKey()));
        }
        entity.setModelName(trim(request.getModelName()));
        entity.setTemperature(request.getTemperature());
        entity.setTopP(request.getTopP());
        entity.setMaxTokens(request.getMaxTokens());
        entity.setPresencePenalty(request.getPresencePenalty());
        entity.setFrequencyPenalty(request.getFrequencyPenalty());
        entity.setExtraBodyJson(trim(request.getExtraBodyJson()));
        entity.setExtraHeadersJson(trim(request.getExtraHeadersJson()));
        entity.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
        entity.setDefaultConfig(Boolean.TRUE.equals(request.getDefaultConfig()));
        if (isCreate) {
            entity.setCreateUser("system");
            entity.setCreateTime(now);
        }
        entity.setModifyUser("system");
        entity.setModifyTime(now);
        AiModelConfigEntity saved = repository.save(entity);
        if (Boolean.TRUE.equals(saved.getDefaultConfig())) {
            clearOtherDefaults(saved.getId());
        }
        return toDto(saved, false);
    }

    @Transactional
    public Boolean delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("模型配置ID不能为空");
        }
        repository.deleteById(id);
        return Boolean.TRUE;
    }

    private AiModelConfigEntity resolveForSave(AiModelProviderConfig request) {
        if (request.getId() != null) {
            return repository.findById(request.getId())
                    .orElseThrow(() -> new IllegalArgumentException("模型配置不存在: " + request.getId()));
        }
        Optional<AiModelConfigEntity> existing = repository.findByProviderCode(normalizeProviderCode(request.getProviderCode()));
        return existing.orElseGet(AiModelConfigEntity::new);
    }

    private void clearOtherDefaults(Long id) {
        for (AiModelConfigEntity entity : repository.findByDefaultConfigTrueAndIdNot(id)) {
            entity.setDefaultConfig(Boolean.FALSE);
            entity.setModifyUser("system");
            entity.setModifyTime(String.valueOf(System.currentTimeMillis()));
            repository.save(entity);
        }
    }

    private void validate(AiModelProviderConfig request) {
        if (request == null) {
            throw new IllegalArgumentException("模型配置不能为空");
        }
        if (isBlank(request.getProviderCode())) {
            throw new IllegalArgumentException("模型编码不能为空");
        }
        if (isBlank(request.getDisplayName())) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (isBlank(request.getBaseUrl())) {
            throw new IllegalArgumentException("Base URL不能为空");
        }
        if (isBlank(request.getModelName())) {
            throw new IllegalArgumentException("默认模型不能为空");
        }
    }

    private AiModelProviderConfig toDto(AiModelConfigEntity entity, boolean includeApiKey) {
        AiModelProviderConfig dto = new AiModelProviderConfig();
        dto.setId(entity.getId());
        dto.setProviderCode(entity.getProviderCode());
        dto.setDisplayName(entity.getDisplayName());
        dto.setProtocol(entity.getProtocol());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setApiPath(entity.getApiPath());
        dto.setApiKey(includeApiKey ? entity.getApiKey() : null);
        dto.setApiKeyMasked(mask(entity.getApiKey()));
        dto.setModelName(entity.getModelName());
        dto.setTemperature(entity.getTemperature());
        dto.setTopP(entity.getTopP());
        dto.setMaxTokens(entity.getMaxTokens());
        dto.setPresencePenalty(entity.getPresencePenalty());
        dto.setFrequencyPenalty(entity.getFrequencyPenalty());
        dto.setExtraBodyJson(entity.getExtraBodyJson());
        dto.setExtraHeadersJson(entity.getExtraHeadersJson());
        dto.setEnabled(entity.getEnabled());
        dto.setDefaultConfig(entity.getDefaultConfig());
        dto.setCreateUser(entity.getCreateUser());
        dto.setCreateTime(entity.getCreateTime());
        dto.setModifyUser(entity.getModifyUser());
        dto.setModifyTime(entity.getModifyTime());
        return dto;
    }

    private String normalizeProviderCode(String providerCode) {
        return trim(providerCode).toUpperCase(Locale.ROOT);
    }

    private String mask(String value) {
        if (isBlank(value)) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= 8) {
            return "********";
        }
        return text.substring(0, 4) + "****" + text.substring(text.length() - 4);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
