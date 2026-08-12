package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillInstallResponse {
    private String skillId;
    private String sha256;
    private String status;
    private Long artifactSize;
}
