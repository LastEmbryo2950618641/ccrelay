package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactAgentRunnerP2Test {

    private final List<RemoteCcRelayServer> relayServers = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void managedRunnerExecutesAllowedCommandAndEmitsAuditEvents() throws Exception {
        String relayBase = startRelayServer(freePort(), request -> new AiChatResponse("fallback should not be used", "FAILED", "trace-unused"));
        RestTemplate restTemplate = new RestTemplate();

        A2aTaskCreateRequest request = taskRequest("task-java-runner-allowed", Map.of(
                "maxSteps", 2,
                "commandWhitelist", List.of("java"),
                "stepTimeoutMs", 30000
        ), List.of(
                Map.of("type", "RUN_COMMAND", "command", "java", "args", List.of("--version")),
                Map.of("type", "FINISH", "answer", "command evidence collected")
        ));

        A2aTaskCreateResponse createResponse = restTemplate.postForObject(relayBase + "/api/ai/a2a/tasks/create", request, A2aTaskCreateResponse.class);
        assertNotNull(createResponse);
        @SuppressWarnings("unchecked")
        Map<String, Object> createReact = (Map<String, Object>) createResponse.getResult().get("react");
        assertEquals("JAVA_ENFORCED_REACT", createReact.get("enforcementMode"));

        Map<?, ?> view = waitForStatus(restTemplate, relayBase, "task-java-runner-allowed", "SUCCESS");
        assertEquals("SUCCESS", view.get("status"));
        assertEquals("command evidence collected", view.get("answer"));
        @SuppressWarnings("unchecked")
        Map<String, Object> viewReact = (Map<String, Object>) view.get("react");
        assertEquals("JAVA_REACT_RUNNER_ENABLED", viewReact.get("enforcementStatus"));

        String events = readEvents(relayBase, "task-java-runner-allowed");
        assertTrue(events.contains("ACTION_APPROVED"));
        assertTrue(events.contains("COMMAND_FINISHED"));
        assertTrue(events.contains("TASK_FINISHED"));
    }

    @Test
    void relayHealthUsesStandardUpStatus() throws Exception {
        String relayBase = startRelayServer(
                freePort(),
                request -> new AiChatResponse("ok", "SUCCESS", "trace-health")
        );
        RestTemplate restTemplate = new RestTemplate();

        Map<?, ?> health = restTemplate.getForObject(relayBase + "/health", Map.class);

        assertNotNull(health);
        assertEquals("UP", health.get("status"));
        assertEquals("REMOTE_CC_RELAY", health.get("component"));
    }

    @Test
    void relayHealthDoesNotImplyAiReadiness() throws Exception {
        String relayBase = startRelayServer(freePort(), request -> new AiChatResponse("ok", "SUCCESS", "trace-health"));
        Map<?, ?> readiness = new RestTemplate().getForObject(relayBase + "/ai-readiness", Map.class);

        assertNotNull(readiness);
        assertEquals("RELAY_READY_AI_UNAVAILABLE", readiness.get("status"));
        assertEquals(false, readiness.get("configReady"));
    }

    @Test
    void relayReportsAiReadinessWhenModelAndCredentialAreConfigured() throws Exception {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(freePort());
        properties.setModel("test-model");
        properties.setBaseUrl("https://example.invalid/anthropic");
        properties.setApiKeyConfigured(true);
        RemoteCcRelayServer server = new RemoteCcRelayServer(
                properties, new RemoteCcRelayService(properties, request -> new AiChatResponse("ok", "SUCCESS", "trace-ready")));
        server.start();
        relayServers.add(server);

        Map<?, ?> readiness = new RestTemplate().getForObject(
                "http://127.0.0.1:" + properties.getPort() + "/ai-readiness", Map.class);

        assertEquals("READY", readiness.get("status"));
        assertEquals(true, readiness.get("configReady"));
    }

    @Test
    void managedRunnerRejectsCommandOutsideWhitelist() throws Exception {
        String relayBase = startRelayServer(freePort(), request -> new AiChatResponse("fallback should not be used", "FAILED", "trace-unused"));
        RestTemplate restTemplate = new RestTemplate();

        A2aTaskCreateRequest request = taskRequest("task-java-runner-rejected", Map.of(
                "maxSteps", 1,
                "commandWhitelist", List.of("java"),
                "stepTimeoutMs", 30000
        ), List.of(
                Map.of("type", "RUN_COMMAND", "command", "not-allowed-command", "args", List.of("--version"))
        ));

        restTemplate.postForObject(relayBase + "/api/ai/a2a/tasks/create", request, A2aTaskCreateResponse.class);

        Map<?, ?> view = waitForStatus(restTemplate, relayBase, "task-java-runner-rejected", "FAILED");
        assertEquals("FAILED", view.get("status"));
        assertTrue(String.valueOf(view.get("answer")).contains("not allowed"));

        String events = readEvents(relayBase, "task-java-runner-rejected");
        assertTrue(events.contains("ACTION_REJECTED"));
        assertTrue(events.contains("Command is not allowed"));
    }

    @Test
    void managedRunnerFailsWhenMaxStepsExceeded() throws Exception {
        String relayBase = startRelayServer(freePort(), request -> new AiChatResponse("fallback should not be used", "FAILED", "trace-unused"));
        RestTemplate restTemplate = new RestTemplate();

        A2aTaskCreateRequest request = taskRequest("task-java-runner-maxsteps", Map.of(
                "maxSteps", 1,
                "commandWhitelist", List.of("java")
        ), List.of(
                Map.of("type", "REPORT", "summary", "first step"),
                Map.of("type", "FINISH", "answer", "should not finish")
        ));

        restTemplate.postForObject(relayBase + "/api/ai/a2a/tasks/create", request, A2aTaskCreateResponse.class);

        Map<?, ?> view = waitForStatus(restTemplate, relayBase, "task-java-runner-maxsteps", "FAILED");
        assertEquals("FAILED", view.get("status"));
        assertEquals("ReAct maxSteps exceeded", view.get("answer"));

        String events = readEvents(relayBase, "task-java-runner-maxsteps");
        assertTrue(events.contains("REACT_MAX_STEPS_EXCEEDED"));
    }

    private A2aTaskCreateRequest taskRequest(String taskId, Map<String, Object> react, List<Map<String, Object>> actions) {
        A2aTaskCreateRequest request = new A2aTaskCreateRequest();
        request.setId("rpc-" + taskId);
        request.setJsonrpc("2.0");
        request.setMethod("tasks/create");
        request.setParams(Map.of(
                "sessionId", "session-" + taskId,
                "taskId", taskId,
                "idempotencyKey", taskId,
                "executionMode", "ReAct",
                "react", react,
                "actions", actions,
                "messages", List.of(Map.of("role", "user", "content", "run managed react task"))
        ));
        return request;
    }

    private Map<?, ?> waitForStatus(RestTemplate restTemplate, String relayBase, String taskId, String expectedStatus) throws Exception {
        Map<?, ?> latest = null;
        for (int i = 0; i < 60; i++) {
            latest = restTemplate.getForObject(relayBase + "/api/ai/a2a/tasks/" + taskId, Map.class);
            if (latest != null && expectedStatus.equals(latest.get("status"))) {
                return latest;
            }
            Thread.sleep(100L);
        }
        return latest;
    }

    private String readEvents(String relayBase, String taskId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(relayBase + "/api/ai/a2a/tasks/" + taskId + "/events").openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "text/event-stream");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
                if (line.contains("TASK_SUCCEEDED") || line.contains("TASK_FAILED")) {
                    break;
                }
            }
            return builder.toString();
        } finally {
            connection.disconnect();
        }
    }

    private String startRelayServer(int port, RemoteCcCommandRunner runner) throws Exception {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(port);
        properties.setPath("/api/ai/remote-cc/chat");
        properties.setAllowedWorkRoots(List.of("*"));
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
