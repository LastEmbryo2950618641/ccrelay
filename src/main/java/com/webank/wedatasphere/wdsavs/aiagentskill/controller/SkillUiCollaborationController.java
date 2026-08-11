package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.service.AiSessionService;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.SessionOpenRequest;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.UiSessionMessageRequest;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.UiSessionMessageResponse;
import com.webank.wedatasphere.wdsavs.aiagentskill.service.UiCollaborationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/skill/ui")
public class SkillUiCollaborationController {

    private final AiSessionService sessionService;
    private final UiCollaborationService collaborationService;

    public SkillUiCollaborationController(AiSessionService sessionService,
                                          UiCollaborationService collaborationService) {
        this.sessionService = sessionService;
        this.collaborationService = collaborationService;
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody(required = false) SessionOpenRequest request) {
        SessionOpenRequest effective = request == null ? new SessionOpenRequest() : request;
        String sessionId = sessionService.openSession(
                normalize(effective.getInitiatorType(), "USER"),
                normalize(effective.getInitiatorId(), "ccrelay-observer"),
                normalize(effective.getSourceNodeId(), "cc-center-ui"));
        return Map.of("sessionId", sessionId, "status", "OPEN");
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public UiSessionMessageResponse send(@PathVariable String sessionId,
                                         @RequestBody UiSessionMessageRequest request) {
        return collaborationService.send(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/sync")
    public Map<String, Object> synchronize(@PathVariable String sessionId) {
        return collaborationService.synchronize(sessionId);
    }

    private String normalize(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
