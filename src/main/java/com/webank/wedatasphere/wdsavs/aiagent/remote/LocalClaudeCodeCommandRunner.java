package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LocalClaudeCodeCommandRunner implements RemoteCcCommandRunner {

    @Override
    public AiChatResponse execute(RemoteCcExecutionRequest request) {
        if (request.getCommand() == null || request.getCommand().trim().isEmpty()) {
            throw new IllegalArgumentException("Remote CC command is required");
        }
        int attempts = maxRetries(request) + 1;
        long deadlineNanos = deadlineNanos(request);
        AiChatResponse lastResponse = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long remainingMs = remainingMillis(deadlineNanos);
            if (remainingMs <= 0L) {
                return timeoutResponse();
            }
            ExecutionAttempt executionAttempt = executeOnce(request, remainingMs);
            lastResponse = executionAttempt.response();
            if (executionAttempt.modelSessionStarted()) {
                markModelSessionStarted(lastResponse);
                request.setResumeModelSession(true);
            }
            if ("SUCCESS".equalsIgnoreCase(lastResponse.getStatus())
                    || "TIMEOUT".equalsIgnoreCase(lastResponse.getStatus())
                    || attempt == attempts) {
                return lastResponse;
            }
            if (!sleepBeforeRetry(request, deadlineNanos)) {
                return timeoutResponse();
            }
        }
        return lastResponse == null ? new AiChatResponse("Remote CC command failed", "FAILED", UUID.randomUUID().toString()) : lastResponse;
    }

    ExecutionAttempt executeOnce(RemoteCcExecutionRequest request, long timeoutMs) {
        CompletableFuture<Void> stdout = null;
        CompletableFuture<String> stderr = null;
        Path requestSettingsFile = null;
        boolean modelSessionStarted = false;
        try {
            requestSettingsFile = writeRequestSettingsFile(request);
            List<String> commandLine = commandLine(request, requestSettingsFile);
            ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
            if (request.getWorkingDirectory() != null && !request.getWorkingDirectory().trim().isEmpty()) {
                processBuilder.directory(new File(request.getWorkingDirectory()));
            }
            if (request.getEnvironment() != null) {
                processBuilder.environment().putAll(request.getEnvironment());
            }
            Process process = processBuilder.start();
            modelSessionStarted = startsNamedModelSession(commandLine);
            process.getOutputStream().write(limitedPrompt(request).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();

            ClaudeCodeStreamCollector collector = new ClaudeCodeStreamCollector(request.getEventWriter());
            stdout = CompletableFuture.runAsync(() -> readOutput(process.getInputStream(), collector));
            stderr = CompletableFuture.supplyAsync(() -> readText(process.getErrorStream()));
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcessTree(process);
                cancel(stdout);
                cancel(stderr);
                return new ExecutionAttempt(timeoutResponse(), modelSessionStarted);
            }
            stdout.join();
            String output = collector.answer();
            String error = stderr.join();
            if (process.exitValue() != 0) {
                return new ExecutionAttempt(new AiChatResponse(
                        limitedResponse(request, error == null || error.isEmpty() ? output : error),
                        "FAILED", UUID.randomUUID().toString()), modelSessionStarted);
            }
            return new ExecutionAttempt(new AiChatResponse(
                    limitedResponse(request, output), "SUCCESS", UUID.randomUUID().toString()), modelSessionStarted);
        } catch (Exception e) {
            return new ExecutionAttempt(
                    new AiChatResponse(e.getMessage(), "FAILED", UUID.randomUUID().toString()),
                    modelSessionStarted);
        } finally {
            deleteRequestSettingsFile(requestSettingsFile);
        }
    }

    private boolean startsNamedModelSession(List<String> commandLine) {
        return commandLine != null && !commandLine.isEmpty()
                && isClaudeCommand(commandLine.get(0))
                && (containsArgument(commandLine, "--session-id") || containsArgument(commandLine, "--resume"));
    }

    private void markModelSessionStarted(AiChatResponse response) {
        if (response == null) {
            return;
        }
        java.util.Map<String, Object> metadata = response.getMetadata() == null
                ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(response.getMetadata());
        metadata.put(RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA, true);
        response.setMetadata(metadata);
    }

    record ExecutionAttempt(AiChatResponse response, boolean modelSessionStarted) {
    }

    List<String> commandLine(RemoteCcExecutionRequest request) {
        return commandLine(request, null);
    }

    List<String> commandLine(RemoteCcExecutionRequest request, Path requestSettingsFile) {
        List<String> commandLine = new ArrayList<>();
        String command = resolveCommand(request.getCommand());
        commandLine.add(command);
        if (request.getArguments() != null) {
            commandLine.addAll(request.getArguments());
        }
        if (isClaudeCommand(command) && containsArgument(commandLine, "--print")
                && !containsArgument(commandLine, "--output-format")) {
            commandLine.add("--output-format");
            commandLine.add("stream-json");
            commandLine.add("--verbose");
        }
        if (isClaudeCommand(command) && request.getClaudeSettingsFile() != null
                && !request.getClaudeSettingsFile().trim().isEmpty()) {
            if (!containsArgument(commandLine, "--bare")) {
                commandLine.add("--bare");
            }
            if (!containsArgument(commandLine, "--permission-mode")) {
                commandLine.add("--permission-mode");
                commandLine.add("dontAsk");
            }
            if (!containsArgument(commandLine, "--setting-sources")) {
                commandLine.add("--setting-sources");
                commandLine.add("user");
            }
            if (!containsArgument(commandLine, "--settings")) {
                commandLine.add("--settings");
                commandLine.add(requestSettingsFile == null
                        ? request.getClaudeSettingsFile().trim() : requestSettingsFile.toString());
            }
        }
        if (isClaudeCommand(command) && request.getModelSessionId() != null
                && !request.getModelSessionId().trim().isEmpty()
                && !containsArgument(commandLine, "--session-id")
                && !containsArgument(commandLine, "--resume")) {
            commandLine.add(request.isResumeModelSession() ? "--resume" : "--session-id");
            commandLine.add(request.getModelSessionId().trim());
        }
        return commandLine;
    }

    Path writeRequestSettingsFile(RemoteCcExecutionRequest request) throws Exception {
        if (request == null || request.getClaudeSettingsJson() == null
                || request.getClaudeSettingsJson().isBlank()) {
            return null;
        }
        Path directory = null;
        if (request.getClaudeSettingsFile() != null && !request.getClaudeSettingsFile().isBlank()) {
            directory = Path.of(request.getClaudeSettingsFile()).toAbsolutePath().getParent();
        }
        Path settingsFile;
        if (directory == null) {
            settingsFile = Files.createTempFile("ccrelay-settings-", ".json");
        } else {
            Files.createDirectories(directory);
            settingsFile = Files.createTempFile(directory, "ccrelay-settings-", ".json");
        }
        Files.writeString(settingsFile, request.getClaudeSettingsJson(), StandardCharsets.UTF_8);
        return settingsFile;
    }

    private void deleteRequestSettingsFile(Path settingsFile) {
        if (settingsFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(settingsFile);
        } catch (Exception ignored) {
        }
    }

    private String resolveCommand(String command) {
        if (!isWindows() || command == null || command.trim().isEmpty() || command.contains("\\") || command.contains("/")) {
            return command;
        }
        String normalized = command.trim();
        if (!"claude".equalsIgnoreCase(normalized)) {
            return command;
        }
        String path = System.getenv("PATH");
        String pathExt = System.getenv("PATHEXT");
        List<String> extensions = new ArrayList<>(List.of(".exe", ".cmd", ".bat", ".com"));
        if (pathExt != null && !pathExt.isBlank()) {
            extensions = new ArrayList<>();
            for (String extension : pathExt.split(";")) {
                if (!extension.isBlank()) {
                    extensions.add(extension.trim().toLowerCase());
                }
            }
        }
        if (path == null || path.isBlank()) {
            return command;
        }
        for (String directory : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            for (String extension : extensions) {
                Path candidate = Path.of(directory, normalized + extension);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return command;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean isClaudeCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String normalized = command.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase();
        return "claude".equals(basename) || "claude.exe".equals(basename)
                || "claude.cmd".equals(basename) || "claude.bat".equals(basename);
    }

    private boolean containsArgument(List<String> arguments, String expected) {
        for (String argument : arguments) {
            if (expected.equalsIgnoreCase(argument) || argument.toLowerCase().startsWith(expected.toLowerCase() + "=")) {
                return true;
            }
        }
        return false;
    }

    private long deadlineNanos(RemoteCcExecutionRequest request) {
        long timeoutMs = request.getTimeoutMs() <= 0 ? Duration.ofMinutes(10).toMillis() : request.getTimeoutMs();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long now = System.nanoTime();
        return Long.MAX_VALUE - now < timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private int maxRetries(RemoteCcExecutionRequest request) {
        if (request.getConvergencePolicy() == null || request.getConvergencePolicy().getMaxRetries() == null) {
            return 0;
        }
        return Math.max(0, request.getConvergencePolicy().getMaxRetries());
    }

    private boolean sleepBeforeRetry(RemoteCcExecutionRequest request, long deadlineNanos) {
        long retryDelayMs = request.getConvergencePolicy() == null || request.getConvergencePolicy().getRetryDelayMs() == null
                ? 0L
                : Math.max(0L, request.getConvergencePolicy().getRetryDelayMs());
        if (retryDelayMs <= 0) {
            return remainingMillis(deadlineNanos) > 0L;
        }
        try {
            long remainingMs = remainingMillis(deadlineNanos);
            if (remainingMs <= 0L) {
                return false;
            }
            Thread.sleep(Math.min(retryDelayMs, remainingMs));
            return remainingMillis(deadlineNanos) > 0L;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = process.toHandle().descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        for (ProcessHandle descendant : descendants) {
            descendant.destroy();
        }
        process.destroy();
        waitForExit(process, 250L);
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        waitForExit(process, 1000L);
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
    }

    private void waitForExit(Process process, long timeoutMs) {
        try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private void cancel(CompletableFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private AiChatResponse timeoutResponse() {
        return new AiChatResponse("Remote CC command timed out", "TIMEOUT", UUID.randomUUID().toString());
    }

    private String limitedPrompt(RemoteCcExecutionRequest request) {
        String prompt = request.getPrompt() == null ? "" : request.getPrompt();
        if (request.getConvergencePolicy() == null || request.getConvergencePolicy().getMaxPromptChars() == null) {
            return prompt;
        }
        int maxPromptChars = request.getConvergencePolicy().getMaxPromptChars();
        if (maxPromptChars <= 0 || prompt.length() <= maxPromptChars) {
            return prompt;
        }
        return prompt.substring(0, maxPromptChars);
    }

    private String limitedResponse(RemoteCcExecutionRequest request, String response) {
        String text = response == null ? "" : response;
        if (request.getConvergencePolicy() == null || request.getConvergencePolicy().getMaxResponseChars() == null) {
            return text;
        }
        int maxResponseChars = request.getConvergencePolicy().getMaxResponseChars();
        if (maxResponseChars <= 0 || text.length() <= maxResponseChars) {
            return text;
        }
        return text.substring(0, maxResponseChars);
    }

    private String readText(java.io.InputStream inputStream) {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private void readOutput(InputStream inputStream, ClaudeCodeStreamCollector collector) {
        try (inputStream; BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                collector.acceptLine(line);
            }
        } catch (Exception e) {
            collector.acceptLine(e.getMessage());
        }
    }
}
