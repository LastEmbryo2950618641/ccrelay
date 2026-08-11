package com.webank.wedatasphere.wdsavs.aiagent.remote;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class RelayHealthProbe {

    private static final long DEFAULT_TIMEOUT_SECONDS = 10L;

    private RelayHealthProbe() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0] == null || args[0].isBlank()) {
            System.err.println("Relay health URL is required");
            System.exit(2);
        }
        long timeoutSeconds = args.length > 1 ? parseTimeout(args[1]) : DEFAULT_TIMEOUT_SECONDS;
        String expectedStatus = args.length > 2 ? args[2] : "UP";
        boolean healthy = waitUntilHealthy(args[0], Duration.ofSeconds(timeoutSeconds), expectedStatus);
        if (!healthy) {
            System.err.println("Relay health did not become UP before timeout: " + args[0]);
            System.exit(1);
        }
    }

    static boolean waitUntilHealthy(String healthUrl, Duration timeout) {
        return waitUntilHealthy(healthUrl, timeout, "UP");
    }

    static boolean waitUntilHealthy(String healthUrl, Duration timeout, String expectedStatus) {
        long deadline = System.nanoTime() + Math.max(1L, timeout.toNanos());
        while (System.nanoTime() < deadline) {
            if (isHealthy(healthUrl, expectedStatus)) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isHealthy(healthUrl, expectedStatus);
    }

    private static boolean isHealthy(String healthUrl, String expectedStatus) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(healthUrl).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1_000);
            connection.setReadTimeout(1_000);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String expected = expectedStatus == null || expectedStatus.isBlank() ? "UP" : expectedStatus;
            return body.matches("(?s).*\\\"status\\\"\\s*:\\s*\\\"" + java.util.regex.Pattern.quote(expected) + "\\\".*");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static long parseTimeout(String value) {
        try {
            return Math.max(1L, Long.parseLong(value));
        } catch (Exception ignored) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }
}
