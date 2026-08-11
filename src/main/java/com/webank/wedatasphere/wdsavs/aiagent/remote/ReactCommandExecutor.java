package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.ReactExecutionPolicy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ReactCommandExecutor {

    private static final Set<String> DEFAULT_READONLY_COMMANDS = Set.of(
            "pwd", "ls", "cat", "grep", "tail", "head", "find", "wc", "hostname", "whoami",
            "date", "df", "du", "ps", "free", "uptime", "env", "printenv", "java"
    );

    private final RemoteCcRelayProperties properties;

    public ReactCommandExecutor(RemoteCcRelayProperties properties) {
        this.properties = properties == null ? new RemoteCcRelayProperties() : properties;
    }

    public void validate(Map<String, Object> action, ReactExecutionPolicy policy) {
        String command = stringValue(action.get("command"));
        if (isBlank(command)) {
            throw new IllegalArgumentException("RUN_COMMAND.action.command is required");
        }
        String basename = commandBasename(command);
        if (!isCommandAllowed(basename, policy)) {
            throw new SecurityException("Command is not allowed by commandWhitelist: " + basename);
        }
        if (containsShellControl(command)) {
            throw new SecurityException("Shell control characters are not allowed in command: " + command);
        }
        String cwd = stringValue(action.get("cwd"));
        if (!isBlank(cwd)) {
            validateWorkingDirectory(cwd);
        }
    }

    public ReactCommandResult execute(Map<String, Object> action, ReactExecutionPolicy policy) {
        validate(action, policy);
        ReactCommandResult result = new ReactCommandResult();
        String command = stringValue(action.get("command"));
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(args(action.get("args")));
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
            String cwd = stringValue(action.get("cwd"));
            if (!isBlank(cwd)) {
                processBuilder.directory(new File(cwd));
            } else if (!isBlank(properties.getWorkingDirectory())) {
                processBuilder.directory(new File(properties.getWorkingDirectory()));
            }
            Process process = processBuilder.start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readText(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readText(process.getErrorStream()));
            long timeoutMs = stepTimeoutMs(action, policy);
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.setStatus("TIMEOUT");
                result.setTimedOut(true);
                result.setErrorMessage("Command timed out after " + timeoutMs + "ms");
                return result;
            }
            result.setExitCode(process.exitValue());
            result.setStdout(limit(stdout.join()));
            result.setStderr(limit(stderr.join()));
            result.setStatus(process.exitValue() == 0 ? "SUCCESS" : "FAILED");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.setStatus("CANCELLED");
            result.setErrorMessage("Command interrupted");
            return result;
        } catch (Exception e) {
            result.setStatus("FAILED");
            result.setErrorMessage(e.getMessage());
            return result;
        }
    }

    private boolean isCommandAllowed(String basename, ReactExecutionPolicy policy) {
        List<String> whitelist = policy == null ? List.of() : policy.getCommandWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            return DEFAULT_READONLY_COMMANDS.contains(basename.toLowerCase(Locale.ROOT));
        }
        for (String item : whitelist) {
            if (basename.equalsIgnoreCase(commandBasename(item))) {
                return true;
            }
        }
        return false;
    }

    private void validateWorkingDirectory(String workingDirectory) {
        List<String> allowedWorkRoots = properties.getAllowedWorkRoots() == null || properties.getAllowedWorkRoots().isEmpty()
                ? List.of("*")
                : properties.getAllowedWorkRoots();
        if (allowedWorkRoots.size() == 1 && "*".equals(allowedWorkRoots.get(0))) {
            return;
        }
        String normalized = normalizePath(workingDirectory);
        for (String allowedRoot : allowedWorkRoots) {
            if ("*".equals(allowedRoot) || normalized.startsWith(normalizePath(allowedRoot))) {
                return;
            }
        }
        throw new SecurityException("cwd is outside allowedWorkRoots: " + workingDirectory);
    }

    private long stepTimeoutMs(Map<String, Object> action, ReactExecutionPolicy policy) {
        Object configured = action.get("timeoutMs");
        if (configured instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        if (configured != null && !String.valueOf(configured).trim().isEmpty()) {
            return Long.parseLong(String.valueOf(configured));
        }
        Long policyTimeout = policy == null ? null : policy.getStepTimeoutMs();
        return policyTimeout == null || policyTimeout <= 0L ? Duration.ofMinutes(1).toMillis() : policyTimeout;
    }

    private List<String> args(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private String commandBasename(String command) {
        if (command == null) {
            return "";
        }
        String normalized = command.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private boolean containsShellControl(String command) {
        return command != null && (command.contains(";") || command.contains("&&") || command.contains("||") || command.contains("|") || command.contains(">") || command.contains("<"));
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    private String limit(String value) {
        String text = value == null ? "" : value;
        return text.length() <= 4096 ? text : text.substring(0, 4096);
    }

    private String readText(java.io.InputStream inputStream) {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
