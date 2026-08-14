package com.webank.wedatasphere.wdsavs.aiagent.remote;

import java.nio.file.Path;
import java.util.List;

record RelayPromptContent(String promptId, String type, int order, String sha256, Path contentPath) {
}

record RelayPromptCatalogSnapshot(String catalogSha256, List<RelayPromptContent> prompts) {
    RelayPromptCatalogSnapshot {
        prompts = List.copyOf(prompts);
    }

    String getCatalogSha256() {
        return catalogSha256;
    }

    List<RelayPromptContent> getPrompts() {
        return prompts;
    }
}
