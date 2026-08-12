package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSkillEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogDigestResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillCatalogResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.SkillInstallResponse;
import com.webank.wedatasphere.wdsavs.aiagent.service.SkillCatalogService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skill/catalog")
public class SkillCatalogController {

    private final SkillCatalogService service;

    public SkillCatalogController(SkillCatalogService service) {
        this.service = service;
    }

    @PostMapping(value = "/install", consumes = {"application/zip", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public SkillInstallResponse install(@RequestBody byte[] artifact) {
        return service.install(artifact);
    }

    @GetMapping("/digest")
    public SkillCatalogDigestResponse digest() {
        return service.digest();
    }

    @GetMapping
    public SkillCatalogResponse catalog() {
        return service.catalog();
    }

    @GetMapping("/{skillId}/artifact")
    public ResponseEntity<Resource> artifact(@PathVariable String skillId) {
        AiSkillEntity entity = service.getActive(skillId);
        FileSystemResource resource = new FileSystemResource(service.artifact(skillId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(entity.getArtifactSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(skillId + "-" + entity.getSha256() + ".zip")
                        .build().toString())
                .body(resource);
    }

    @DeleteMapping("/{skillId}")
    public SkillInstallResponse invalidate(@PathVariable String skillId) {
        return service.invalidate(skillId);
    }
}
