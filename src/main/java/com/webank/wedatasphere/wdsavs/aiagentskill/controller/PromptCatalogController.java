package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiPromptEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogDigestResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptCatalogResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptInstallResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.PromptType;
import com.webank.wedatasphere.wdsavs.aiagent.service.PromptCatalogService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prompt/catalog")
public class PromptCatalogController {

    public static final String PROMPT_ID_HEADER = "X-CCRelay-Prompt-Id";
    public static final String PROMPT_TYPE_HEADER = "X-CCRelay-Prompt-Type";
    public static final String PROMPT_ORDER_HEADER = "X-CCRelay-Prompt-Order";

    private final PromptCatalogService service;

    public PromptCatalogController(PromptCatalogService service) {
        this.service = service;
    }

    @PostMapping(value = "/install", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public PromptInstallResponse install(@RequestHeader(PROMPT_ID_HEADER) String promptId,
                                         @RequestHeader(PROMPT_TYPE_HEADER) PromptType type,
                                         @RequestHeader(PROMPT_ORDER_HEADER) int order,
                                         @RequestBody byte[] content) {
        return service.install(promptId, type, order, content);
    }

    @GetMapping("/digest")
    public PromptCatalogDigestResponse digest() {
        return service.digest();
    }

    @GetMapping
    public PromptCatalogResponse catalog() {
        return service.catalog();
    }

    @GetMapping("/{promptId}/content")
    public ResponseEntity<Resource> content(@PathVariable String promptId) {
        AiPromptEntity entity = service.getActive(promptId);
        FileSystemResource resource = new FileSystemResource(service.content(promptId));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(entity.getContentSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(promptId + "-" + entity.getSha256() + ".md")
                        .build().toString())
                .body(resource);
    }

    @DeleteMapping("/{promptId}")
    public PromptInstallResponse invalidate(@PathVariable String promptId) {
        return service.invalidate(promptId);
    }
}
