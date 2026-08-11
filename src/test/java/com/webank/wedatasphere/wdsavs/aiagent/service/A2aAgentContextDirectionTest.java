package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextEventView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aAgentContextDirectionTest {

    @Test
    void recordsRelayReplyBackToTheOriginalSourceNode() {
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        RelayGrantView grant = new RelayGrantView();
        grant.setGrantId("grant-1");
        grant.setExpiresAt("9999999999999");
        grant.setAllowedCapabilities(List.of("A2A_MESSAGE_SEND"));
        when(grantService.getGrant("grant-1")).thenReturn(grant);
        when(grantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(eq("http://target:19192/internal/grant/validate"), any(),
                eq(RelayGrantValidateResponse.class)))
                .thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        when(restTemplate.postForObject(eq("http://target:19192/api/ai/remote-cc/chat"),
                any(AiChatRequest.class), eq(AiChatResponse.class)))
                .thenReturn(new AiChatResponse("目标节点回复", "SUCCESS", "trace-1"));
        AiSessionContextService contextService = mock(AiSessionContextService.class);
        when(contextService.append(any(), any())).thenReturn(new AiSessionContextEventView());
        when(contextService.headCursor("session-1")).thenReturn(1L);
        A2aAgentService service = new A2aAgentService(
                request -> new AiChatResponse("fallback", "SUCCESS", "fallback-trace"),
                new AiCapabilityCatalogService(),
                grantService,
                mock(AiRelayRegistryService.class),
                mock(AiAuditService.class),
                restTemplate,
                new A2aPayloadPolicyServiceImpl(),
                false);
        service.setSessionContextService(contextService);
        A2aJsonRpcRequest request = new A2aJsonRpcRequest();
        request.setJsonrpc("2.0");
        request.setId("request-1");
        request.setMethod("message/send");
        request.setParams(Map.of(
                "sessionId", "session-1",
                "taskId", "task-1",
                "senderType", "RELAY",
                "sourceNodeId", "node-source:19191",
                "targetNodeId", "node-target:19192",
                "targetRelayEndpoint", "http://target:19192/api/ai/remote-cc/chat",
                "grantId", "grant-1",
                "signedToken", "signed-token",
                "messages", List.of(Map.of("role", "user", "content", "请补充证据"))));

        service.handle(request);

        ArgumentCaptor<AiSessionContextAppendRequest> events = ArgumentCaptor.forClass(AiSessionContextAppendRequest.class);
        verify(contextService, times(2)).append(eq("session-1"), events.capture());
        AiSessionContextAppendRequest question = events.getAllValues().get(0);
        assertEquals("RELAY", question.getSenderType());
        assertEquals("node-source:19191", question.getSenderId());
        assertEquals("node-target:19192", question.getTargetNodeId());
        AiSessionContextAppendRequest answer = events.getAllValues().get(1);
        assertEquals("RELAY", answer.getSenderType());
        assertEquals("node-target:19192", answer.getSenderId());
        assertEquals("node-source:19191", answer.getTargetNodeId());
        assertEquals("task-1", answer.getTaskId());
    }
}
