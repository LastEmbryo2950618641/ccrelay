package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
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

class ReactAgentRunnerP3ControlTest {

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
    void injectIsStoredAndConsumedBeforeNextStep() throws Exception {
        String relayBase = startRelayServer(freePort());
        RestTemplate restTemplate = new RestTemplate();
        String taskId = "task-control-inject";

        restTemplate.postForObject(relayBase + "/api/ai/a2a/tasks/create", taskRequest(taskId, Map.of("maxSteps", 3), List.of(
                Map.of("type", "NOOP", "waitMs", 500),
                Map.of("type", "FINISH", "answer", "finished after injection")
        )), Object.class);

        AiChatResponse controlResponse = restTemplate.postForObject(relayBase + "/api/ai/remote-cc/chat",
                controlRequest(taskId, Map.of("type", "INTERRUPT", "taskId", taskId, "prompt", "focus on latest logs")),
                AiChatResponse.class);
        assertNotNull(controlResponse);
        assertEquals("SUCCESS", controlResponse.getStatus());

        Map<?, ?> view = waitForStatus(restTemplate, relayBase, taskId, "SUCCESS");
        assertEquals("finished after injection", view.get("answer"));
        String events = readEvents(relayBase, taskId);
        assertTrue(events.contains("CONTROL_INJECTED"));
        assertTrue(events.contains("CONTROL_INJECTION_CONSUMED"));
        assertTrue(events.contains("focus on latest logs"));
    }

    @Test
    void adjustIsAppliedBeforeNextStep() throws Exception {
        String relayBase = startRelayServer(freePort());
        RestTemplate restTemplate = new RestTemplate();
        String taskId = "task-control-adjust";

        restTemplate.postForObject(relayBase + "/api/ai/a2a/tasks/create", taskRequest(taskId, Map.of("maxSteps", 3), List.of(
                Map.of("type", "NOOP", "waitMs", 500),
                Map.of("type", "REPORT", "summary", "second step"),
                Map.of("type", "FINISH", "answer", "should not finish")
        )), Object.class);

        AiChatResponse controlResponse = restTemplate.postForObject(relayBase + "/api/ai/remote-cc/chat",
                controlRequest(taskId, Map.of("type", "ADJUST", "taskId", taskId, "react", Map.of("maxSteps", 1))),
                AiChatResponse.class);
        assertNotNull(controlResponse);
        assertEquals("SUCCESS", controlResponse.getStatus());

        Map<?, ?> view = waitForStatus(restTemplate, relayBase, taskId, "FAILED");
        assertEquals("ReAct maxSteps exceeded", view.get("answer"));
        String events = readEvents(relayBase, taskId);
        assertTrue(events.contains("CONTROL_ADJUSTED"));
        assertTrue(events.contains("CONTROL_ADJUSTMENT_CONSUMED"));
        assertTrue(events.contains("REACT_MAX_STEPS_EXCEEDED"));
    }

    @Test
    void stopCancelsRunningTask() throws Exception {
        String relayBase = startRelayServer(freePort());
        RestTemplate restTemplate = new RestTemplate();
        String taskId = "task-control-stop";

        restTemplate.postForObject(relayBase + "/api/ai/a2a/tasks/create", taskRequest(taskId, Map.of("maxSteps", 2), List.of(
                Map.of("type", "NOOP", "waitMs", 5000),
                Map.of("type", "FINISH", "answer", "should not finish")
        )), Object.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> cancelResult = restTemplate.postForObject(relayBase + "/api/ai/a2a/tasks/" + taskId + "/cancel",
                Map.of("reason", "test stop"), Map.class);
        assertNotNull(cancelResult);
        assertEquals("CANCELLED", cancelResult.get("status"));

        Map<?, ?> view = waitForStatus(restTemplate, relayBase, taskId, "CANCELLED");
        assertEquals("CANCELLED", view.get("status"));
        String events = readEvents(relayBase, taskId);
        assertTrue(events.contains("TASK_CANCELLED"));
        assertTrue(events.contains("test stop") || events.contains("CANCELLED"));
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
                "messages", List.of(Map.of("role", "user", "content", "run controlled react task"))
        ));
        return request;
    }

    private AiChatRequest controlRequest(String taskId, Map<String, Object> control) {
        AiChatRequest request = new AiChatRequest();
        request.setMetadata(Map.of(
                "taskId", taskId,
                "control", control
        ));
        return request;
    }

    private Map<?, ?> waitForStatus(RestTemplate restTemplate, String relayBase, String taskId, String expectedStatus) throws Exception {
        Map<?, ?> latest = null;
        for (int i = 0; i < 80; i++) {
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
                if (line.contains("TASK_SUCCEEDED") || line.contains("TASK_FAILED") || line.contains("TASK_CANCELLED")) {
                    break;
                }
            }
            return builder.toString();
        } finally {
            connection.disconnect();
        }
    }

    private String startRelayServer(int port) throws Exception {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(port);
        properties.setPath("/api/ai/remote-cc/chat");
        properties.setAllowedWorkRoots(List.of("*"));
        RemoteCcRelayServer server = new RemoteCcRelayServer(
                properties,
                new RemoteCcRelayService(properties, request -> new AiChatResponse("fallback", "SUCCESS", "trace-fallback"))
        );
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
