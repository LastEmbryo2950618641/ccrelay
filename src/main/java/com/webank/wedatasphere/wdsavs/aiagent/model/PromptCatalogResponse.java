package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PromptCatalogResponse {
    private boolean complete = true;
    private String catalogSha256;
    private List<PromptCatalogEntry> prompts = new ArrayList<>();
}
