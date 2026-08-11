package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Minimal, bundled relay-to-relay command surface used by Claude Code inside a Relay. */
public final class RelayCollaborationCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private RelayCollaborationCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0])) {
            usage();
            return;
        }
        String center = centerUrl();
        if ("relay".equals(args[0]) && args.length > 1) {
            if ("scan".equals(args[1])) {
                print(request("GET", center + "/api/skill/relay/heartbeat/scan", null));
                return;
            }
            if ("nodes".equals(args[1])) {
                print(request("GET", center + "/api/skill/relay/nodes", null));
                return;
            }
            if ("node".equals(args[1]) && args.length > 2) {
                print(request("GET", center + "/api/skill/relay/nodes/" + encode(args[2]), null));
                return;
            }
        }
        if ("agent".equals(args[0]) && args.length > 1 && "run".equals(args[1])) {
            runAgent(center, args);
            return;
        }
        throw new IllegalArgumentException("Unsupported bundled ccrelay-cli command");
    }

    private static void runAgent(String center, String[] args) throws Exception {
        String targetNodeId = option(args, "--target-node-id", null);
        String sessionId = option(args, "--session-id", env("WDSAVS_AI_RELAY_SESSION_ID"));
        String prompt = option(args, "--prompt", null);
        if (isBlank(targetNodeId) || isBlank(sessionId) || isBlank(prompt)) {
            throw new IllegalArgumentException("agent run requires --session-id, --target-node-id and --prompt");
        }
        String sourceNodeId = firstNonBlank(env("WDSAVS_AI_RELAY_NODE_ID"), readNodeId());
        if (isBlank(sourceNodeId)) {
            throw new IllegalStateException("Relay nodeId is unavailable");
        }
        String requestId = "relay-cli-" + UUID.randomUUID();
        ObjectNode access = MAPPER.createObjectNode();
        access.put("sessionId", sessionId);
        access.put("requestId", requestId);
        access.put("sourceNodeId", sourceNodeId);
        access.put("targetNodeId", targetNodeId);
        access.put("reason", "bundled ccrelay-cli agent run");
        ArrayNode capabilities = access.putArray("requiredCapabilities");
        capabilities.add("A2A_MESSAGE_SEND");
        access.put("ttlMs", 180000L);
        JsonNode grant = request("POST", center + "/api/skill/relay/access/request", access);
        String grantId = text(grant, "grantId");
        String signedToken = text(grant, "signedToken");
        String expiresAt = text(grant, "expiresAt");
        if (isBlank(grantId) || isBlank(signedToken)) {
            throw new IllegalStateException("Relay access request returned no usable grant");
        }
        JsonNode node = request("GET", center + "/api/skill/relay/nodes/" + encode(targetNodeId), null);
        String relayEndpoint = text(node, "relayEndpoint");
        if (isBlank(relayEndpoint)) {
            throw new IllegalStateException("Target relay endpoint is unavailable for node: " + targetNodeId);
        }
        ObjectNode params = MAPPER.createObjectNode();
        params.put("sessionId", sessionId);
        params.put("requestId", requestId);
        params.put("taskId", requestId);
        params.put("senderType", "RELAY");
        params.put("sourceNodeId", sourceNodeId);
        params.put("targetNodeId", targetNodeId);
        params.put("grantId", grantId);
        params.put("signedToken", signedToken);
        params.put("expiresAt", expiresAt);
        params.put("targetRelayEndpoint", relayEndpoint);
        params.put("centerGrantValidateEndpoint", center + "/api/skill/relay/access/validate");
        ArrayNode messages = params.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);
        ObjectNode metadata = params.putObject("metadata");
        metadata.put("sessionId", sessionId);
        metadata.put("requestId", requestId);
        metadata.put("sourceNodeId", sourceNodeId);
        metadata.put("targetNodeId", targetNodeId);
        metadata.put("centerGrantValidateEndpoint", center + "/api/skill/relay/access/validate");
        ObjectNode relayGrant = metadata.putObject("relayGrant");
        relayGrant.put("grantId", grantId);
        relayGrant.put("sessionId", sessionId);
        relayGrant.put("sourceNodeId", sourceNodeId);
        relayGrant.put("targetNodeId", targetNodeId);
        relayGrant.put("signedToken", signedToken);
        relayGrant.put("expiresAt", expiresAt);
        relayGrant.put("centerGrantValidateEndpoint", center + "/api/skill/relay/access/validate");
        ObjectNode routedRequest = MAPPER.createObjectNode();
        routedRequest.put("jsonrpc", "2.0");
        routedRequest.put("id", requestId);
        routedRequest.put("method", "message/send");
        routedRequest.set("params", params);
        JsonNode response = request("POST", center + "/api/skill/a2a/message/send", routedRequest);
        print(response);
    }

    private static JsonNode request(String method, String url, JsonNode body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json;charset=UTF-8");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode parsed = response.body() == null || response.body().isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(response.body());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + parsed.toString());
        }
        return parsed;
    }

    private static String centerUrl() {
        String configured = firstNonBlank(env("CCRELAY_CENTER_URL"), env("WDSAVS_CC_RELAY_CENTER_URL"));
        if (!isBlank(configured)) {
            return configured.replaceAll("/$", "");
        }
        String register = env("WDSAVS_AI_RELAY_REGISTER_ENDPOINT");
        if (!isBlank(register)) {
            int index = register.indexOf("/api/skill/");
            if (index > 0) {
                return register.substring(0, index);
            }
        }
        throw new IllegalStateException("CC center endpoint is unavailable");
    }

    private static String readNodeId() {
        String path = env("WDSAVS_AI_RELAY_NODE_ID_FILE");
        if (isBlank(path)) {
            return null;
        }
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String option(String[] args, String name, String defaultValue) {
        for (int index = 0; index < args.length - 1; index++) {
            if (name.equals(args[index])) {
                return args[index + 1];
            }
        }
        return defaultValue;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void print(JsonNode value) throws IOException {
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static void usage() {
        System.out.println("ccrelay-cli relay scan");
        System.out.println("ccrelay-cli relay nodes");
        System.out.println("ccrelay-cli relay node <nodeId>");
        System.out.println("ccrelay-cli agent run --session-id <sessionId> --target-node-id <nodeId> --prompt <prompt>");
    }
}
