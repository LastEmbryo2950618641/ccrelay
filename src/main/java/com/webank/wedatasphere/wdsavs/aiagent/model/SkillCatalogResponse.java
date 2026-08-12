package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SkillCatalogResponse {
    private boolean complete = true;
    private String catalogSha256;
    private List<SkillCatalogEntry> skills = new ArrayList<>();
}
