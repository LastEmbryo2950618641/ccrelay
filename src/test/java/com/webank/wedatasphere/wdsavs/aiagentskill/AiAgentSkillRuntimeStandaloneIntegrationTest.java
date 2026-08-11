package com.webank.wedatasphere.wdsavs.aiagentskill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCancelRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiSessionContextAppendRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.CapabilityCode;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayAccessRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayHeartbeatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayRegisterRequest;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcCommandRunner;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcExecutionRequest;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayProperties;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayServer;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayService;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiTaskRepository;
import com.webank.wedatasphere.wdsavs.aiagentskill.model.SessionOpenRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiAgentSkillRuntimeStandaloneIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static Path runtimeDir;
    private static Path sqliteFile;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AiTaskRepository taskRepository;


    private final List<RemoteCcRelayServer> relayServers = new ArrayList<>();

    @BeforeAll
    static void initRuntimeDir() throws Exception {
        runtimeDir = Files.createTempDirectory("ccrelay-it-");
        sqliteFile = runtimeDir.resolve("skill-runtime.db");
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + sqliteFile.toAbsolutePath());
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("wdsavs.ai.task.recover-on-startup", () -> "false");
        registry.add("wdsavs.ai.relay.node-whitelist-enabled", () -> "true");
    }

    @AfterEach
    void tearDown() {
        for (RemoteCcRelayServer relayServer : relayServers) {
            if (relayServer != null) {
                relayServer.stop();
            }
        }
        relayServers.clear();
    }

    @Test
    void supportsStandaloneSkillRuntimeA2aFlowAndShadowPersistence() throws Exception {
        int targetPort = freePort();
        List<RemoteCcExecutionRequest> executionRequests = new CopyOnWriteArrayList<>();
        String targetRelayEndpoint = startRelayServer(
                targetPort, "remote relay answer", "trace-skill-a2a", 5000L, executionRequests);
        String targetNodeId = "127.0.0.1:" + targetPort;
        String sourceNodeId = "skill-local:" + port;
        String centerValidateEndpoint = baseUrl("/api/skill/relay/access/validate");

        RelayRegisterRequest registerRequest = new RelayRegisterRequest();
        registerRequest.setHost("127.0.0.1");
        registerRequest.setPort(targetPort);
        registerRequest.setRelayEndpoint(targetRelayEndpoint);
        registerRequest.setVersion("1.0.0");
        registerRequest.setProtocolVersion("1.0");
        registerRequest.setWorkspaceRoot(runtimeDir.toString());
        registerRequest.setCapabilities(List.of(
                CapabilityCode.A2A_MESSAGE_SEND.name(),
                CapabilityCode.A2A_TASK_CREATE.name(),
                CapabilityCode.A2A_TASK_GET.name(),
                CapabilityCode.A2A_TASK_CANCEL.name(),
                CapabilityCode.DEPLOY_RELAY.name()));

        ResponseEntity<Map> registerResponse = restTemplate.postForEntity(baseUrl("/api/skill/relay/register"), registerRequest, Map.class);
        assertEquals(HttpStatus.OK, registerResponse.getStatusCode());
        assertEquals(targetNodeId, registerResponse.getBody().get("nodeId"));
        assertEquals(Boolean.TRUE, registerResponse.getBody().get("accepted"));

        RelayHeartbeatRequest heartbeatRequest = new RelayHeartbeatRequest();
        heartbeatRequest.setNodeId(targetNodeId);
        heartbeatRequest.setStatus("AVAILABLE");
        heartbeatRequest.setActiveSessions(1);
        heartbeatRequest.setCpuLoad(BigDecimal.valueOf(0.12D));
        heartbeatRequest.setMemoryUsage(1024L * 1024L);
        heartbeatRequest.setLastTaskTime(String.valueOf(System.currentTimeMillis()));
        heartbeatRequest.setDetail(Map.of("agent", "remote-relay"));

        ResponseEntity<Map> heartbeatResponse = restTemplate.postForEntity(baseUrl("/api/skill/relay/heartbeat"), heartbeatRequest, Map.class);
        assertEquals(HttpStatus.OK, heartbeatResponse.getStatusCode());
        assertEquals(targetNodeId, heartbeatResponse.getBody().get("nodeId"));
        assertEquals("AVAILABLE", heartbeatResponse.getBody().get("nodeStatus"));

        ResponseEntity<Map> scanResponse = restTemplate.getForEntity(baseUrl("/api/skill/relay/heartbeat/scan"), Map.class);
        assertEquals(HttpStatus.OK, scanResponse.getStatusCode());
        assertTrue(((Number) scanResponse.getBody().get("availableCount")).intValue() >= 1);

        String sessionId = openSession(sourceNodeId);
        assertNotNull(sessionId);

        RelayAccessRequest accessRequest = new RelayAccessRequest();
        accessRequest.setSessionId(sessionId);
        accessRequest.setRequestId("grant-req-" + System.nanoTime());
        accessRequest.setSourceNodeId(sourceNodeId);
        accessRequest.setTargetNodeId(targetNodeId);
        accessRequest.setReason("integration test");
        accessRequest.setTtlMs(60_000L);
        accessRequest.setRequiredCapabilities(List.of(
                CapabilityCode.A2A_MESSAGE_SEND.name(),
                CapabilityCode.A2A_TASK_CREATE.name(),
                CapabilityCode.A2A_TASK_GET.name(),
                CapabilityCode.A2A_TASK_CANCEL.name()));

        ResponseEntity<Map> accessResponse = restTemplate.postForEntity(baseUrl("/api/skill/relay/access/request"), accessRequest, Map.class);
        assertEquals(HttpStatus.OK, accessResponse.getStatusCode());
        assertEquals("ALLOW", accessResponse.getBody().get("decision"));
        String grantId = String.valueOf(accessResponse.getBody().get("grantId"));
        String signedToken = String.valueOf(accessResponse.getBody().get("signedToken"));
        assertFalse(grantId.isBlank());
        assertFalse(signedToken.isBlank());
        assertEquals(targetRelayEndpoint, accessResponse.getBody().get("targetRelayEndpoint"));

        ResponseEntity<Map> grantResponse = restTemplate.getForEntity(baseUrl("/api/skill/relay/access/" + grantId), Map.class);
        assertEquals(HttpStatus.OK, grantResponse.getStatusCode());
        assertEquals(grantId, grantResponse.getBody().get("grantId"));
        assertEquals(sessionId, grantResponse.getBody().get("sessionId"));

        A2aJsonRpcRequest messageRequest = new A2aJsonRpcRequest();
        messageRequest.setJsonrpc("2.0");
        messageRequest.setId("rpc-message-1");
        messageRequest.setMethod("message/send");
        messageRequest.setParams(Map.of(
                "sessionId", sessionId,
                "grantId", grantId,
                "signedToken", signedToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "hello remote relay"))
        ));

        ResponseEntity<Map> messageResponse = restTemplate.postForEntity(baseUrl("/api/skill/a2a/message/send"), messageRequest, Map.class);
        assertEquals(HttpStatus.OK, messageResponse.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> messageResult = (Map<String, Object>) messageResponse.getBody().get("result");
        assertEquals("remote relay answer", messageResult.get("answer"));
        assertEquals(targetNodeId, messageResult.get("targetNodeId"));

        assertEquals(1, executionRequests.size());
        RemoteCcExecutionRequest firstExecution = executionRequests.get(0);
        assertNotNull(firstExecution.getModelSessionId());
        assertFalse(firstExecution.getModelSessionId().isBlank());
        assertFalse(firstExecution.isResumeModelSession());
        assertTrue(firstExecution.getPrompt().contains("hello remote relay"));

        A2aJsonRpcRequest followUpRequest = new A2aJsonRpcRequest();
        followUpRequest.setJsonrpc("2.0");
        followUpRequest.setId("rpc-message-2");
        followUpRequest.setMethod("message/send");
        followUpRequest.setParams(Map.of(
                "sessionId", sessionId,
                "grantId", grantId,
                "signedToken", signedToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "inspect the next symptom"))
        ));

        ResponseEntity<Map> followUpResponse = restTemplate.postForEntity(
                baseUrl("/api/skill/a2a/message/send"), followUpRequest, Map.class);
        assertEquals(HttpStatus.OK, followUpResponse.getStatusCode());
        assertEquals(2, executionRequests.size());
        RemoteCcExecutionRequest secondExecution = executionRequests.get(1);
        assertEquals(firstExecution.getModelSessionId(), secondExecution.getModelSessionId());
        assertTrue(secondExecution.isResumeModelSession());
        assertTrue(secondExecution.getPrompt().contains("inspect the next symptom"));
        assertFalse(secondExecution.getPrompt().contains("remote relay answer"));

        ResponseEntity<Map> contextResponse = restTemplate.getForEntity(
                baseUrl("/api/skill/session/" + sessionId + "/context/delta?afterCursor=0"), Map.class);
        assertEquals(HttpStatus.OK, contextResponse.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contextEvents = (List<Map<String, Object>>) contextResponse.getBody().get("events");
        assertEquals(List.of("system", "user", "assistant", "user", "assistant"), contextEvents.stream()
                .map(event -> String.valueOf(event.get("role")))
                .toList());
        assertEquals("SESSION_CONTROL", contextEvents.get(0).get("contentType"));
        assertTrue(String.valueOf(contextEvents.get(0).get("content")).contains("SESSION_COLLABORATION_INITIALIZED"));
        assertEquals(List.of(
                        "hello remote relay",
                        "remote relay answer",
                        "inspect the next symptom",
                        "remote relay answer"),
                contextEvents.stream().skip(1).map(event -> String.valueOf(event.get("content"))).toList());

        String taskId = "a2a-shadow-" + System.nanoTime();
        A2aTaskCreateRequest createRequest = new A2aTaskCreateRequest();
        createRequest.setJsonrpc("2.0");
        createRequest.setId("rpc-task-create-1");
        createRequest.setMethod("tasks/create");
        createRequest.setParams(new LinkedHashMap<>(Map.of(
                "sessionId", sessionId,
                "taskId", taskId,
                "idempotencyKey", taskId,
                "grantId", grantId,
                "signedToken", signedToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "run long task"))
        )));

        ResponseEntity<Map> createResponse = restTemplate.postForEntity(baseUrl("/api/skill/a2a/tasks/create"), createRequest, Map.class);
        assertEquals(HttpStatus.OK, createResponse.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> createResult = (Map<String, Object>) createResponse.getBody().get("result");
        assertEquals(taskId, createResult.get("taskId"));
        assertEquals(sessionId, createResult.get("sessionId"));
        assertEquals(Boolean.TRUE, createResult.get("accepted"));

        String requestOnlyTaskId = "a2a-request-only-" + System.nanoTime();
        A2aTaskCreateRequest requestIdOnlyCreateRequest = new A2aTaskCreateRequest();
        requestIdOnlyCreateRequest.setJsonrpc("2.0");
        requestIdOnlyCreateRequest.setId("rpc-task-create-request-only-1");
        requestIdOnlyCreateRequest.setMethod("tasks/create");
        requestIdOnlyCreateRequest.setParams(new LinkedHashMap<>(Map.of(
                "sessionId", sessionId,
                "taskId", requestOnlyTaskId,
                "requestId", requestOnlyTaskId,
                "grantId", grantId,
                "signedToken", signedToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "run request-id-only task"))
        )));

        ResponseEntity<Map> requestIdOnlyCreateResponse = restTemplate.postForEntity(baseUrl("/api/skill/a2a/tasks/create"), requestIdOnlyCreateRequest, Map.class);
        assertEquals(HttpStatus.OK, requestIdOnlyCreateResponse.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> requestIdOnlyCreateResult = (Map<String, Object>) requestIdOnlyCreateResponse.getBody().get("result");
        assertEquals(requestOnlyTaskId, requestIdOnlyCreateResult.get("taskId"));
        assertEquals(sessionId, requestIdOnlyCreateResult.get("sessionId"));
        assertEquals(Boolean.TRUE, requestIdOnlyCreateResult.get("accepted"));

        Optional<AiTaskEntity> shadowTask = awaitTask(taskId, 5000L);
        assertTrue(shadowTask.isPresent());
        assertEquals(sessionId, shadowTask.get().getSessionId());
        assertEquals("A2A_TASK", shadowTask.get().getTaskType());

        ResponseEntity<Map> a2aGetResponse = restTemplate.getForEntity(buildUrl("/api/skill/a2a/tasks/" + taskId, Map.of(
                "sessionId", sessionId,
                "grantId", grantId,
                "signedToken", signedToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerValidateEndpoint
        )), Map.class);
        assertEquals(HttpStatus.OK, a2aGetResponse.getStatusCode());
        assertEquals(taskId, a2aGetResponse.getBody().get("taskId"));

        ResponseEntity<String> a2aCancelResponse = restTemplate.postForEntity(buildUrl("/api/skill/a2a/tasks/" + taskId + "/cancel", Map.of(
                "sessionId", sessionId,
                "grantId", grantId,
                "signedToken", signedToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerValidateEndpoint
        )), null, String.class);
        assertEquals(HttpStatus.OK, a2aCancelResponse.getStatusCode());
        assertNotNull(a2aCancelResponse.getBody());
        if (a2aCancelResponse.getBody().trim().startsWith("{")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cancelResult = OBJECT_MAPPER.readValue(a2aCancelResponse.getBody(), Map.class);
            assertEquals(taskId, cancelResult.get("taskId"));
            assertEquals("CANCELLED", cancelResult.get("status"));
        } else {
            assertEquals("true", a2aCancelResponse.getBody().trim());
        }

        awaitTaskStatus(taskId, "CANCELLED", 10_000L);

        ResponseEntity<Map> localShadowResponse = restTemplate.getForEntity(baseUrl("/api/skill/tasks/" + taskId), Map.class);
        assertEquals(HttpStatus.OK, localShadowResponse.getStatusCode());
        assertEquals(taskId, localShadowResponse.getBody().get("taskId"));
        assertEquals("CANCELLED", localShadowResponse.getBody().get("status"));

        ResponseEntity<List<Map<String, Object>>> localEventsResponse = restTemplate.exchange(
                baseUrl("/api/skill/tasks/" + taskId + "/events"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });
        assertEquals(HttpStatus.OK, localEventsResponse.getStatusCode());
        assertTrue(localEventsResponse.getBody().stream()
                .map(event -> String.valueOf(event.get("eventType")))
                .anyMatch(eventType -> "REMOTE_A2A_TASK_CANCELLED".equals(eventType)
                        || "CENTER_FORWARD_A2A_TASK_CANCELLED".equals(eventType)));

        ResponseEntity<String> localStreamResponse = restTemplate.getForEntity(baseUrl("/api/skill/tasks/" + taskId + "/events/stream"), String.class);
        assertEquals(HttpStatus.OK, localStreamResponse.getStatusCode());
        assertTrue(localStreamResponse.getBody().contains("REMOTE_A2A_TASK_CANCELLED")
                || localStreamResponse.getBody().contains("CENTER_FORWARD_A2A_TASK_CANCELLED"));
        assertTrue(localStreamResponse.getBody().contains(": stream-end"));
    }

    @Test
    void persistsAndMergesEnvironmentSummaryAcrossRegisterAndHeartbeat() throws Exception {
        int targetPort = freePort();
        String targetNodeId = "127.0.0.1:" + targetPort;

        RelayRegisterRequest registerRequest = new RelayRegisterRequest();
        registerRequest.setHost("127.0.0.1");
        registerRequest.setPort(targetPort);
        registerRequest.setRelayEndpoint("http://127.0.0.1:" + targetPort + "/api/ai/remote-cc/chat");
        registerRequest.setVersion("1.0.0");
        registerRequest.setProtocolVersion("1.0");
        registerRequest.setWorkspaceRoot(runtimeDir.toString());
        registerRequest.setCapabilities(List.of(CapabilityCode.A2A_MESSAGE_SEND.name()));
        registerRequest.setEnvironmentSummary(Map.of(
                "shell", "/bin/bash",
                "path", "/usr/bin:/bin",
                "availableCommands", Map.of("git", true, "python3", true),
                "javaBundled", true
        ));

        ResponseEntity<Map> registerResponse = restTemplate.postForEntity(baseUrl("/api/skill/relay/register"), registerRequest, Map.class);
        assertEquals(HttpStatus.OK, registerResponse.getStatusCode());
        assertEquals(targetNodeId, registerResponse.getBody().get("nodeId"));

        RelayHeartbeatRequest heartbeatRequest = new RelayHeartbeatRequest();
        heartbeatRequest.setNodeId(targetNodeId);
        heartbeatRequest.setStatus("AVAILABLE");
        heartbeatRequest.setEnvironmentSummary(Map.of(
                "cwd", runtimeDir.toString(),
                "availableCommands", Map.of("rg", true, "python3", false),
                "probedAt", String.valueOf(System.currentTimeMillis())
        ));
        ResponseEntity<Map> heartbeatResponse = restTemplate.postForEntity(baseUrl("/api/skill/relay/heartbeat"), heartbeatRequest, Map.class);
        assertEquals(HttpStatus.OK, heartbeatResponse.getStatusCode());
        assertEquals("AVAILABLE", heartbeatResponse.getBody().get("nodeStatus"));

        ResponseEntity<Map> nodeResponse = restTemplate.getForEntity(baseUrl("/api/skill/relay/nodes/" + targetNodeId), Map.class);
        assertEquals(HttpStatus.OK, nodeResponse.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> environmentSummary = (Map<String, Object>) nodeResponse.getBody().get("environmentSummary");
        assertEquals("/bin/bash", environmentSummary.get("shell"));
        assertEquals("/usr/bin:/bin", environmentSummary.get("path"));
        assertEquals(runtimeDir.toString(), environmentSummary.get("cwd"));
        assertEquals(true, environmentSummary.get("javaBundled"));
        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) environmentSummary.get("availableCommands");
        assertEquals(true, commands.get("git"));
        assertEquals(true, commands.get("rg"));
        assertEquals(false, commands.get("python3"));

        ResponseEntity<List<Map<String, Object>>> listResponse = restTemplate.exchange(
                baseUrl("/api/skill/relay/nodes"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });
        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertTrue(listResponse.getBody().stream()
                .map(item -> String.valueOf(item.get("nodeId")))
                .anyMatch(targetNodeId::equals));
    }

    @Test
    void keepsRegisteredNodeUnavailableUntilHeartbeatPasses() throws Exception {
        int targetPort = freePort();
        String targetNodeId = "127.0.0.1:" + targetPort;
        String sourceNodeId = "skill-local:" + port;

        RelayRegisterRequest registerRequest = new RelayRegisterRequest();
        registerRequest.setHost("127.0.0.1");
        registerRequest.setPort(targetPort);
        registerRequest.setRelayEndpoint("http://127.0.0.1:" + targetPort + "/api/a2a");
        registerRequest.setVersion("1.0.0");
        registerRequest.setProtocolVersion("1.0");
        registerRequest.setWorkspaceRoot(runtimeDir.toString());
        registerRequest.setCapabilities(List.of(CapabilityCode.A2A_MESSAGE_SEND.name()));

        ResponseEntity<Map> registerResponse = restTemplate.postForEntity(baseUrl("/api/skill/relay/register"), registerRequest, Map.class);
        assertEquals(HttpStatus.OK, registerResponse.getStatusCode());
        assertEquals(targetNodeId, registerResponse.getBody().get("nodeId"));
        assertEquals("REGISTERING", registerResponse.getBody().get("status"));

        String sessionId = openSession(sourceNodeId);
        RelayAccessRequest accessRequest = new RelayAccessRequest();
        accessRequest.setSessionId(sessionId);
        accessRequest.setRequestId("grant-waiting-deploy-" + System.nanoTime());
        accessRequest.setSourceNodeId(sourceNodeId);
        accessRequest.setTargetNodeId(targetNodeId);
        accessRequest.setReason("register only should not be ready");
        accessRequest.setRequiredCapabilities(List.of(CapabilityCode.A2A_MESSAGE_SEND.name()));

        ResponseEntity<Map> accessResponse = restTemplate.postForEntity(baseUrl("/api/skill/relay/access/request"), accessRequest, Map.class);
        assertEquals(HttpStatus.OK, accessResponse.getStatusCode());
        assertEquals("ALLOW_WITH_DEPLOY", accessResponse.getBody().get("decision"));
        assertEquals(null, accessResponse.getBody().get("signedToken"));
        assertTrue(String.valueOf(accessResponse.getBody().get("message")).contains("not yet available"));

        String grantId = String.valueOf(accessResponse.getBody().get("grantId"));
        ResponseEntity<Map> grantResponse = restTemplate.getForEntity(baseUrl("/api/skill/relay/access/" + grantId), Map.class);
        assertEquals(HttpStatus.OK, grantResponse.getStatusCode());
        assertEquals("WAITING_DEPLOY", grantResponse.getBody().get("status"));
    }

    @Test
    void requiresRegistrationAndHeartbeatBeforeGrantBecomesDirectlyUsable() throws Exception {
        int targetPort = freePort();
        String targetNodeId = "127.0.0.1:" + targetPort;
        String sourceNodeId = "skill-local:" + port;

        RelayRegisterRequest registerRequest = new RelayRegisterRequest();
        registerRequest.setHost("127.0.0.1");
        registerRequest.setPort(targetPort);
        registerRequest.setRelayEndpoint("http://127.0.0.1:" + targetPort + "/api/a2a");
        registerRequest.setVersion("1.0.0");
        registerRequest.setProtocolVersion("1.0");
        registerRequest.setWorkspaceRoot(runtimeDir.toString());
        registerRequest.setCapabilities(List.of(CapabilityCode.A2A_MESSAGE_SEND.name()));

        ResponseEntity<Map> registerResponse = restTemplate.postForEntity(baseUrl("/api/skill/relay/register"), registerRequest, Map.class);
        assertEquals(HttpStatus.OK, registerResponse.getStatusCode());

        String sessionId = openSession(sourceNodeId);
        RelayAccessRequest accessRequest = new RelayAccessRequest();
        accessRequest.setSessionId(sessionId);
        accessRequest.setRequestId("grant-activate-after-heartbeat-" + System.nanoTime());
        accessRequest.setSourceNodeId(sourceNodeId);
        accessRequest.setTargetNodeId(targetNodeId);
        accessRequest.setReason("becomes active only after health");
        accessRequest.setRequiredCapabilities(List.of(CapabilityCode.A2A_MESSAGE_SEND.name()));

        ResponseEntity<Map> firstAccess = restTemplate.postForEntity(baseUrl("/api/skill/relay/access/request"), accessRequest, Map.class);
        assertEquals(HttpStatus.OK, firstAccess.getStatusCode());
        assertEquals("ALLOW_WITH_DEPLOY", firstAccess.getBody().get("decision"));
        String grantId = String.valueOf(firstAccess.getBody().get("grantId"));

        RelayHeartbeatRequest heartbeatRequest = new RelayHeartbeatRequest();
        heartbeatRequest.setNodeId(targetNodeId);
        heartbeatRequest.setStatus("AVAILABLE");
        heartbeatRequest.setActiveSessions(1);
        heartbeatRequest.setCpuLoad(BigDecimal.valueOf(0.08D));
        heartbeatRequest.setMemoryUsage(256L * 1024L);
        heartbeatRequest.setLastTaskTime(String.valueOf(System.currentTimeMillis()));
        heartbeatRequest.setDetail(Map.of("agent", "ready-now"));
        ResponseEntity<Map> heartbeatResponse = restTemplate.postForEntity(baseUrl("/api/skill/relay/heartbeat"), heartbeatRequest, Map.class);
        assertEquals(HttpStatus.OK, heartbeatResponse.getStatusCode());
        assertEquals("AVAILABLE", heartbeatResponse.getBody().get("nodeStatus"));

        ResponseEntity<Map> secondAccess = restTemplate.postForEntity(baseUrl("/api/skill/relay/access/request"), accessRequest, Map.class);
        assertEquals(HttpStatus.OK, secondAccess.getStatusCode());
        assertEquals("ALLOW", secondAccess.getBody().get("decision"));
        assertEquals(grantId, secondAccess.getBody().get("grantId"));
        assertTrue(String.valueOf(secondAccess.getBody().get("signedToken")).length() > 10);

        ResponseEntity<Map> grantResponse = restTemplate.getForEntity(baseUrl("/api/skill/relay/access/" + grantId), Map.class);
        assertEquals(HttpStatus.OK, grantResponse.getStatusCode());
        assertEquals("ACTIVE", grantResponse.getBody().get("status"));
    }

    @Test
    void supportsStandaloneSkillRuntimeLocalTaskLifecycleEndpoints() {
        String sessionId = openSession("skill-local:" + port);
        String taskId = "local-control-" + System.nanoTime();

        AiTaskCreateRequest createRequest = new AiTaskCreateRequest();
        createRequest.setTaskId(taskId);
        createRequest.setSessionId(sessionId);
        createRequest.setRequestId("local-request-" + System.nanoTime());
        createRequest.setTaskType("LOCAL_CONTROL");
        createRequest.setPayload(Map.of("action", "health-check"));

        ResponseEntity<Map> createResponse = restTemplate.postForEntity(baseUrl("/api/skill/tasks/create"), createRequest, Map.class);
        assertEquals(HttpStatus.OK, createResponse.getStatusCode());
        assertEquals(taskId, createResponse.getBody().get("taskId"));
        assertEquals("PENDING", createResponse.getBody().get("status"));

        ResponseEntity<Map> getResponse = restTemplate.getForEntity(baseUrl("/api/skill/tasks/" + taskId), Map.class);
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        assertEquals(taskId, getResponse.getBody().get("taskId"));
        assertEquals(sessionId, getResponse.getBody().get("sessionId"));

        ResponseEntity<Map> statusResponse = restTemplate.getForEntity(baseUrl("/api/skill/tasks/" + taskId + "/status"), Map.class);
        assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
        assertEquals(taskId, statusResponse.getBody().get("taskId"));
        assertEquals("PENDING", statusResponse.getBody().get("status"));

        AiTaskCancelRequest cancelRequest = new AiTaskCancelRequest();
        cancelRequest.setReason("cancel local control task");
        ResponseEntity<Boolean> cancelResponse = restTemplate.postForEntity(baseUrl("/api/skill/tasks/" + taskId + "/cancel"), cancelRequest, Boolean.class);
        assertEquals(HttpStatus.OK, cancelResponse.getStatusCode());
        assertEquals(Boolean.TRUE, cancelResponse.getBody());

        ResponseEntity<Map> cancelledTaskResponse = restTemplate.getForEntity(baseUrl("/api/skill/tasks/" + taskId), Map.class);
        assertEquals(HttpStatus.OK, cancelledTaskResponse.getStatusCode());
        assertEquals("CANCELLED", cancelledTaskResponse.getBody().get("status"));

        ResponseEntity<List<Map<String, Object>>> eventsResponse = restTemplate.exchange(
                baseUrl("/api/skill/tasks/" + taskId + "/events"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });
        assertEquals(HttpStatus.OK, eventsResponse.getStatusCode());
        assertTrue(eventsResponse.getBody().stream()
                .map(event -> String.valueOf(event.get("eventType")))
                .anyMatch("TASK_CREATED"::equals));
        assertTrue(eventsResponse.getBody().stream()
                .map(event -> String.valueOf(event.get("eventType")))
                .anyMatch("TASK_CANCELLED"::equals));

        ResponseEntity<String> streamResponse = restTemplate.getForEntity(baseUrl("/api/skill/tasks/" + taskId + "/events/stream"), String.class);
        assertEquals(HttpStatus.OK, streamResponse.getStatusCode());
        assertTrue(streamResponse.getBody().contains("TASK_CANCELLED"));
        assertTrue(streamResponse.getBody().contains(": stream-end"));

        ResponseEntity<Map> observationResponse = restTemplate.getForEntity(
                baseUrl("/api/skill/observations/tasks/" + taskId + "?limit=5"),
                Map.class);
        assertEquals(HttpStatus.OK, observationResponse.getStatusCode());
        assertEquals(taskId, observationResponse.getBody().get("taskId"));
        assertEquals("CANCELLED", observationResponse.getBody().get("status"));

        ResponseEntity<Map> batchObservationResponse = restTemplate.getForEntity(
                baseUrl("/api/skill/observations/tasks?taskIds=" + taskId + "&limit=5"),
                Map.class);
        assertEquals(HttpStatus.OK, batchObservationResponse.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> observations = (List<Map<String, Object>>) batchObservationResponse.getBody().get("observations");
        assertEquals(1, observations.size());
        assertEquals(taskId, observations.get(0).get("taskId"));
    }

    @Test
    void persistsSessionContextAndReadsOnlyTheRequestedDelta() {
        String sessionId = openSession("context-test:" + port);
        AiSessionContextAppendRequest userEvent = new AiSessionContextAppendRequest();
        userEvent.setEventId("context-user-" + System.nanoTime());
        userEvent.setSenderType("CODEX");
        userEvent.setSenderId("context-test");
        userEvent.setRole("user");
        userEvent.setContent("请检查节点状态");

        ResponseEntity<Map> userResponse = restTemplate.postForEntity(
                baseUrl("/api/skill/session/" + sessionId + "/context/events"), userEvent, Map.class);
        assertEquals(HttpStatus.OK, userResponse.getStatusCode());
        long userCursor = ((Number) userResponse.getBody().get("cursor")).longValue();

        AiSessionContextAppendRequest assistantEvent = new AiSessionContextAppendRequest();
        assistantEvent.setEventId("context-assistant-" + System.nanoTime());
        assistantEvent.setSenderType("RELAY");
        assistantEvent.setSenderId("node-a:18192");
        assistantEvent.setRole("assistant");
        assistantEvent.setContent("节点状态正常");
        restTemplate.postForEntity(baseUrl("/api/skill/session/" + sessionId + "/context/events"), assistantEvent, Map.class);

        ResponseEntity<Map> deltaResponse = restTemplate.getForEntity(
                baseUrl("/api/skill/session/" + sessionId + "/context/delta?afterCursor=" + userCursor), Map.class);
        assertEquals(HttpStatus.OK, deltaResponse.getStatusCode());
        List<Map<String, Object>> events = (List<Map<String, Object>>) deltaResponse.getBody().get("events");
        assertEquals(1, events.size());
        assertEquals("assistant", events.get(0).get("role"));
        assertEquals("节点状态正常", events.get(0).get("content"));
    }

    private String openSession(String sourceNodeId) {
        SessionOpenRequest request = new SessionOpenRequest();
        request.setInitiatorType("SKILL");
        request.setInitiatorId("integration-test");
        request.setSourceNodeId(sourceNodeId);
        ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl("/api/skill/session/open"), request, Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return String.valueOf(response.getBody().get("sessionId"));
    }

    private Optional<AiTaskEntity> awaitTask(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<AiTaskEntity> task = taskRepository.findByTaskId(taskId);
            if (task.isPresent()) {
                return task;
            }
            Thread.sleep(100L);
        }
        return taskRepository.findByTaskId(taskId);
    }

    private void awaitTaskStatus(String taskId, String expectedStatus, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<AiTaskEntity> task = taskRepository.findByTaskId(taskId);
            if (task.isPresent() && expectedStatus.equalsIgnoreCase(task.get().getStatus())) {
                return;
            }
            Thread.sleep(200L);
        }
        Optional<AiTaskEntity> task = taskRepository.findByTaskId(taskId);
        assertTrue(task.isPresent());
        assertEquals(expectedStatus, task.get().getStatus());
    }

    private String startRelayServer(int relayPort, String answer, String traceId, long delayMs,
                                    List<RemoteCcExecutionRequest> executionRequests) throws Exception {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setHost("127.0.0.1");
        properties.setNodeHost("127.0.0.1");
        properties.setPort(relayPort);
        properties.setPath("/api/ai/remote-cc/chat");
        properties.setRelayEndpoint("http://127.0.0.1:" + relayPort + properties.getPath());
        properties.setCenterRegisterEndpoint(baseUrl("/api/skill/relay/register"));
        properties.setCenterHeartbeatEndpoint(baseUrl("/api/skill/relay/heartbeat"));
        properties.setNodeIdFilePath(runtimeDir.resolve("relay-" + relayPort + "-node-id").toString());
        properties.setContextStateFilePath(runtimeDir.resolve("relay-" + relayPort + "-context.json").toString());
        properties.setAllowedWorkRoots(List.of("*"));
        properties.setAllowedLogRoots(List.of("*"));
        properties.setAllowedCodeRoots(List.of("*"));
        properties.setA2aLargeFileThresholdBytes(5L * 1024L * 1024L);
        RemoteCcCommandRunner runner = request -> {
            executionRequests.add(request);
            if (delayMs > 0L && request.getPrompt().contains("run long task")) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("relay task interrupted", e);
                }
            }
            return new AiChatResponse(answer, "SUCCESS", traceId);
        };
        RemoteCcRelayServer relayServer = new RemoteCcRelayServer(properties, new RemoteCcRelayService(properties, runner));
        relayServer.start();
        relayServers.add(relayServer);
        return "http://127.0.0.1:" + relayPort + properties.getPath();
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String buildUrl(String path, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl(path));
        params.forEach(builder::queryParam);
        return builder.build(true).toUriString();
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}






