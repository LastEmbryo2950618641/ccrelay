package com.webank.wedatasphere.wdsavs.aiagent.remote;

import lombok.Data;

@Data
class RelayPromptMetadata {
    private String promptId;
    private String type;
    private Integer order;
    private String centerSha256;
    private String installedSha256;
    private String status;
    private String contentPath;
    private String lastError;
    private Long updatedAt;
}
