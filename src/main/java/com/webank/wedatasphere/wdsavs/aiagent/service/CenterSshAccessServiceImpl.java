package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.CenterSshPreflightRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.CenterSshPreflightResponse;
import com.webank.wedatasphere.wdsavs.aiagent.model.CenterSshPublicKeyView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class CenterSshAccessServiceImpl implements CenterSshAccessService {

    private static final long DEFAULT_TIMEOUT_MS = 15_000L;
    private final String keyAlgorithm;

    public CenterSshAccessServiceImpl(
            @Value("${wdsavs.ai.ssh.center-key-algorithm:${CCRELAY_SSH_KEY_ALGORITHM:AUTO}}") String keyAlgorithm) {
        this.keyAlgorithm = normalizeKeyAlgorithm(keyAlgorithm);
    }

    @Override
    public CenterSshPublicKeyView getPublicKey() {
        Path privateKey = ensureCenterKey();
        Path publicKey = Path.of(privateKey + ".pub");
        try {
            String value = Files.readString(publicKey, StandardCharsets.UTF_8).trim();
            String algorithm = value.contains(" ") ? value.substring(0, value.indexOf(' ')) : "unknown";
            return new CenterSshPublicKeyView(algorithm, value, fingerprint(value));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read center SSH public key", e);
        }
    }

    @Override
    public CenterSshPreflightResponse preflight(CenterSshPreflightRequest request) {
        validate(request);
        int port = request.getPort() == null || request.getPort() <= 0 ? 22 : request.getPort();
        long timeoutMs = request.getTimeoutMs() == null || request.getTimeoutMs() <= 0L
                ? DEFAULT_TIMEOUT_MS : request.getTimeoutMs();
        String target = request.getUsername().trim() + "@" + request.getHost().trim();
        long started = System.nanoTime();
        try {
            List<String> command = new ArrayList<>();
            command.add(sshCommand());
            if (request.getSshArguments() == null || request.getSshArguments().isEmpty()) {
                command.addAll(List.of(
                        "-o", "BatchMode=yes",
                        "-o", "StrictHostKeyChecking=no",
                        "-o", "ConnectTimeout=" + Math.max(1L, Duration.ofMillis(timeoutMs).toSeconds()),
                        "-o", "ConnectionAttempts=1"));
            } else {
                command.addAll(request.getSshArguments());
            }
            command.addAll(List.of(
                    "-p", String.valueOf(port),
                    target,
                    "printf CCRELAY_CENTER_SSH_OK"));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return response(false, "FAILED", "NETWORK_TIMEOUT", "Center SSH preflight timed out",
                        request, port, started);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() == 0) {
                return response(true, "READY", null, output, request, port, started);
            }
            return response(false, "FAILED", classify(output), summarize(output), request, port, started);
        } catch (Exception e) {
            return response(false, "FAILED", "CENTER_SSH_EXECUTION_FAILED", summarize(e.getMessage()), request, port, started);
        }
    }

    Path ensureCenterKey() {
        Path sshDirectory = Path.of(System.getProperty("user.home"), ".ssh");
        for (String keyName : keyNames()) {
            Path existing = sshDirectory.resolve(keyName);
            if (Files.isRegularFile(existing) && Files.isRegularFile(Path.of(existing + ".pub"))) {
                return existing;
            }
        }
        String generatedName = "RSA".equals(keyAlgorithm) ? "id_rsa" : "id_ed25519";
        Path generated = sshDirectory.resolve(generatedName);
        try {
            Files.createDirectories(sshDirectory);
            List<String> command = new ArrayList<>();
            command.add(sshKeygenCommand());
            command.addAll(keygenArguments());
            command.addAll(List.of("-N", "", "-f", generated.toString(), "-C", "ccrelay-center"));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("ssh-keygen timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ssh-keygen failed: " + summarize(output));
            }
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create center SSH key", e);
        }
    }

    String keyAlgorithm() {
        return keyAlgorithm;
    }

    List<String> keyNames() {
        return List.of("ED25519".equals(keyAlgorithm) ? "id_ed25519" : "id_rsa");
    }

    List<String> keygenArguments() {
        return "ED25519".equals(keyAlgorithm)
                ? List.of("-q", "-t", "ed25519")
                : List.of("-q", "-t", "rsa", "-b", "3072");
    }

    private String normalizeKeyAlgorithm(String value) {
        String normalized = value == null ? "AUTO" : value.trim().toUpperCase();
        if ("AUTO".equals(normalized) || "RSA".equals(normalized)) {
            return "RSA";
        }
        if ("ED25519".equals(normalized) || "SSH-ED25519".equals(normalized)) {
            return "ED25519";
        }
        throw new IllegalArgumentException("wdsavs.ai.ssh.center-key-algorithm must be AUTO, ED25519, or RSA");
    }

    private CenterSshPreflightResponse response(boolean success, String status, String failureType, String summary,
                                                CenterSshPreflightRequest request, int port, long started) {
        return new CenterSshPreflightResponse(success, status, failureType, summarize(summary), request.getHost(), port,
                request.getUsername(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private void validate(CenterSshPreflightRequest request) {
        if (request == null || isBlank(request.getHost()) || isBlank(request.getUsername())) {
            throw new IllegalArgumentException("host and username are required for center SSH preflight");
        }
        if (request.getSshArguments() != null) {
            if (request.getSshArguments().size() > 64) {
                throw new IllegalArgumentException("sshArguments cannot contain more than 64 values");
            }
            if (request.getSshArguments().stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("sshArguments cannot contain null values or NUL characters");
            }
        }
    }

    private String classify(String value) {
        String normalized = value == null ? "" : value.toLowerCase();
        if (normalized.contains("host key verification failed") || normalized.contains("identification has changed")) {
            return "HOST_KEY_CHANGED";
        }
        if (normalized.contains("permission denied")) {
            return "AUTHENTICATION_FAILED";
        }
        if (normalized.contains("timed out")) {
            return "NETWORK_TIMEOUT";
        }
        if (normalized.contains("connection refused") || normalized.contains("no route to host")
                || normalized.contains("could not resolve hostname")) {
            return "NETWORK_UNREACHABLE";
        }
        return "UNKNOWN_SSH_FAILURE";
    }

    private String fingerprint(String publicKey) throws Exception {
        String[] parts = publicKey.split("\\s+");
        byte[] decoded = Base64.getDecoder().decode(parts.length > 1 ? parts[1] : parts[0]);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(decoded);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    }

    private String sshCommand() {
        return Files.isExecutable(Path.of("/usr/bin/ssh")) ? "/usr/bin/ssh" : "ssh";
    }

    private String sshKeygenCommand() {
        return Files.isExecutable(Path.of("/usr/bin/ssh-keygen")) ? "/usr/bin/ssh-keygen" : "ssh-keygen";
    }

    private String summarize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
