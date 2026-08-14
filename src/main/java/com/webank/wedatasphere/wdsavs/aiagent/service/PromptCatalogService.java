package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiPromptEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogDigestResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptInstallResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptType;

import java.nio.file.Path;

public interface PromptCatalogService {
    PromptInstallResponse install(String promptId, PromptType type, int order, byte[] content);

    PromptCatalogDigestResponse digest();

    PromptCatalogResponse catalog();

    AiPromptEntity getActive(String promptId);

    Path content(String promptId);

    PromptInstallResponse invalidate(String promptId);
}
