package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSkillEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogDigestResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillInstallResponse;

import java.nio.file.Path;

public interface SkillCatalogService {
    SkillInstallResponse install(byte[] artifact);

    SkillCatalogDigestResponse digest();

    SkillCatalogResponse catalog();

    AiSkillEntity getActive(String skillId);

    Path artifact(String skillId);

    SkillInstallResponse invalidate(String skillId);
}
