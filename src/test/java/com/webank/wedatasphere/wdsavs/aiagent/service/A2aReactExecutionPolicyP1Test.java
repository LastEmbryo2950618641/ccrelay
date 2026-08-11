package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcCommandRunner;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayProperties;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayServer;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class A2aReactExecutionPolicyP1Test {

    private final List<RemoteCcRelayServer> relayServers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (RemoteCcRelayServer server : relayServers) {
            if (server != null) {
                server.stop();
            }
        }
        relayServers.clear();
    }

    @Test
    void messageSendNormalizesReactPolicyIntoChatMetadataAndResponse() {
        AtomicReference<AiChatRequest> capturedRequest = new AtomicReference<>();
        A2aAgentService service = new A2aAgentService(
                request -> {
                    capturedRequest.set(request);
                    return new AiChatResponse("ok", "SUCCESS", "trace-react-message");
                },
                new AiCapabilityCatalogService(),
                null,
                null,
                null,
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        A2aJsonRpcRequest request = new A2aJsonRpcRequest();
        request.setId("rpc-react-message");
        request.setJsonrpc("2.0");
        request.setMethod("message/send");
        request.setParams(Map.of(
                "sessionId", "session-react-message",
                "executionMode", "ReAct",
                "react", Map.of(
                        "maxSteps", 7,
                        "commandWhitelist", List.of("ls", "grep"),
                        "stepTimeoutMs", 30000,
                        "auditLevel", "SUMMARY",
                        "allowAi", false
                ),
                "messages", List.of(Map.of("role", "user", "content", "inspect"))
        ));

        A2aJsonRpcResponse response = service.handle(request);

        assertNotNull(response.getResult());
        assertEquals("ReAct", response.getResult().get("executionMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resultReact = (Map<String, Object>) response.getResult().get("react");
        assertEquals(7, resultReact.get("maxSteps"));
        assertEquals("SUMMARY", resultReact.get("auditLevel"));
        assertEquals("WEAK_PROMPT", resultReact.get("enforcementMode"));

        AiChatRequest chatRequest = capturedRequest.get();
        assertNotNull(chatRequest);
        assertEquals("ReAct", chatRequest.getMetadata().get("executionMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadataReact = (Map<String, Object>) chatRequest.getMetadata().get("react");
        assertEquals(List.of("ls", "grep"), metadataReact.get("commandWhitelist"));
        assertEquals(false, metadataReact.get("allowAi"));
    }

    @Test
    void localTaskCreatePersistsAndReturnsReactPolicySummary() {
        AiTaskLifecycleService taskLifecycleService = mock(AiTaskLifecycleService.class);
        when(taskLifecycleService.createTask(any())).thenReturn(
                new AiTaskCreateResponse("task-react-local", "PENDING", true, "session-react-task:idem-react", "trace-react-task", null)
        );
        A2aTaskServiceImpl service = new A2aTaskServiceImpl(
                taskLifecycleService,
                mock(AiTaskEventService.class),
                mock(AiRelayGrantService.class),
                mock(AiRelayRegistryService.class),
                null,
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        A2aTaskCreateRequest request = new A2aTaskCreateRequest();
        request.setId("rpc-react-task");
        request.setJsonrpc("2.0");
        request.setMethod("tasks/create");
        request.setParams(Map.of(
                "sessionId", "session-react-task",
                "idempotencyKey", "idem-react",
                "taskType", "A2A_TASK",
                "executionMode", "ReAct",
                "react", Map.of(
                        "maxSteps", 9,
                        "commandWhitelist", "tail,grep",
                        "taskTimeoutMs", 120000,
                        "allowAi", true
                ),
                "messages", List.of(Map.of("role", "user", "content", "inspect"))
        ));

        A2aTaskCreateResponse response = service.createTask(request);

        assertEquals("ReAct", response.getResult().get("executionMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resultReact = (Map<String, Object>) response.getResult().get("react");
        assertEquals(9, resultReact.get("maxSteps"));
        assertEquals(List.of("tail", "grep"), resultReact.get("commandWhitelist"));
        assertEquals(120000L, resultReact.get("taskTimeoutMs"));

        ArgumentCaptor<AiTaskCreateRequest> captor = ArgumentCaptor.forClass(AiTaskCreateRequest.class);
        org.mockito.Mockito.verify(taskLifecycleService).createTask(captor.capture());
        Map<String, Object> payload = captor.getValue().getPayload();
        assertEquals("ReAct", payload.get("executionMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadReact = (Map<String, Object>) payload.get("react");
        assertEquals("WEAK_PROMPT", payloadReact.get("enforcementMode"));
        assertEquals("JAVA_REACT_RUNNER_NOT_ENABLED", payloadReact.get("enforcementStatus"));
    }

    @Test
    void remoteRelayTaskCreateAndGetExposeReactPolicySummary() throws Exception {
        String relayBase = startRelayServer(freePort());
        RestTemplate restTemplate = new RestTemplate();

        A2aTaskCreateRequest request = new A2aTaskCreateRequest();
        request.setId("rpc-remote-react");
        request.setJsonrpc("2.0");
        request.setMethod("tasks/create");
        request.setParams(Map.of(
                "sessionId", "session-remote-react",
                "taskId", "task-remote-react",
                "idempotencyKey", "task-remote-react",
                "executionMode", "ReAct",
                "react", Map.of("maxSteps", 5, "stepTimeoutMs", 15000),
                "messages", List.of(Map.of("role", "user", "content", "remote inspect"))
        ));

        A2aTaskCreateResponse response = restTemplate.postForObject(relayBase + "/api/ai/a2a/tasks/create", request, A2aTaskCreateResponse.class);

        assertNotNull(response);
        assertEquals("task-remote-react", response.getResult().get("taskId"));
        assertEquals("ReAct", response.getResult().get("executionMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> createReact = (Map<String, Object>) response.getResult().get("react");
        assertEquals(5, createReact.get("maxSteps"));
        assertEquals(15000L, ((Number) createReact.get("stepTimeoutMs")).longValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> view = restTemplate.getForObject(relayBase + "/api/ai/a2a/tasks/task-remote-react", Map.class);
        assertNotNull(view);
        assertEquals("ReAct", view.get("executionMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> viewReact = (Map<String, Object>) view.get("react");
        assertEquals("JAVA_ENFORCED_REACT", viewReact.get("enforcementMode"));
    }

    private String startRelayServer(int port) throws Exception {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(port);
        properties.setPath("/api/ai/remote-cc/chat");
        properties.setAllowedWorkRoots(List.of("*"));
        RemoteCcCommandRunner runner = request -> new AiChatResponse("remote ok", "SUCCESS", "trace-remote-react");
        RemoteCcRelayServer server = new RemoteCcRelayServer(properties, new RemoteCcRelayService(properties, runner));
        server.start();
        relayServers.add(server);
        return "http://127.0.0.1:" + port;
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
