package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.A2aJsonRpcResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantValidateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.RelayGrantView;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcCommandRunner;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayProperties;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayServer;
import com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class A2aAgentServiceMultiNodeLocalTest {

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
    void supportsLocalMultiNodeCollaborationAcrossDifferentPorts() throws Exception {
        int sourcePort = freePort();
        int targetPort = freePort();
        startRelayServer(sourcePort, "source-node reply", "trace-source-node");
        String targetRelayEndpoint = startRelayServer(targetPort, "target-node reply", "trace-target-node");

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-local-1";
        String grantId = "grant-local-1";
        String sourceNodeId = "node-source-local";
        String targetNodeId = "node-target-local";
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);
        String signedToken = tokenService.sign(
                grantId,
                sessionId,
                sourceNodeId,
                targetNodeId,
                List.of("A2A_MESSAGE_SEND"),
                expiresAt);

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        RelayGrantView grant = new RelayGrantView();
        grant.setGrantId(grantId);
        grant.setExpiresAt(expiresAt);
        when(relayGrantService.getGrant(grantId)).thenReturn(grant);
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));

        A2aAgentService service = new A2aAgentService(
                request -> new AiChatResponse("center fallback", "SUCCESS", "trace-center"),
                new AiCapabilityCatalogService(),
                relayGrantService,
                null,
                mock(AiAuditService.class),
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        A2aJsonRpcRequest request = new A2aJsonRpcRequest();
        request.setId("rpc-local-multi-node");
        request.setJsonrpc("2.0");
        request.setMethod("message/send");
        request.setParams(Map.of(
                "sessionId", sessionId,
                "grantId", grantId,
                "signedToken", signedToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "hello target node"))
        ));

        A2aJsonRpcResponse response = service.handle(request);

        assertNotNull(response.getResult());
        assertEquals("target-node reply", response.getResult().get("answer"));
        assertEquals("trace-target-node", response.getResult().get("traceId"));
        assertEquals("rpc-local-multi-node", response.getResult().get("requestId"));
        assertEquals(sessionId, response.getResult().get("sessionId"));
    }

    @Test
    void rejectsForgedGrantMetadataOnDirectRelayCall() throws Exception {
        String targetRelayEndpoint = startRelayServer(freePort(), "target-node reply", "trace-target-node");
        RestTemplate restTemplate = new RestTemplate();
        AiChatRequest request = new AiChatRequest();
        request.setMessages(List.of(new AiChatMessage("user", "hello target node")));
        Map<String, Object> relayGrant = new LinkedHashMap<>();
        relayGrant.put("grantId", "grant-fake");
        relayGrant.put("sessionId", "session-fake");
        relayGrant.put("sourceNodeId", "node-source-fake");
        relayGrant.put("targetNodeId", "node-target-fake");
        relayGrant.put("signedToken", "forged-token");
        relayGrant.put("expiresAt", String.valueOf(System.currentTimeMillis() + 60000L));
        relayGrant.put("allowedCapabilities", List.of("A2A_MESSAGE_SEND"));
        request.setMetadata(Map.of("relayGrant", relayGrant));

        assertThrows(HttpServerErrorException.class,
                () -> restTemplate.postForObject(targetRelayEndpoint, request, AiChatResponse.class));
    }

    @Test
    void rejectsDirectRelayAfterCenterGrantRevocationAcrossDifferentPorts() throws Exception {
        int centerPort = freePort();
        int targetPort = freePort();
        AtomicReference<RelayGrantValidateResponse> decision = new AtomicReference<>(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));
        String centerGrantValidateEndpoint = startGrantValidationServer(centerPort, decision);
        String targetRelayEndpoint = startRelayServer(targetPort, "target-node reply", "trace-target-node");

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-local-revoke";
        String grantId = "grant-local-revoke";
        String sourceNodeId = "node-source-local";
        String targetNodeId = "node-target-local";
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);
        String signedToken = tokenService.sign(
                grantId,
                sessionId,
                sourceNodeId,
                targetNodeId,
                List.of("A2A_MESSAGE_SEND"),
                expiresAt);

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        RelayGrantView grant = new RelayGrantView();
        grant.setGrantId(grantId);
        grant.setExpiresAt(expiresAt);
        when(relayGrantService.getGrant(grantId)).thenReturn(grant);
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));

        A2aAgentService service = new A2aAgentService(
                request -> new AiChatResponse("center fallback", "SUCCESS", "trace-center"),
                new AiCapabilityCatalogService(),
                relayGrantService,
                null,
                mock(AiAuditService.class),
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(1024L),
                false
        );

        A2aJsonRpcRequest request = new A2aJsonRpcRequest();
        request.setId("rpc-local-revoke");
        request.setJsonrpc("2.0");
        request.setMethod("message/send");
        request.setParams(Map.of(
                "sessionId", sessionId,
                "grantId", grantId,
                "signedToken", signedToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "centerGrantValidateEndpoint", centerGrantValidateEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "hello target node"))
        ));

        A2aJsonRpcResponse firstResponse = service.handle(request);
        assertNotNull(firstResponse.getResult());
        assertEquals("target-node reply", firstResponse.getResult().get("answer"));

        decision.set(new RelayGrantValidateResponse(false, "REVOKED", "Grant is not active"));

        A2aJsonRpcResponse revokedResponse = service.handle(request);

        assertNotNull(revokedResponse.getError());
        assertEquals(-32000, revokedResponse.getError().get("code"));
        assertTrue(String.valueOf(revokedResponse.getError().get("message")).contains("relayGrant center validation failed"));
    }

    @Test
    void sanitizesStructuredRemoteMessageResultAcrossDifferentPorts() throws Exception {
        int sourcePort = freePort();
        int targetPort = freePort();
        startRelayServer(sourcePort, "source-node reply", "trace-source-node");

        AiChatResponse structuredResponse = new AiChatResponse("target-node reply", "SUCCESS", "trace-structured");
        structuredResponse.setSummary("structured summary");
        structuredResponse.setArtifacts(List.of(Map.of(
                "name", "big.txt",
                "sizeBytes", 20,
                "content", "01234567890123456789",
                "fileRef", Map.of("path", "/tmp/big.txt")
        )));
        structuredResponse.setDiagnostics(Map.of("tokens", 321));
        structuredResponse.setMetadata(Map.of("provider", "remote-cc"));
        String targetRelayEndpoint = startRelayServer(targetPort, structuredResponse);

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        String sessionId = "session-local-structured";
        String grantId = "grant-local-structured";
        String sourceNodeId = "node-source-local:" + sourcePort;
        String targetNodeId = "node-target-local:" + targetPort;
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);
        String signedToken = tokenService.sign(grantId, sessionId, sourceNodeId, targetNodeId,
                List.of("A2A_MESSAGE_SEND"), expiresAt);

        AiRelayGrantService relayGrantService = mock(AiRelayGrantService.class);
        RelayGrantView grant = new RelayGrantView();
        grant.setGrantId(grantId);
        grant.setExpiresAt(expiresAt);
        when(relayGrantService.getGrant(grantId)).thenReturn(grant);
        when(relayGrantService.validateGrant(any())).thenReturn(new RelayGrantValidateResponse(true, "ACTIVE", "ok"));

        A2aAgentService service = new A2aAgentService(
                request -> new AiChatResponse("center fallback", "SUCCESS", "trace-center"),
                new AiCapabilityCatalogService(),
                relayGrantService,
                null,
                mock(AiAuditService.class),
                new RestTemplate(),
                new A2aPayloadPolicyServiceImpl(10L),
                false
        );

        A2aJsonRpcRequest request = new A2aJsonRpcRequest();
        request.setId("rpc-local-structured");
        request.setJsonrpc("2.0");
        request.setMethod("message/send");
        request.setParams(Map.of(
                "sessionId", sessionId,
                "grantId", grantId,
                "signedToken", signedToken,
                "sourceNodeId", sourceNodeId,
                "targetNodeId", targetNodeId,
                "targetRelayEndpoint", targetRelayEndpoint,
                "messages", List.of(Map.of("role", "user", "content", "hello target node"))
        ));

        A2aJsonRpcResponse response = service.handle(request);
        assertNotNull(response.getResult());
        assertEquals("target-node reply", response.getResult().get("answer"));
        assertEquals("structured summary", response.getResult().get("summary"));
        assertEquals(Map.of("tokens", 321), response.getResult().get("diagnostics"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) response.getResult().get("metadata");
        assertEquals("remote-cc", metadata.get("provider"));
        assertEquals(sessionId, metadata.get("sessionId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) ((List<?>) response.getResult().get("artifacts")).get(0);
        assertEquals(true, artifact.get("inlineContentOmitted"));
        assertEquals(true, artifact.get("referenceOnly"));
        assertTrue(!artifact.containsKey("content"));
    }

    @Test
    void rejectsReplayedSignedGrantValidationRequestAcrossDifferentPorts() throws Exception {
        int targetPort = freePort();
        startRelayServer(targetPort, "target-node reply", "trace-target-node");

        RelayGrantTokenService tokenService = new RelayGrantTokenServiceImpl();
        RelayRequestSecurityService requestSecurityService = new RelayRequestSecurityServiceImpl();
        String sessionId = "session-replay-validate";
        String grantId = "grant-replay-validate";
        String sourceNodeId = "node-source-local";
        String targetNodeId = "node-target-local";
        String expiresAt = String.valueOf(System.currentTimeMillis() + 60000L);
        String signedToken = tokenService.sign(grantId, sessionId, sourceNodeId, targetNodeId,
                List.of("A2A_MESSAGE_SEND"), expiresAt);

        RelayGrantValidateRequest validateRequest = new RelayGrantValidateRequest();
        validateRequest.setGrantId(grantId);
        validateRequest.setSessionId(sessionId);
        validateRequest.setSourceNodeId(sourceNodeId);
        validateRequest.setTargetNodeId(targetNodeId);
        validateRequest.setSignedToken(signedToken);
        validateRequest.setExpiresAt(expiresAt);
        validateRequest.setAllowedCapabilities(List.of("A2A_MESSAGE_SEND"));

        RelayGrantValidateRequest signedRequest = requestSecurityService.sign(validateRequest);
        RestTemplate restTemplate = new RestTemplate();
        String endpoint = "http://127.0.0.1:" + targetPort + "/internal/grant/validate";

        RelayGrantValidateResponse first = restTemplate.postForObject(endpoint, signedRequest, RelayGrantValidateResponse.class);
        assertNotNull(first);
        assertEquals(true, first.getValid());

        RelayGrantValidateResponse replayed = restTemplate.postForObject(endpoint, signedRequest, RelayGrantValidateResponse.class);
        assertNotNull(replayed);
        assertEquals(false, replayed.getValid());
        assertEquals("REPLAYED", replayed.getStatus());
        assertTrue(String.valueOf(replayed.getMessage()).contains("nonce"));
    }
    private String startRelayServer(int port, String answer, String traceId) throws Exception {
        return startRelayServer(port, new AiChatResponse(answer, "SUCCESS", traceId));
    }

    private String startRelayServer(int port, AiChatResponse response) throws Exception {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(port);
        properties.setPath("/api/ai/remote-cc/chat");
        properties.setAllowedWorkRoots(List.of("*"));
        properties.setA2aLargeFileThresholdBytes(10L);
        RemoteCcCommandRunner runner = request -> response;
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

    private void stopHttpServers() {
        for (HttpServer server : httpServers) {
            if (server != null) {
                server.stop(0);
            }
        }
        httpServers.clear();
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}









