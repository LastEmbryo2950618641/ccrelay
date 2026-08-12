package com.webank.wedatasphere.wdsavs.aiagent.remote;

import lombok.Data;

@Data
class RelaySkillMetadata {
    private String skillId;
    private String centerSha256;
    private String installedSha256;
    private String status;
    private String installPath;
    private String lastError;
    private Long updatedAt;
}
