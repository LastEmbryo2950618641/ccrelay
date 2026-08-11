package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.service.RelayRequestSecurityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteSessionContextSynchronizerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesContextEventSourceMetadataForTheModel() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RelayRequestSecurityService securityService = mock(RelayRequestSecurityService.class);
        when(securityService.sign(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(restTemplate.postForObject(eq("http://center/context/delta"), any(), eq(Map.class)))
                .thenReturn(Map.of(
                        "events", List.of(Map.of(
                                "cursor", 7L,
                                "eventId", "event-7",
                                "taskId", "task-7",
                                "senderType", "RELAY",
                                "senderId", "node-a:19192",
                                "targetNodeId", "node-b:19193,node-c:19194",
                                "role", "assistant",
                                "content", "远端结论",
                                "contentType", "TEXT",
                                "createdTime", "1786219200000")),
                        "headCursor", 7L));
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setContextStateFilePath(temporaryDirectory.resolve("context-state.json").toString());
        RemoteSessionContextSynchronizer synchronizer = new RemoteSessionContextSynchronizer(
                restTemplate,
                securityService,
                new RemoteSessionContextStateStore(properties, new ObjectMapper()));
        AiChatRequest request = new AiChatRequest();
        request.setMessages(List.of(new AiChatMessage("user", "fallback")));
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", "session-1");
        metadata.put("centerContextDeltaEndpoint", "http://center/context/delta");
        metadata.put("contextHeadCursor", 7L);
        metadata.put("relayGrant", relayGrant());
        request.setMetadata(metadata);

        synchronizer.synchronize(request, "node-b:19193");

        assertEquals(1, request.getMessages().size());
        AiChatMessage message = request.getMessages().get(0);
        assertEquals("assistant", message.getRole());
        assertEquals("远端结论", message.getContent());
        assertEquals("node-a:19192", message.getMetadata().get("senderId"));
        assertEquals(List.of("node-b:19193", "node-c:19194"), message.getMetadata().get("targetNodeIds"));
        assertEquals("task-7", message.getMetadata().get("taskId"));
    }

    @Test
    void failedExecutionPersistsStartedModelSessionWithoutAdvancingContextCursor() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RelayRequestSecurityService securityService = mock(RelayRequestSecurityService.class);
        when(securityService.sign(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(restTemplate.postForObject(eq("http://center/context/delta"), any(), eq(Map.class)))
                .thenReturn(Map.of(
                        "events", List.of(Map.of(
                                "cursor", 7L,
                                "senderId", "node-a:19192",
                                "role", "user",
                                "content", "需要处理的消息")),
                        "headCursor", 7L));
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setContextStateFilePath(temporaryDirectory.resolve("failed-context-state.json").toString());
        RemoteSessionContextStateStore stateStore = new RemoteSessionContextStateStore(properties, new ObjectMapper());
        RemoteSessionContextSynchronizer synchronizer = new RemoteSessionContextSynchronizer(
                restTemplate, securityService, stateStore);

        AiChatRequest firstRequest = request("session-failed");
        RemoteSessionContextSynchronizer.SyncState syncState = synchronizer.synchronize(firstRequest, "node-b:19193");
        assertEquals(false, firstRequest.getMetadata().get("resumeModelSession"));

        AiChatResponse failedResponse = new AiChatResponse("403 insufficient balance", "FAILED", "trace-failed");
        failedResponse.setMetadata(new LinkedHashMap<>(Map.of(
                RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA, true)));
        synchronizer.recordExecution(syncState, failedResponse);

        assertTrue(stateStore.modelSessionStarted("session-failed"));
        assertFalse(stateStore.initialized("session-failed"));
        assertEquals(0L, stateStore.cursor("session-failed"));
        assertFalse(failedResponse.getMetadata().containsKey(
                RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA));

        AiChatRequest retryRequest = request("session-failed");
        synchronizer.synchronize(retryRequest, "node-b:19193");
        assertEquals(true, retryRequest.getMetadata().get("resumeModelSession"));
    }

    @Test
    void startedModelSessionStateSurvivesRelayRestart() {
        Path statePath = temporaryDirectory.resolve("persistent-context-state.json");
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setContextStateFilePath(statePath.toString());
        RemoteSessionContextStateStore stateStore = new RemoteSessionContextStateStore(properties, new ObjectMapper());
        stateStore.modelSessionId("session-persisted", "node-b:19193");
        stateStore.markModelSessionStarted("session-persisted");

        RemoteSessionContextStateStore reloaded = new RemoteSessionContextStateStore(properties, new ObjectMapper());

        assertTrue(reloaded.modelSessionStarted("session-persisted"));
        assertFalse(reloaded.initialized("session-persisted"));
        assertEquals(0L, reloaded.cursor("session-persisted"));
    }

    private AiChatRequest request(String sessionId) {
        AiChatRequest request = new AiChatRequest();
        request.setMessages(List.of(new AiChatMessage("user", "fallback")));
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", sessionId);
        metadata.put("centerContextDeltaEndpoint", "http://center/context/delta");
        metadata.put("contextHeadCursor", 7L);
        metadata.put("relayGrant", relayGrant());
        request.setMetadata(metadata);
        return request;
    }

    private Map<String, Object> relayGrant() {
        return Map.of(
                "grantId", "grant-1",
                "sessionId", "session-1",
                "sourceNodeId", "node-source:19191",
                "targetNodeId", "node-b:19193",
                "signedToken", "signed-token",
                "expiresAt", "9999999999999",
                "allowedCapabilities", List.of("A2A_TASK_CREATE"));
    }
}
