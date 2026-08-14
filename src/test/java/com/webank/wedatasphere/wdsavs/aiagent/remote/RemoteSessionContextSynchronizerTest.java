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
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
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
import org.mockito.ArgumentCaptor;

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

    @Test
    void unifiedChangeRotatesSessionReplaysContextAndResumesAfterFailedCreation() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RelayRequestSecurityService securityService = mock(RelayRequestSecurityService.class);
        when(securityService.sign(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(restTemplate.postForObject(eq("http://center/context/delta"), any(), eq(Map.class)))
                .thenReturn(Map.of(
                        "events", List.of(Map.of(
                                "cursor", 7L,
                                "senderId", "node-a:19192",
                                "role", "assistant",
                                "content", "共享结论")),
                        "headCursor", 7L));
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setContextStateFilePath(temporaryDirectory.resolve("rotated-context-state.json").toString());
        properties.setPromptMetadataPath(temporaryDirectory.resolve("rotated-prompts.json").toString());
        Path promptPath = temporaryDirectory.resolve("unified.md");
        Files.writeString(promptPath, "新的统一规则", StandardCharsets.UTF_8);
        RelayPromptMetadata metadata = new RelayPromptMetadata();
        metadata.setPromptId("shared");
        metadata.setType("UNIFIED");
        metadata.setOrder(1);
        String promptSha256 = fileSha256(promptPath);
        metadata.setCenterSha256(promptSha256);
        metadata.setInstalledSha256(promptSha256);
        metadata.setStatus("INSTALLED");
        metadata.setContentPath(promptPath.toString());
        RelayPromptCatalogState catalogState = new RelayPromptCatalogState();
        catalogState.setCatalogSha256("new-revision");
        catalogState.setPrompts(List.of(metadata));
        new RelayPromptMetadataStore(properties).save(catalogState);
        PromptSnapshot snapshot = new PromptSnapshotProvider(properties).snapshot();

        RemoteSessionContextStateStore stateStore = new RemoteSessionContextStateStore(properties, new ObjectMapper());
        String oldModelSessionId = stateStore.modelSessionId("session-rotate", "node-b:19193");
        stateStore.markApplied("session-rotate", 7L, oldModelSessionId, "old-revision", "old-digest");
        RemoteSessionContextSynchronizer synchronizer = new RemoteSessionContextSynchronizer(
                restTemplate, securityService, stateStore, new PromptSnapshotProvider(properties));

        AiChatRequest first = request("session-rotate");
        RemoteSessionContextSynchronizer.SyncState firstSync = synchronizer.synchronize(first, "node-b:19193");
        String rotatedModelSessionId = String.valueOf(first.getMetadata().get("modelSessionId"));
        assertFalse(oldModelSessionId.equals(rotatedModelSessionId));
        assertEquals(false, first.getMetadata().get("resumeModelSession"));
        assertEquals(snapshot.getRevision(), ((PromptSnapshot) first.getMetadata().get("ccrelayPromptSnapshot")).getRevision());

        ArgumentCaptor<com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextDeltaRequest> delta =
                ArgumentCaptor.forClass(com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextDeltaRequest.class);
        org.mockito.Mockito.verify(restTemplate).postForObject(eq("http://center/context/delta"), delta.capture(), eq(Map.class));
        assertEquals(0L, delta.getValue().getAfterCursor());

        AiChatResponse failed = new AiChatResponse("temporary failure", "FAILED", "trace-failed");
        failed.setMetadata(new LinkedHashMap<>(Map.of(
                RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA, true)));
        synchronizer.recordExecution(firstSync, failed);

        AiChatRequest retry = request("session-rotate");
        synchronizer.synchronize(retry, "node-b:19193");
        assertEquals(rotatedModelSessionId, retry.getMetadata().get("modelSessionId"));
        assertEquals(true, retry.getMetadata().get("resumeModelSession"));
        assertEquals("old-digest", stateStore.appliedUnifiedDigest("session-rotate"));
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

    private String fileSha256(Path path) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
