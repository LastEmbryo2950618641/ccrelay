package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionMessageCompletion;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionMessageDispatchResult;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class A2aAgentServiceQueueWakeTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void createsAuthorizedReverseWakeAfterDeferredDiscussionReply() {
        AiRelayGrantService grantService = mock(AiRelayGrantService.class);
        RelayAccessDecisionResponse grant = new RelayAccessDecisionResponse();
        grant.setDecision("ALLOW");
        grant.setGrantId("wake-grant");
        grant.setSignedToken("wake-token");
        grant.setExpiresAt(String.valueOf(System.currentTimeMillis() + 60000L));
        grant.setTargetRelayEndpoint("http://node-a/chat");
        when(grantService.requestAccess(any())).thenReturn(grant);

        AiSessionMessageQueueService queueService = mock(AiSessionMessageQueueService.class);
        AiSessionMessageDispatchResult queued = new AiSessionMessageDispatchResult();
        queued.setQueued(true);
        queued.setResponse(new AiChatResponse("queued", "QUEUED", "wake-queue"));
        when(queueService.submit(any(), anyString(), anyBoolean())).thenReturn(queued);

        A2aAgentService service = new A2aAgentService(
                request -> new AiChatResponse("local", "SUCCESS", "trace"),
                new AiCapabilityCatalogService(),
                grantService,
                null,
                null,
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(),
                false);
        AiSessionCollaborationService collaborationService = mock(AiSessionCollaborationService.class);
        doAnswer(invocation -> {
            Map<String, Object> params = invocation.getArgument(0);
            Map<String, Object> metadata = (Map<String, Object>) params.get("metadata");
            String role = "node-a".equals(params.get("targetNodeId")) ? "COORDINATOR" : "PARTICIPANT";
            metadata.put("agentRole", role);
            params.put("agentRole", role);
            return null;
        }).when(collaborationService).enrichTaskParams(any());
        service.setSessionCollaborationService(collaborationService);
        service.setSessionMessageQueueService(queueService);

        ArgumentCaptor<Function> listenerCaptor = ArgumentCaptor.forClass(Function.class);
        verify(queueService).registerCompletionListener(listenerCaptor.capture());

        AiSessionMessageCompletion completion = new AiSessionMessageCompletion();
        completion.setQueueId("queue-1");
        completion.setSessionId("session-1");
        completion.setRequestId("request-1");
        completion.setSourceNodeId("node-a");
        completion.setTargetNodeId("node-b");
        completion.setRequestParams(Map.of("metadata", Map.of(
                "collaborationMode", "DISCUSSION",
                "collaborationPolicy", Map.of("maxWakeDepth", 4))));
        completion.setResponse(new AiChatResponse("node-b answer", "SUCCESS", "trace-b"));

        assertTrue((Boolean) listenerCaptor.getValue().apply(completion));

        ArgumentCaptor<RelayAccessRequest> grantCaptor = ArgumentCaptor.forClass(RelayAccessRequest.class);
        verify(grantService).requestAccess(grantCaptor.capture());
        assertEquals("node-b", grantCaptor.getValue().getSourceNodeId());
        assertEquals("node-a", grantCaptor.getValue().getTargetNodeId());

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(queueService).submit(paramsCaptor.capture(), anyString(), anyBoolean());
        Map<String, Object> wakeParams = paramsCaptor.getValue();
        assertEquals("node-b", wakeParams.get("sourceNodeId"));
        assertEquals("node-a", wakeParams.get("targetNodeId"));
        assertEquals("wake-grant", wakeParams.get("grantId"));
        assertEquals("http://node-a/chat", wakeParams.get("targetRelayEndpoint"));
        assertEquals(1, ((Map<?, ?>) wakeParams.get("metadata")).get("wakeDepth"));
        assertEquals("COORDINATOR", wakeParams.get("agentRole"));
        assertEquals("COORDINATOR", ((Map<?, ?>) wakeParams.get("metadata")).get("agentRole"));
        verify(collaborationService).enrichTaskParams(any());
    }
}
