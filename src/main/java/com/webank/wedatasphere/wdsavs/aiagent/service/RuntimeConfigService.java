package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigHistoryView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigReloadResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigView;

import java.util.List;

public interface RuntimeConfigService {

    String getString(String key, String defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    long getLong(String key, long defaultValue);

    int getInt(String key, int defaultValue);

    List<String> getList(String key, List<String> defaultValue);

    RuntimeConfigView getConfig(String key);

    List<RuntimeConfigView> listConfigs(String prefix);

    RuntimeConfigView setConfig(String key, String value, String operatorId, String comment);

    RuntimeConfigView setSecretConfig(String key, String value, String operatorId, String comment);

    RuntimeConfigView unsetConfig(String key, String operatorId, String comment);

    RuntimeConfigReloadResponse reload(String operatorId);

    List<RuntimeConfigHistoryView> history(String key, int limit);

    boolean isSecret(String key);

    boolean isDynamic(String key);

    boolean isRestartRequired(String key);
}
