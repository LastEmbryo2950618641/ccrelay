package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteCcRelayServerHeartbeatRetryTest {

    @Test
    void sendHeartbeatRetriesAcrossMultipleUnexpectedEofFailures() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try {
                    while (attempts.get() < 3) {
                        Socket socket = serverSocket.accept();
                        int attempt = attempts.incrementAndGet();
                        if (attempt <= 2) {
                            socket.close();
                            continue;
                        }
                        try (socket;
                             BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
                             OutputStream outputStream = socket.getOutputStream()) {
                            byte[] buffer = new byte[8192];
                            while (inputStream.read(buffer) != -1) {
                                String received = new String(buffer, StandardCharsets.UTF_8);
                                if (received.contains("\r\n\r\n")) {
                                    break;
                                }
                            }
                            byte[] responseBody = "{}".getBytes(StandardCharsets.UTF_8);
                            outputStream.write(("HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: application/json\r\n"
                                    + "Content-Length: " + responseBody.length + "\r\n"
                                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            outputStream.write(responseBody);
                            outputStream.flush();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    completed.countDown();
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
            properties.setCenterHeartbeatEndpoint("http://127.0.0.1:" + serverSocket.getLocalPort() + "/api/skill/relay/heartbeat");
            properties.setTimeoutMs(2000L);

            RemoteCcRelayServer relayServer = new RemoteCcRelayServer(
                    properties,
                    new RemoteCcRelayService(properties, request -> new AiChatResponse("ok", "SUCCESS", "trace-1"))
            );

            Field localNodeIdField = RemoteCcRelayServer.class.getDeclaredField("localNodeId");
            localNodeIdField.setAccessible(true);
            localNodeIdField.set(relayServer, "node-a:18091");

            Method sendHeartbeatMethod = RemoteCcRelayServer.class.getDeclaredMethod("sendHeartbeatToCenter");
            sendHeartbeatMethod.setAccessible(true);

            assertDoesNotThrow(() -> sendHeartbeatMethod.invoke(relayServer));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(3, attempts.get());
        }
    }
}
