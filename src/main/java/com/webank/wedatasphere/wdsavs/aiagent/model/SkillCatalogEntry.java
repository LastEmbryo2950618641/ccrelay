package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class SkillCatalogEntry {
    private String skillId;
    private String sha256;
    private String status;
    private Long artifactSize;
    private Long updatedAt;
    private String artifactUrl;
}
