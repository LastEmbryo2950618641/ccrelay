package com.webank.wedatasphere.wdsavs.aiagentskill.controller;

import com.webank.wedatasphere.wdsavs.aiagent.service.AiSessionService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiSessionCollaborationService;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.SessionOpenRequest;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.SessionCollaborationRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/skill/session")
public class SkillSessionController {

    private final AiSessionService sessionService;
    private final AiSessionCollaborationService collaborationService;

    public SkillSessionController(AiSessionService sessionService,
                                  AiSessionCollaborationService collaborationService) {
        this.sessionService = sessionService;
        this.collaborationService = collaborationService;
    }

    @PostMapping("/open")
    public Map<String, Object> open(@RequestBody SessionOpenRequest request) {
        String sessionId = sessionService.openSession(request.getInitiatorType(), request.getInitiatorId(), request.getSourceNodeId());
        return Map.of("sessionId", sessionId);
    }

    @PostMapping("/{sessionId}/close")
    public Map<String, Object> close(@PathVariable String sessionId) {
        sessionService.closeSession(sessionId);
        return Map.of("closed", true, "sessionId", sessionId);
    }

    @PostMapping("/{sessionId}/collaboration/initialize")
    public Object initializeCollaboration(@PathVariable String sessionId,
                                          @RequestBody SessionCollaborationRequest request) {
        return collaborationService.initialize(sessionId,
                request == null ? null : request.getCollaborationMode(),
                request == null ? null : request.getParticipantNodeIds(),
                request == null ? null : request.getCollaborationPolicy());
    }

    @GetMapping("/{sessionId}/collaboration")
    public Object collaboration(@PathVariable String sessionId) {
        return collaborationService.getState(sessionId);
    }
}
