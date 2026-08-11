package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskView;
import com.webank.wedatasphere.wdsavs.aiagent.model.CapabilityCode;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aTaskStatusFallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fallsBackToStatusQueryWhenRemoteSseEndpointIsUnavailable() throws Exception {
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        AiAuditService auditService = mock(AiAuditService.class);

        int validatePort = freePort();
        AtomicReference<RelayGrantValidateResponse> decision = new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        HttpServer validationServer = startGrantValidationServer(validatePort, decision);
        try {
            String sessionId = "session-sse-fallback";
            String taskId = "task-sse-fallback-1";
            String grantId = "grant-sse-fallback-1";
            String expiresAt = String.valueOf(System.currentTimeMillis() + 60_000L);

            RelayGrantView grantView = new RelayGrantView();
            grantView.setGrantId(grantId);
            grantView.setExpiresAt(expiresAt);
            grantView.setAllowedCapabilities(List.of(CapabilityCode.A2A_TASK_GET.name()));
            when(relayGrantService.getGrant(grantId)).thenReturn(grantView);
            when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
            when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-1");

            AiTaskView localTask = new AiTaskView();
            localTask.setTaskId(taskId);
            localTask.setSessionId(sessionId);
            localTask.setStatus("SUCCESS");
            localTask.setCurrentStage("TASK_SUCCEEDED");
            localTask.setResult(Map.of("answer", "fallback answer"));
            when(taskLifecycleService.getTask(taskId)).thenReturn(localTask);
            when(taskEventService.streamEvents(taskId)).thenReturn(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(outputStream -> outputStream.write(("event: TASK_SUCCEEDED\n" +
                            "data: {\"taskId\":\"" + taskId + "\",\"status\":\"SUCCESS\"}\n\n" +
                            ": stream-end\n\n").getBytes(StandardCharsets.UTF_8))));

            A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                    taskLifecycleService,
                    taskEventService,
                    relayGrantService,
                    mock(AiRelayRegistryService.class),
                    auditService,
                    new RestTemplate(),
                    new A2aPayloadPolicyServiceImpl(1024L),
                    true
            );

            Map<String, Object> context = Map.of(
                    "sessionId", sessionId,
                    "grantId", grantId,
                    "signedToken", "signed-token-1",
                    "sourceNodeId", "source-node:18091",
                    "targetNodeId", "target-node:19091",
                    "targetRelayEndpoint", "http://127.0.0.1:19091/api/ai/remote-cc/chat",
                    "targetGrantValidateEndpoint", "http://127.0.0.1:" + validatePort + "/api/ai/relay/access/validate",
                    "targetTaskEventsEndpoint", "http://127.0.0.1:1/api/ai/a2a/tasks/{taskId}/events",
                    "targetTaskDetailEndpoint", "http://127.0.0.1:1/api/ai/a2a/tasks/{taskId}",
                    "allowCenterForwardFallback", true
            );

            ResponseEntity<StreamingResponseBody> streamResponse = service.streamTaskEvents(taskId, context);
            assertThrows(Exception.class, () -> readStream(streamResponse));

            Object getResult = service.getTask(taskId, context);
            assertNotNull(getResult);
            AiTaskView taskView = (AiTaskView) getResult;
            assertEquals(taskId, taskView.getTaskId());
            assertEquals("SUCCESS", taskView.getStatus());
            assertEquals("fallback answer", taskView.getResult().get("answer"));

            verify(taskLifecycleService).getTask(eq(taskId));
        } finally {
            validationServer.stop(0);
        }
    }

    @Test
    void persistsRemoteSuccessOnCenterShadowAndDoesNotLeaveItTimeoutEligible() throws Exception {
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        AiTaskEventService taskEventService = mock(AiTaskEventService.class);
        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        AiAuditService auditService = mock(AiAuditService.class);
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);

        int validatePort = freePort();
        HttpServer validationServer = startGrantValidationServer(validatePort,
                new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok")));
        try {
            String sessionId = "session-success-sync";
            String taskId = "task-success-sync-1";
            String grantId = "grant-success-sync-1";
            String expiresAt = String.valueOf(System.currentTimeMillis() + 60_000L);

            RelayGrantView grantView = new RelayGrantView();
            grantView.setGrantId(grantId);
            grantView.setExpiresAt(expiresAt);
            grantView.setAllowedCapabilities(List.of(CapabilityCode.A2A_TASK_GET.name()));
            when(relayGrantService.getGrant(grantId)).thenReturn(grantView);
            when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
            when(auditService.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("audit-success");
            when(taskEventService.listEvents(taskId)).thenReturn(List.of());

            AiTaskView remoteResult = new AiTaskView();
            remoteResult.setTaskId(taskId);
            remoteResult.setSessionId(sessionId);
            remoteResult.setStatus("SUCCESS");
            remoteResult.setCurrentStage("TASK_SUCCEEDED");
            remoteResult.setResult(Map.of("answer", "persisted answer"));
            when(taskLifecycleService.getTask(taskId)).thenReturn(remoteResult);

            AiTaskEntity parent = new AiTaskEntity();
            parent.setTaskId(taskId);
            parent.setSessionId(sessionId);
            parent.setStatus("PARTIAL_SUCCESS");
            parent.setCurrentStage("REMOTE_A2A_TASK_CREATED");
            parent.setStartTime(String.valueOf(System.currentTimeMillis() - 3_600_000L));
            when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(parent));

            A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                    taskLifecycleService,
                    taskEventService,
                    relayGrantService,
                    mock(AiRelayRegistryService.class),
                    auditService,
                    taskRepository,
                    new RestTemplate(),
                    new A2aPayloadPolicyServiceImpl(1024L),
                    true
            );

            Object result = service.getTask(taskId, Map.of(
                    "sessionId", sessionId,
                    "parentTaskId", taskId,
                    "grantId", grantId,
                    "signedToken", "signed-token-success",
                    "sourceNodeId", "source-node:18091",
                    "targetNodeId", "target-node:19091",
                    "targetRelayEndpoint", "http://127.0.0.1:19091/api/ai/remote-cc/chat",
                    "targetGrantValidateEndpoint", "http://127.0.0.1:" + validatePort + "/api/ai/relay/access/validate",
                    "targetTaskDetailEndpoint", "http://127.0.0.1:1/api/ai/a2a/tasks/{taskId}",
                    "allowCenterForwardFallback", true
            ));

            assertEquals("SUCCESS", ((AiTaskView) result).getStatus());
            assertEquals("SUCCESS", parent.getStatus());
            assertEquals("TASK_SUCCEEDED", parent.getCurrentStage());
            assertNotNull(parent.getEndTime());
            assertTrue(parent.getResultJson().contains("persisted answer"));
            verify(taskRepository).save(parent);
        } finally {
            validationServer.stop(0);
        }
    }

    private String readStream(ResponseEntity<StreamingResponseBody> response) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        response.getBody().writeTo(outputStream);
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private HttpServer startGrantValidationServer(int port, AtomicReference<RelayGrantValidateResponse> responseRef) throws Exception {
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
        return server;
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}



