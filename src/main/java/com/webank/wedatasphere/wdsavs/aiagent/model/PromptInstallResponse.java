package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromptInstallResponse {
    private String promptId;
    private PromptType type;
    private Integer order;
    private String sha256;
    private String status;
    private Long contentSize;
}
