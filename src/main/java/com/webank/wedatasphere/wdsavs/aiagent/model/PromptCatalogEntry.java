package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class PromptCatalogEntry {
    private String promptId;
    private PromptType type;
    private Integer order;
    private String sha256;
    private String status;
    private Long contentSize;
    private Long updatedAt;
    private String contentUrl;
}
