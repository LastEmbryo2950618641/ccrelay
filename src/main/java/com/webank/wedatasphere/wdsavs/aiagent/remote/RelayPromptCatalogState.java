package com.webank.wedatasphere.wdsavs.aiagent.remote;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
class RelayPromptCatalogState {
    private String catalogSha256;
    private List<RelayPromptMetadata> prompts = new ArrayList<>();
}
