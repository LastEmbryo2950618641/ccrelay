package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEventEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskView;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessDecisionResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.CapabilityCode;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskEventRepository;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcCommandRunner;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayProperties;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayServer;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aTaskRelayServerMultiNodeLocalTest {

    private final List<RemoteCcRelayServer> relayServers = new ArrayList<>();
    private final List<HttpServer> httpServers = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        for (RemoteCcRelayServer server : relayServers) {
            if (server != null) {
                server.stop();
            }
        }
        relayServers.clear();
        for (HttpServer server : httpServers) {
            if (server != null) {
                server.stop(0);
            }
        }
        httpServers.clear();
    }

    @Test
    void supportsDirectRelayTaskCreateGetCancelAcrossDifferentPorts() throws Exception {
        int centerPort = freePort();
        int targetPort = freePort();
        AtomicReference<RelayGrantValidateResponse> decision = new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        String centerGrantValidateEndpoint = startGrantValidationServer(centerPort, decision);
        String targetRelayEndpoint = startRelayServer(targetPort, "task relay answer", "trace-task-relay", 5000L);

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-task-relay-1";
        String sourceNodeId = "node-source-local:" + centerPort;
        String targetNodeId = "node-target-local:" + targetPort;
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);

        String createGrantId = "grant-task-relay-create";
        String getGrantId = "grant-task-relay-get";
        String cancelGrantId = "grant-task-relay-cancel";
        String createToken = tokenService.sign(createGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_CREATE.name()), expiresAt);
        String getToken = tokenService.sign(getGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_GET.name()), expiresAt);
        String cancelToken = tokenService.sign(cancelGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_CANCEL.name()), expiresAt);

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        when(relayGrantService.getGrant(createGrantId)).thenReturn(grantView(createGrantId, expiresAt));
        when(relayGrantService.getGrant(getGrantId)).thenReturn(grantView(getGrantId, expiresAt));
        when(relayGrantService.getGrant(cancelGrantId)).thenReturn(grantView(cancelGrantId, expiresAt));
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));

        A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                mock(AiTaskLifecycleService.class),
                mock(AiTaskEventService.class),
                relayGrantService,
                mock(AiRelayRegistryService.class),
                mock(AiAuditService.class),
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        String taskId = "relay-task-1";
        A2aTaskCreateRequest createRequest = new A2aTaskCreateRequest();
        createRequest.setId("rpc-task-relay-create");
        createRequest.setJsonrpc("2.0");
        createRequest.setMethod("tasks/create");
        createRequest.setParams(Map.of(
                "sessionId", sessionId,
                "taskId", taskId,
                "idempotencyKey", taskId,
                "grantId", createGrantId,
                "signedToken", createToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "long running task"))
        ));

        A2aTaskCreateResponse createResponse = service.createTask(createRequest);
        assertEquals(taskId, createResponse.getResult().get("taskId"));
        assertEquals(sessionId, createResponse.getResult().get("sessionId"));
        assertEquals(taskId, createResponse.getResult().get("requestId"));
        String agentRunId = String.valueOf(createResponse.getResult().get("agentRunId"));
        assertNotNull(agentRunId);
        assertTrue(List.of("PENDING", "RUNNING", "SUCCESS").contains(String.valueOf(createResponse.getResult().get("status"))));
        assertEquals(true, createResponse.getResult().get("accepted"));

        Map<String, Object> getContext = Map.of(
                "sessionId", sessionId,
                "grantId", getGrantId,
                "signedToken", getToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint
        );
        Object getResult = service.getTask(taskId, getContext);
        assertNotNull(getResult);
        @SuppressWarnings("unchecked")
        Map<String, Object> taskView = (Map<String, Object>) getResult;
        assertEquals(taskId, taskView.get("taskId"));
        assertTrue(List.of("PENDING", "RUNNING", "SUCCESS").contains(String.valueOf(taskView.get("status"))));

        Map<String, Object> cancelContext = Map.of(
                "sessionId", sessionId,
                "grantId", cancelGrantId,
                "signedToken", cancelToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint,
                "reason", "user cancelled"
        );
        Object cancelResult = service.cancelTask(taskId, cancelContext);
        assertNotNull(cancelResult);
        @SuppressWarnings("unchecked")
        Map<String, Object> cancelView = (Map<String, Object>) cancelResult;
        assertEquals(taskId, cancelView.get("taskId"));
        assertEquals("CANCELLED", cancelView.get("status"));
        assertEquals(true, cancelView.get("accepted"));
    }

    @Test
    void rejectsDirectRelayTaskGetAfterCenterGrantRevocationAcrossDifferentPorts() throws Exception {
        int centerPort = freePort();
        int targetPort = freePort();
        AtomicReference<RelayGrantValidateResponse> decision = new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        String centerGrantValidateEndpoint = startGrantValidationServer(centerPort, decision);
        String targetRelayEndpoint = startRelayServer(targetPort, "task relay answer", "trace-task-relay", 100L);

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-task-relay-revoke";
        String sourceNodeId = "node-source-local:" + centerPort;
        String targetNodeId = "node-target-local:" + targetPort;
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);

        String createGrantId = "grant-task-relay-create-revoke";
        String getGrantId = "grant-task-relay-get-revoke";
        String createToken = tokenService.sign(createGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_CREATE.name()), expiresAt);
        String getToken = tokenService.sign(getGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_GET.name()), expiresAt);

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        when(relayGrantService.getGrant(createGrantId)).thenReturn(grantView(createGrantId, expiresAt));
        when(relayGrantService.getGrant(getGrantId)).thenReturn(grantView(getGrantId, expiresAt));
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));

        A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                mock(AiTaskLifecycleService.class),
                mock(AiTaskEventService.class),
                relayGrantService,
                mock(AiRelayRegistryService.class),
                mock(AiAuditService.class),
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        String taskId = "relay-task-revoke-1";
        A2aTaskCreateRequest createRequest = new A2aTaskCreateRequest();
        createRequest.setId("rpc-task-relay-revoke-create");
        createRequest.setJsonrpc("2.0");
        createRequest.setMethod("tasks/create");
        createRequest.setParams(Map.of(
                "sessionId", sessionId,
                "taskId", taskId,
                "idempotencyKey", taskId,
                "grantId", createGrantId,
                "signedToken", createToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "short task"))
        ));

        A2aTaskCreateResponse createResponse = service.createTask(createRequest);
        assertEquals(taskId, createResponse.getResult().get("taskId"));
        assertEquals(sessionId, createResponse.getResult().get("sessionId"));
        assertEquals(taskId, createResponse.getResult().get("requestId"));

        Map<String, Object> getContext = Map.of(
                "sessionId", sessionId,
                "grantId", getGrantId,
                "signedToken", getToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint
        );
        Object firstGet = service.getTask(taskId, getContext);
        assertNotNull(firstGet);

        decision.set(new RelayGrantValidateResponse(false, "REVOKED", "Grant is not active"));

        assertThrows(HttpServerErrorException.class, () -> service.getTask(taskId, getContext));
    }

    @Test
    void streamsDirectRelayTaskEventsAcrossDifferentPorts() throws Exception {
        int centerPort = freePort();
        int targetPort = freePort();
        AtomicReference<RelayGrantValidateResponse> decision = new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        String centerGrantValidateEndpoint = startGrantValidationServer(centerPort, decision);
        String targetRelayEndpoint = startRelayServer(targetPort, "task relay answer", "trace-task-events", 150L);

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-task-events";
        String sourceNodeId = "node-source-local:" + centerPort;
        String targetNodeId = "node-target-local:" + targetPort;
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);
        String createGrantId = "grant-task-events-create";
        String getGrantId = "grant-task-events-get";
        String createToken = tokenService.sign(createGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_CREATE.name()), expiresAt);
        String getToken = tokenService.sign(getGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_GET.name()), expiresAt);

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        when(relayGrantService.getGrant(createGrantId)).thenReturn(grantView(createGrantId, expiresAt));
        when(relayGrantService.getGrant(getGrantId)).thenReturn(grantView(getGrantId, expiresAt));
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));

        A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                mock(AiTaskLifecycleService.class),
                mock(AiTaskEventService.class),
                relayGrantService,
                mock(AiRelayRegistryService.class),
                mock(AiAuditService.class),
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        String taskId = "relay-task-events-1";
        A2aTaskCreateRequest createRequest = new A2aTaskCreateRequest();
        createRequest.setId("rpc-task-events-create");
        createRequest.setJsonrpc("2.0");
        createRequest.setMethod("tasks/create");
        createRequest.setParams(Map.of(
                "sessionId", sessionId,
                "taskId", taskId,
                "idempotencyKey", taskId,
                "grantId", createGrantId,
                "signedToken", createToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "stream my events"))
        ));
        service.createTask(createRequest);

        Map<String, Object> eventContext = Map.of(
                "sessionId", sessionId,
                "grantId", getGrantId,
                "signedToken", getToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint
        );
        ResponseEntity<StreamingResponseBody> response = service.streamTaskEvents(taskId, eventContext);
        String body = readStream(response);

        assertTrue(body.contains("TASK_ACCEPTED"));
        assertTrue(body.contains("TASK_RUNNING"));
        assertTrue(body.contains("TASK_SUCCEEDED"));
        assertTrue(body.contains("\"status\":\"SUCCESS\""));
    }

    @Test
    void rejectsDirectRelayTaskEventsAfterCenterGrantRevocationAcrossDifferentPorts() throws Exception {
        int centerPort = freePort();
        int targetPort = freePort();
        AtomicReference<RelayGrantValidateResponse> decision = new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        String centerGrantValidateEndpoint = startGrantValidationServer(centerPort, decision);
        String targetRelayEndpoint = startRelayServer(targetPort, "task relay answer", "trace-task-events-revoke", 50L);

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-task-events-revoke";
        String sourceNodeId = "node-source-local:" + centerPort;
        String targetNodeId = "node-target-local:" + targetPort;
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);
        String createGrantId = "grant-task-events-revoke-create";
        String getGrantId = "grant-task-events-revoke-get";
        String createToken = tokenService.sign(createGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_CREATE.name()), expiresAt);
        String getToken = tokenService.sign(getGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_GET.name()), expiresAt);

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        when(relayGrantService.getGrant(createGrantId)).thenReturn(grantView(createGrantId, expiresAt));
        when(relayGrantService.getGrant(getGrantId)).thenReturn(grantView(getGrantId, expiresAt));
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));

        A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                mock(AiTaskLifecycleService.class),
                mock(AiTaskEventService.class),
                relayGrantService,
                mock(AiRelayRegistryService.class),
                mock(AiAuditService.class),
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        String taskId = "relay-task-events-revoke-1";
        A2aTaskCreateRequest createRequest = new A2aTaskCreateRequest();
        createRequest.setId("rpc-task-events-revoke-create");
        createRequest.setJsonrpc("2.0");
        createRequest.setMethod("tasks/create");
        createRequest.setParams(Map.of(
                "sessionId", sessionId,
                "taskId", taskId,
                "idempotencyKey", taskId,
                "grantId", createGrantId,
                "signedToken", createToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "stream revoke"))
        ));
        service.createTask(createRequest);

        decision.set(new RelayGrantValidateResponse(false, "REVOKED", "Grant is not active"));

        Map<String, Object> eventContext = Map.of(
                "sessionId", sessionId,
                "grantId", getGrantId,
                "signedToken", getToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint
        );
        ResponseEntity<StreamingResponseBody> response = service.streamTaskEvents(taskId, eventContext);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> readStream(response));
        assertTrue(error.getMessage().contains("Grant is not active"));
    }

    @Test
    void terminatesTaskEventStreamWhenGrantRevokedDuringStreamingAcrossDifferentPorts() throws Exception {
        int centerPort = freePort();
        int targetPort = freePort();
        AtomicReference<RelayGrantValidateResponse> decision = new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        String centerGrantValidateEndpoint = startGrantValidationServer(centerPort, decision);
        String targetRelayEndpoint = startRelayServer(targetPort, "task relay answer", "trace-task-events-mid-revoke", 1500L);

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-task-events-mid-revoke";
        String sourceNodeId = "node-source-local:" + centerPort;
        String targetNodeId = "node-target-local:" + targetPort;
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);
        String createGrantId = "grant-task-events-mid-revoke-create";
        String getGrantId = "grant-task-events-mid-revoke-get";
        String createToken = tokenService.sign(createGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_CREATE.name()), expiresAt);
        String getToken = tokenService.sign(getGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_GET.name()), expiresAt);

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        when(relayGrantService.getGrant(createGrantId)).thenReturn(grantView(createGrantId, expiresAt));
        when(relayGrantService.getGrant(getGrantId)).thenReturn(grantView(getGrantId, expiresAt));
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));

        A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                mock(AiTaskLifecycleService.class),
                mock(AiTaskEventService.class),
                relayGrantService,
                mock(AiRelayRegistryService.class),
                mock(AiAuditService.class),
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        String taskId = "relay-task-events-mid-revoke-1";
        A2aTaskCreateRequest createRequest = new A2aTaskCreateRequest();
        createRequest.setId("rpc-task-events-mid-revoke-create");
        createRequest.setJsonrpc("2.0");
        createRequest.setMethod("tasks/create");
        createRequest.setParams(Map.of(
                "sessionId", sessionId,
                "taskId", taskId,
                "idempotencyKey", taskId,
                "grantId", createGrantId,
                "signedToken", createToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "stream until revoked"))
        ));
        service.createTask(createRequest);

        Map<String, Object> eventContext = Map.of(
                "sessionId", sessionId,
                "grantId", getGrantId,
                "signedToken", getToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint
        );
        ResponseEntity<StreamingResponseBody> response = service.streamTaskEvents(taskId, eventContext);
        Thread revoker = new Thread(() -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            decision.set(new RelayGrantValidateResponse(false, "REVOKED", "Grant is not active"));
        });
        revoker.start();

        String body = readStream(response);
        revoker.join();

        assertTrue(body.contains("TASK_AUTH_REVOKED"));
        assertTrue(body.contains("GRANT_REVOKED"));
        assertTrue(body.contains("Grant is not active") || body.contains("relayGrant center validation failed"));
        assertTrue(!body.contains("TASK_SUCCEEDED"));
    }

    @Test
    void propagatesStructuredTaskResultAndSanitizesLargeArtifactsAcrossDifferentPorts() throws Exception {
        int centerPort = freePort();
        int targetPort = freePort();
        AtomicReference<RelayGrantValidateResponse> decision = new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        String centerGrantValidateEndpoint = startGrantValidationServer(centerPort, decision);

        AiChatResponse structuredResponse = new AiChatResponse("structured task answer", "SUCCESS", "trace-task-structured");
        structuredResponse.setSummary("structured task summary");
        structuredResponse.setArtifacts(List.of(Map.of(
                "name", "big.txt",
                "sizeBytes", 20,
                "content", "01234567890123456789",
                "fileRef", Map.of("path", "/tmp/big.txt")
        )));
        structuredResponse.setDiagnostics(Map.of("tokens", 88));
        structuredResponse.setMetadata(Map.of("provider", "remote-cc"));
        String targetRelayEndpoint = startRelayServer(targetPort, structuredResponse, 50L);

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-task-structured";
        String sourceNodeId = "node-source-local:" + centerPort;
        String targetNodeId = "node-target-local:" + targetPort;
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);
        String createGrantId = "grant-task-structured-create";
        String getGrantId = "grant-task-structured-get";
        String createToken = tokenService.sign(createGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_CREATE.name()), expiresAt);
        String getToken = tokenService.sign(getGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_GET.name()), expiresAt);

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        when(relayGrantService.getGrant(createGrantId)).thenReturn(grantView(createGrantId, expiresAt));
        when(relayGrantService.getGrant(getGrantId)).thenReturn(grantView(getGrantId, expiresAt));
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));

        AiAgentRunService agentRunService = mock(AiAgentRunService.class);
        when(agentRunService.startRun(any(), eq("relay-task-structured-1"), eq(sessionId), eq(targetNodeId), eq("AGENT")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                mock(AiTaskLifecycleService.class),
                mock(AiTaskEventService.class),
                relayGrantService,
                mock(AiRelayRegistryService.class),
                mock(AiAuditService.class),
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(10L),
                false
        );
        service.setAgentRunService(agentRunService);

        String taskId = "relay-task-structured-1";
        A2aTaskCreateRequest createRequest = new A2aTaskCreateRequest();
        createRequest.setId("rpc-task-structured-create");
        createRequest.setJsonrpc("2.0");
        createRequest.setMethod("tasks/create");
        createRequest.setParams(Map.of(
                "sessionId", sessionId,
                "taskId", taskId,
                "idempotencyKey", taskId,
                "grantId", createGrantId,
                "signedToken", createToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "structured task"))
        ));
        A2aTaskCreateResponse createResponse = service.createTask(createRequest);
        assertEquals(taskId, createResponse.getResult().get("taskId"));
        assertEquals(sessionId, createResponse.getResult().get("sessionId"));
        assertEquals(taskId, createResponse.getResult().get("requestId"));

        Map<String, Object> getContext = Map.of(
                "sessionId", sessionId,
                "grantId", getGrantId,
                "signedToken", getToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint
        );
        ResponseEntity<StreamingResponseBody> streamResponse = service.streamTaskEvents(taskId, getContext);
        String body = readStream(streamResponse);
        assertTrue(body.contains("TASK_SUCCEEDED"));
        assertTrue(body.contains("\"requestId\":\"" + taskId + "\""));
        String agentRunId = String.valueOf(createResponse.getResult().get("agentRunId"));
        assertNotNull(agentRunId);
        assertTrue(body.contains("\"agentRunId\":\"" + agentRunId + "\""));
        assertTrue(body.contains("inlineContentOmitted"));
        assertTrue(!body.contains("01234567890123456789"));

        Object getResult = service.getTask(taskId, getContext);
        assertNotNull(getResult);
        @SuppressWarnings("unchecked")
        Map<String, Object> taskView = (Map<String, Object>) getResult;
        assertEquals("SUCCESS", taskView.get("status"));
        assertEquals(taskId, taskView.get("requestId"));
        assertEquals(sessionId, taskView.get("sessionId"));
        assertEquals(agentRunId, taskView.get("agentRunId"));
        assertEquals("structured task answer", taskView.get("answer"));
        assertEquals("structured task summary", taskView.get("summary"));
        assertEquals(Map.of("tokens", 88), taskView.get("diagnostics"));
        assertEquals(Map.of("provider", "remote-cc"), taskView.get("metadata"));
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) ((List<?>) taskView.get("artifacts")).get(0);
        assertEquals(true, artifact.get("inlineContentOmitted"));
        assertEquals(true, artifact.get("referenceOnly"));
        assertTrue(!artifact.containsKey("content"));
        verify(agentRunService, atLeastOnce()).completeRun(eq(agentRunId), any());
    }

    @Test
    void backfillsParentTaskForDirectRelayGetCancelAndStreamAcrossDifferentPorts() throws Exception {
        int centerPort = freePort();
        int targetPort = freePort();
        AtomicReference<RelayGrantValidateResponse> decision = new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        String centerGrantValidateEndpoint = startGrantValidationServer(centerPort, decision);
        String targetRelayEndpoint = startRelayServer(targetPort, "task relay parent sync", "trace-task-parent-sync", 100L);

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-task-parent-sync";
        String sourceNodeId = "node-source-local:" + centerPort;
        String targetNodeId = "node-target-local:" + targetPort;
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);
        String sharedGrantId = "grant-task-parent-sync-all";
        String sharedToken = tokenService.sign(sharedGrantId, sessionId, sourceNodeId, targetNodeId,
                List.of(CapabilityCode.A2A_TASK_CREATE.name(), CapabilityCode.A2A_TASK_GET.name(), CapabilityCode.A2A_TASK_CANCEL.name()), expiresAt);

        Map<String, AiTaskEntity> taskStore = new ConcurrentHashMap<>();
        List<AiTaskEventEntity> eventStore = new CopyOnWriteArrayList<>();
        String parentTaskId = "parent-task-parent-sync-1";
        Map<String, Object> parentResult = new LinkedHashMap<>();
        parentResult.put("accessGrantId", sharedGrantId);
        parentResult.put("accessTargetRelayEndpoint", targetRelayEndpoint);
        AiTaskEntity parentTask = new AiTaskEntity();
        parentTask.setTaskId(parentTaskId);
        parentTask.setSessionId(sessionId);
        parentTask.setStatus("PENDING");
        parentTask.setCurrentStage("ACCESS_GRANTED");
        parentTask.setResultJson(objectMapper.writeValueAsString(parentResult));
        parentTask.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        taskStore.put(parentTaskId, parentTask);

        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        when(taskRepository.findByTaskId(any())).thenAnswer(invocation -> Optional.ofNullable(taskStore.get(invocation.getArgument(0))));
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            AiTaskEntity entity = invocation.getArgument(0);
            taskStore.put(entity.getTaskId(), entity);
            return entity;
        });

        AiTaskEventRepository taskEventRepository = mock(AiTaskEventRepository.class);
        when(taskEventRepository.save(any())).thenAnswer(invocation -> {
            AiTaskEventEntity entity = invocation.getArgument(0);
            eventStore.add(entity);
            return entity;
        });
        when(taskEventRepository.findByTaskIdOrderBySequenceNoAsc(any())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            return eventStore.stream()
                    .filter(event -> taskId.equals(event.getTaskId()))
                    .sorted(Comparator.comparing(AiTaskEventEntity::getSequenceNo))
                    .toList();
        });
        AiTaskEventService taskEventService = new AiTaskEventServiceImpl(taskEventRepository);

        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        when(taskLifecycleService.getTask(any())).thenAnswer(invocation -> toTaskView(taskStore.get(invocation.getArgument(0))));

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        RelayGrantView grantView = grantView(sharedGrantId, expiresAt);
        grantView.setSourceNodeId(sourceNodeId);
        grantView.setTargetNodeId(targetNodeId);
        grantView.setAllowedCapabilities(List.of(CapabilityCode.A2A_TASK_CREATE.name(), CapabilityCode.A2A_TASK_GET.name(), CapabilityCode.A2A_TASK_CANCEL.name()));
        when(relayGrantService.getGrant(sharedGrantId)).thenReturn(grantView);
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        RelayAccessDecisionResponse activatedGrant = new RelayAccessDecisionResponse();
        activatedGrant.setDecision("ALLOW");
        activatedGrant.setGrantId(sharedGrantId);
        activatedGrant.setSignedToken(sharedToken);
        activatedGrant.setTargetRelayEndpoint(targetRelayEndpoint);
        activatedGrant.setAllowedCapabilities(List.of(CapabilityCode.A2A_TASK_CREATE.name(), CapabilityCode.A2A_TASK_GET.name(), CapabilityCode.A2A_TASK_CANCEL.name()));
        when(relayGrantService.activateGrantIfReady(sharedGrantId)).thenReturn(activatedGrant);

        AiAuditService auditService = mock(AiAuditService.class);
        when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> "audit-parent-sync-" + invocation.getArgument(1));

        A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                taskLifecycleService,
                taskEventService,
                relayGrantService,
                mock(AiRelayRegistryService.class),
                auditService,
                taskRepository,
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        String taskId = "relay-task-parent-sync-1";
        A2aTaskCreateRequest createRequest = new A2aTaskCreateRequest();
        createRequest.setId("rpc-task-parent-sync-create");
        createRequest.setJsonrpc("2.0");
        createRequest.setMethod("tasks/create");
        Map<String, Object> createParams = new LinkedHashMap<>();
        createParams.put("sessionId", sessionId);
        createParams.put("taskId", taskId);
        createParams.put("idempotencyKey", taskId);
        createParams.put("grantId", sharedGrantId);
        createParams.put("signedToken", sharedToken);
        createParams.put("sourceNodeId", sourceNodeId);
        createParams.put("targetNodeId", targetNodeId);
        createParams.put("targetRelayEndpoint", targetRelayEndpoint);
        createParams.put("centerGrantValidateEndpoint", centerGrantValidateEndpoint);
        createParams.put("parentTaskId", parentTaskId);
        createParams.put("messages", List.of(Map.of("role", "user", "content", "parent task sync")));
        createRequest.setParams(createParams);

        A2aTaskCreateResponse createResponse = service.createTask(createRequest);
        assertEquals(taskId, createResponse.getResult().get("taskId"));
        assertTrue(taskEventService.listEvents(parentTaskId).stream()
                .anyMatch(event -> "REMOTE_A2A_TASK_CREATED".equals(event.getEventType())));

        Map<String, Object> parentContext = Map.of(
                "sessionId", sessionId,
                "parentTaskId", parentTaskId
        );
        Object getResult = service.getTask(taskId, parentContext);
        assertNotNull(getResult);
        @SuppressWarnings("unchecked")
        Map<String, Object> getView = (Map<String, Object>) getResult;
        assertEquals(taskId, getView.get("taskId"));

        AiTaskView parentAfterGet = toTaskView(taskStore.get(parentTaskId));
        assertEquals("REMOTE_A2A_TASK_GET", parentAfterGet.getCurrentStage());
        assertEquals(taskId, parentAfterGet.getResult().get("latestA2aTaskId"));
        assertTrue(List.of("PENDING", "RUNNING", "SUCCESS").contains(String.valueOf(parentAfterGet.getResult().get("latestA2aStatus"))));
        assertTrue(taskEventService.listEvents(parentTaskId).stream()
                .anyMatch(event -> "REMOTE_A2A_TASK_GET".equals(event.getEventType())));

        ResponseEntity<StreamingResponseBody> streamResponse = service.streamTaskEvents(taskId, parentContext);
        String streamBody = readStream(streamResponse);
        assertTrue(streamBody.contains("TASK_RUNNING"));
        assertTrue(streamBody.contains("TASK_SUCCEEDED"));
        assertTrue(taskEventService.listEvents(parentTaskId).stream()
                .anyMatch(event -> "REMOTE_A2A_TASK_STREAM_OPENED".equals(event.getEventType())));

        Object cancelResult = service.cancelTask(taskId, Map.of(
                "sessionId", sessionId,
                "parentTaskId", parentTaskId,
                "reason", "cancel from parent sync test"
        ));
        assertNotNull(cancelResult);
        @SuppressWarnings("unchecked")
        Map<String, Object> cancelView = (Map<String, Object>) cancelResult;
        assertEquals(taskId, cancelView.get("taskId"));
        assertEquals("CANCELLED", cancelView.get("status"));

        AiTaskView parentAfterCancel = toTaskView(taskStore.get(parentTaskId));
        assertEquals("CANCELLED", parentAfterCancel.getStatus());
        assertEquals("REMOTE_A2A_TASK_CANCELLED", parentAfterCancel.getCurrentStage());
        assertEquals("CANCELLED", parentAfterCancel.getResult().get("latestA2aStatus"));
        assertTrue(taskEventService.listEvents(parentTaskId).stream()
                .anyMatch(event -> "REMOTE_A2A_TASK_CANCELLED".equals(event.getEventType())));
    }

    private AiTaskView toTaskView(AiTaskEntity entity) throws Exception {
        if (entity == null) {
            return null;
        }
        AiTaskView view = new AiTaskView();
        view.setTaskId(entity.getTaskId());
        view.setSessionId(entity.getSessionId());
        view.setParentTaskId(entity.getParentTaskId());
        view.setRequestId(entity.getRequestId());
        view.setTaskType(entity.getTaskType());
        view.setStatus(entity.getStatus());
        view.setCurrentStage(entity.getCurrentStage());
        view.setSourceNodeId(entity.getSourceNodeId());
        view.setTargetNodeId(entity.getTargetNodeId());
        view.setErrorCode(entity.getErrorCode());
        view.setErrorMessage(entity.getErrorMessage());
        view.setStartTime(entity.getStartTime());
        view.setEndTime(entity.getEndTime());
        if (entity.getResultJson() != null && !entity.getResultJson().trim().isEmpty()) {
            view.setResult(objectMapper.readValue(entity.getResultJson(), objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)));
        }
        return view;
    }

    private String readStream(ResponseEntity<StreamingResponseBody> response) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        response.getBody().writeTo(outputStream);
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private RelayGrantView grantView(String grantId, String expiresAt) {
        RelayGrantView view = new RelayGrantView();
        view.setGrantId(grantId);
        view.setExpiresAt(expiresAt);
        return view;
    }

    private String startRelayServer(int port, String answer, String traceId, long delayMs) throws Exception {
        return startRelayServer(port, new AiChatResponse(answer, "SUCCESS", traceId), delayMs);
    }

    private String startRelayServer(int port, AiChatResponse response, long delayMs) throws Exception {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(port);
        properties.setPath("/api/ai/remote-cc/chat");
        properties.setAllowedWorkRoots(List.of("*"));
        properties.setA2aLargeFileThresholdBytes(10L);
        RemoteCcCommandRunner runner = request -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("task interrupted", e);
                }
            }
            return response;
        };
        RemoteCcRelayServer server = new RemoteCcRelayServer(properties, new RemoteCcRelayService(properties, runner));
        server.start();
        relayServers.add(server);
        return "http://127.0.0.1:" + port + properties.getPath();
    }

    private String startGrantValidationServer(int port, AtomicReference<RelayGrantValidateResponse> responseRef) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/ai/relay/access/validate", exchange -> {
            byte[] bytes = objectMapper.writeValueAsBytes(responseRef.get());
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        });
        server.start();
        httpServers.add(server);
        return "http://127.0.0.1:" + port + "/api/ai/relay/access/validate";
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}








