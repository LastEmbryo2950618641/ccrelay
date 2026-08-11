package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigHistoryView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigReloadResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigUnsetRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigUpsertRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RuntimeConfigView;
import com.webank.wedatasphere.wdsavs.aiagent.service.RuntimeConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/skill/config")
public class SkillConfigController {

    private final RuntimeConfigService runtimeConfigService;

    public SkillConfigController(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping("/{key}")
    public RuntimeConfigView get(@PathVariable String key) {
        return runtimeConfigService.getConfig(key);
    }

    @GetMapping
    public List<RuntimeConfigView> list(@RequestParam(required = false) String prefix) {
        return runtimeConfigService.listConfigs(prefix);
    }

    @PostMapping
    public RuntimeConfigView set(@RequestBody RuntimeConfigUpsertRequest request) {
        return runtimeConfigService.setConfig(request.getKey(), request.getValue(), request.getOperatorId(), request.getComment());
    }

    @PostMapping("/secret")
    public RuntimeConfigView setSecret(@RequestBody RuntimeConfigUpsertRequest request) {
        return runtimeConfigService.setSecretConfig(request.getKey(), request.getValue(), request.getOperatorId(), request.getComment());
    }

    @PostMapping("/unset")
    public RuntimeConfigView unset(@RequestBody RuntimeConfigUnsetRequest request) {
        return runtimeConfigService.unsetConfig(request.getKey(), request.getOperatorId(), request.getComment());
    }

    @PostMapping("/reload")
    public RuntimeConfigReloadResponse reload(@RequestParam(required = false) String operatorId) {
        return runtimeConfigService.reload(operatorId);
    }

    @GetMapping("/history/{key}")
    public List<RuntimeConfigHistoryView> history(@PathVariable String key, @RequestParam(required = false) Integer limit) {
        return runtimeConfigService.history(key, limit == null ? 50 : limit);
    }
}
