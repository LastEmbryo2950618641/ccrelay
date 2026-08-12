package com.webank.wedatasphere.wdsavs.aiagentskill.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionContextEventEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayNodeView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionContextEventRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiSessionRepository;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayGrantService;
import com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayRegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTitleServiceTest {

    @Test
    void generatesTitleWithCoordinatorFromFirstUserMessage() {
        AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
        AiSessionContextEventRepository contextRepository = mock(AiSessionContextEventRepository.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        AiSessionEntity session = session("session-1", null);
        when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(contextRepository.findFirstBySessionIdAndRoleOrderByIdAsc("session-1", "user"))
                .thenReturn(Optional.of(userEvent("请检查集群服务状态并说明风险")));
        when(registryService.getNode("node-b:18192")).thenReturn(node("node-b:18192"));
        when(grantService.requestAccess(any())).thenReturn(grant());
        when(restTemplate.postForObject(eq("http://node-b:18192/api/ai/remote-cc/chat"),
                any(AiChatRequest.class), eq(AiChatResponse.class)))
                .thenReturn(new AiChatResponse("会话标题：\"集群服务状态检查。\"\n说明", "SUCCESS", "trace-1"));
        SessionTitleService service = new SessionTitleService(sessionRepository, contextRepository,
                registryService, grantService, restTemplate);

        service.generateIfAbsent("session-1", "node-b:18192");

        assertEquals("集群服务状态检查", session.getTitle());
        verify(sessionRepository).save(session);
        ArgumentCaptor<RelayAccessRequest> accessRequest = ArgumentCaptor.forClass(RelayAccessRequest.class);
        verify(grantService).requestAccess(accessRequest.capture());
        assertEquals("session-1", accessRequest.getValue().getSessionId());
        assertEquals("node-b:18192", accessRequest.getValue().getTargetNodeId());
        assertEquals(List.of("CHAT"), accessRequest.getValue().getRequiredCapabilities());
        ArgumentCaptor<AiChatRequest> chatRequest = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(restTemplate).postForObject(eq("http://node-b:18192/api/ai/remote-cc/chat"),
                chatRequest.capture(), eq(AiChatResponse.class));
        assertEquals("请检查集群服务状态并说明风险", chatRequest.getValue().getMessages().get(0).getContent());
        assertEquals("SESSION_TITLE", chatRequest.getValue().getMetadata().get("requestPurpose"));
        assertEquals("node-b:18192", chatRequest.getValue().getMetadata().get("targetNodeId"));
        Map<?, ?> relayGrant = (Map<?, ?>) chatRequest.getValue().getMetadata().get("relayGrant");
        assertEquals(List.of("CHAT"), relayGrant.get("allowedCapabilities"));
    }

    @Test
    void leavesFallbackTitleWhenGenerationFails() {
        AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
        AiSessionContextEventRepository contextRepository = mock(AiSessionContextEventRepository.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        AiSessionEntity session = session("session-1", null);
        when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(contextRepository.findFirstBySessionIdAndRoleOrderByIdAsc("session-1", "user"))
                .thenReturn(Optional.of(userEvent("检查状态")));
        when(registryService.getNode("node-a:18192")).thenReturn(node("node-a:18192"));
        RelayAccessDecisionResponse denied = new RelayAccessDecisionResponse();
        denied.setDecision("DENY");
        when(grantService.requestAccess(any())).thenReturn(denied);
        SessionTitleService service = new SessionTitleService(sessionRepository, contextRepository,
                registryService, grantService, restTemplate);

        service.generateIfAbsent("session-1", "node-a:18192");

        assertNull(session.getTitle());
        verify(restTemplate, never()).postForObject(any(String.class), any(), eq(AiChatResponse.class));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void doesNotRegenerateExistingTitle() {
        AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
        AiSessionContextEventRepository contextRepository = mock(AiSessionContextEventRepository.class);
        AiRelayRegistryService registryService = mock(AiRelayRegistryService.class);
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(sessionRepository.findBySessionId("session-1"))
                .thenReturn(Optional.of(session("session-1", "已有标题")));
        SessionTitleService service = new SessionTitleService(sessionRepository, contextRepository,
                registryService, grantService, restTemplate);

        service.generateIfAbsent("session-1", "node-a:18192");

        verify(contextRepository, never()).findFirstBySessionIdAndRoleOrderByIdAsc(any(), any());
        verify(registryService, never()).getNode(any());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void normalizesAndTruncatesModelOutput() {
        SessionTitleService service = new SessionTitleService(mock(AiSessionRepository.class),
                mock(AiSessionContextEventRepository.class), mock(AiRelayRegistryService.class),
                mock(AiRelayGrantService.class), mock(RestTemplate.class));

        assertEquals("服务故障定位", service.normalizeTitle("  标题：`服务故障定位！`\n补充说明"));
        assertEquals("一".repeat(40), service.normalizeTitle("一".repeat(45)));
        assertNull(service.normalizeTitle("标题：\"\""));
    }

    private AiSessionEntity session(String sessionId, String title) {
        AiSessionEntity session = new AiSessionEntity();
        session.setSessionId(sessionId);
        session.setTitle(title);
        return session;
    }

    private AiSessionContextEventEntity userEvent(String content) {
        AiSessionContextEventEntity event = new AiSessionContextEventEntity();
        event.setRole("user");
        event.setContent(content);
        return event;
    }

    private RelayNodeView node(String nodeId) {
        RelayNodeView node = new RelayNodeView();
        node.setNodeId(nodeId);
        node.setStatus("AVAILABLE");
        node.setRelayEndpoint("http://" + nodeId + "/api/ai/remote-cc/chat");
        return node;
    }

    private RelayAccessDecisionResponse grant() {
        RelayAccessDecisionResponse grant = new RelayAccessDecisionResponse();
        grant.setDecision("ALLOW");
        grant.setGrantId("grant-title");
        grant.setSignedToken("signed-token");
        grant.setExpiresAt("9999999999999");
        grant.setAllowedCapabilities(List.of("CHAT"));
        return grant;
    }
}
