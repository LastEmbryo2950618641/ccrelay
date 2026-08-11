package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayHealthProbeTest {

    @Test
    void shouldAcceptUpHealthResponse() throws Exception {
        HttpServer server = startServer("{\"status\":\"UP\"}");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";

            assertTrue(RelayHealthProbe.waitUntilHealthy(url, Duration.ofSeconds(1)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectNonUpHealthResponse() throws Exception {
        HttpServer server = startServer("{\"status\":\"STARTING\"}");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";

            assertFalse(RelayHealthProbe.waitUntilHealthy(url, Duration.ofMillis(100)));
        } finally {
            server.stop(0);
        }
    }

    private HttpServer startServer(String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }
}
