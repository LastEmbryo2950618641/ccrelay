package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextDeltaRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayGrantService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiSessionContextService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/skill/session")
public class SkillSessionContextController {

    private final AiSessionContextService contextService;
    private final AiRelayGrantService relayGrantService;

    public SkillSessionContextController(AiSessionContextService contextService,
                                         AiRelayGrantService relayGrantService) {
        this.contextService = contextService;
        this.relayGrantService = relayGrantService;
    }

    @GetMapping("/{sessionId}/context/head")
    public Map<String, Object> head(@PathVariable String sessionId) {
        return Map.of("sessionId", sessionId, "headCursor", contextService.headCursor(sessionId));
    }

    @GetMapping("/{sessionId}/context/delta")
    public Map<String, Object> delta(@PathVariable String sessionId,
                                     @RequestParam(defaultValue = "0") long afterCursor,
                                     @RequestParam(defaultValue = "200") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("afterCursor", Math.max(afterCursor, 0L));
        result.put("events", contextService.delta(sessionId, afterCursor, limit));
        result.put("headCursor", contextService.headCursor(sessionId));
        return result;
    }

    @PostMapping("/{sessionId}/context/events")
    public Object append(@PathVariable String sessionId,
                         @RequestBody AiSessionContextAppendRequest request) {
        return contextService.append(sessionId, request);
    }

    @PostMapping("/context/delta")
    public Map<String, Object> relayDelta(@RequestBody AiSessionContextDeltaRequest request) {
        if (request == null || request.getGrant() == null) {
            throw new SecurityException("Signed context grant is required");
        }
        RelayGrantValidateResponse validation = relayGrantService.validateGrant(request.getGrant());
        if (validation == null || !Boolean.TRUE.equals(validation.getValid())) {
            throw new SecurityException(validation == null ? "Context grant validation failed" : validation.getMessage());
        }
        if (request.getSessionId() == null || !request.getSessionId().equals(request.getGrant().getSessionId())) {
            throw new SecurityException("Context session does not match grant");
        }
        return delta(request.getSessionId(), request.getAfterCursor(), request.getLimit());
    }
}
