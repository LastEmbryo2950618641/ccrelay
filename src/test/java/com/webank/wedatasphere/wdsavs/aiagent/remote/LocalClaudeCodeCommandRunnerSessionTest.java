package com.webank.wedatasphere.wdsavs.aiagent.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalClaudeCodeCommandRunnerSessionTest {

    @TempDir
    Path tempDir;

    private final LocalClaudeCodeCommandRunner runner = new LocalClaudeCodeCommandRunner();

    @Test
    void startsNamedClaudeSessionOnFirstTurn() {
        RemoteCcExecutionRequest request = request(false);

        List<String> commandLine = runner.commandLine(request);

        assertTrue(commandLine.contains("--session-id"));
        assertFalse(commandLine.contains("--resume"));
        assertEquals("11111111-1111-1111-1111-111111111111",
                commandLine.get(commandLine.indexOf("--session-id") + 1));
    }

    @Test
    void resumesNamedClaudeSessionOnLaterTurn() {
        RemoteCcExecutionRequest request = request(true);

        List<String> commandLine = runner.commandLine(request);

        assertTrue(commandLine.contains("--resume"));
        assertFalse(commandLine.contains("--session-id"));
        assertEquals("11111111-1111-1111-1111-111111111111",
                commandLine.get(commandLine.indexOf("--resume") + 1));
    }

    @Test
    void retriesStartedClaudeSessionWithResume() {
        List<Boolean> resumeStates = new ArrayList<>();
        LocalClaudeCodeCommandRunner retryingRunner = new LocalClaudeCodeCommandRunner() {
            private int attempt;

            @Override
            ExecutionAttempt executeOnce(RemoteCcExecutionRequest request, long timeoutMs) {
                resumeStates.add(request.isResumeModelSession());
                attempt++;
                String status = attempt == 1 ? "FAILED" : "SUCCESS";
                String answer = attempt == 1 ? "403 insufficient balance" : "recovered";
                return new ExecutionAttempt(
                        new com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse(
                                answer, status, "trace-" + attempt),
                        true);
            }
        };
        RemoteCcExecutionRequest request = request(false);
        var policy = new com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConvergencePolicy();
        policy.setMaxRetries(1);
        policy.setRetryDelayMs(0L);
        request.setConvergencePolicy(policy);
        request.setTimeoutMs(1000L);

        var response = retryingRunner.execute(request);

        assertEquals(List.of(false, true), resumeStates);
        assertEquals("SUCCESS", response.getStatus());
        assertEquals("recovered", response.getAnswer());
        assertEquals(true, response.getMetadata().get(
                RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA));
    }

    @Test
    void isolatesClaudeSettingsFromUserConfiguration() {
        RemoteCcExecutionRequest request = request(false);
        request.setClaudeSettingsFile(tempDir.resolve("isolated settings.json").toString());

        List<String> commandLine = runner.commandLine(request);

        assertTrue(commandLine.contains("--bare"));
        assertEquals("dontAsk", commandLine.get(commandLine.indexOf("--permission-mode") + 1));
        assertEquals("user", commandLine.get(commandLine.indexOf("--setting-sources") + 1));
        assertEquals(request.getClaudeSettingsFile(), commandLine.get(commandLine.indexOf("--settings") + 1));
    }

    @Test
    void materializesDynamicClaudeSettingsAsAFileArgument() throws Exception {
        RemoteCcExecutionRequest request = request(false);
        request.setClaudeSettingsFile(tempDir.resolve("settings.json").toString());
        request.setClaudeSettingsJson("{\"permissions\":{\"allow\":[\"Bash(ccrelay-cli.cmd *)\"]}}");

        Path dynamicSettings = runner.writeRequestSettingsFile(request);
        List<String> commandLine = runner.commandLine(request, dynamicSettings);

        assertEquals(request.getClaudeSettingsJson(), Files.readString(dynamicSettings));
        assertEquals(dynamicSettings.toString(), commandLine.get(commandLine.indexOf("--settings") + 1));
        Files.delete(dynamicSettings);
    }

    @Test
    void timeoutStopsDescendantProcess() throws Exception {
        Path marker = tempDir.resolve("child-finished.txt");
        RemoteCcExecutionRequest request = javaHelperRequest("spawn-child", marker.toString(), "1200");
        request.setTimeoutMs(250L);

        assertEquals("TIMEOUT", runner.execute(request).getStatus());
        Thread.sleep(1500L);

        assertFalse(Files.exists(marker));
    }

    @Test
    void retriesShareOneTimeoutBudgetAndTimeoutIsNotRetried() throws Exception {
        Path counter = tempDir.resolve("attempts.txt");
        RemoteCcExecutionRequest request = javaHelperRequest("fail-after", counter.toString(), "300");
        request.setTimeoutMs(500L);
        var policy = new com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConvergencePolicy();
        policy.setMaxRetries(3);
        policy.setRetryDelayMs(0L);
        request.setConvergencePolicy(policy);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertEquals("TIMEOUT", runner.execute(request).getStatus()));

        int attempts = Files.readAllLines(counter).size();
        assertTrue(attempts >= 1 && attempts <= 2);
    }

    @Test
    void timeoutDoesNotStartAnotherRetry() throws Exception {
        Path counter = tempDir.resolve("timeout-attempts.txt");
        RemoteCcExecutionRequest request = javaHelperRequest("fail-after", counter.toString(), "1000");
        request.setTimeoutMs(200L);
        var policy = new com.webank.wedatasphere.wdsavs.aiagent.model.ClaudeCodeConvergencePolicy();
        policy.setMaxRetries(3);
        policy.setRetryDelayMs(0L);
        request.setConvergencePolicy(policy);

        assertEquals("TIMEOUT", runner.execute(request).getStatus());

        assertEquals(1, Files.readAllLines(counter).size());
    }

    private RemoteCcExecutionRequest request(boolean resume) {
        RemoteCcExecutionRequest request = new RemoteCcExecutionRequest();
        request.setCommand("claude");
        request.setArguments(List.of("--print"));
        request.setModelSessionId("11111111-1111-1111-1111-111111111111");
        request.setResumeModelSession(resume);
        return request;
    }

    private RemoteCcExecutionRequest javaHelperRequest(String... helperArguments) {
        RemoteCcExecutionRequest request = new RemoteCcExecutionRequest();
        request.setCommand(Path.of(System.getProperty("java.home"), "bin", javaCommand()).toString());
        request.setArguments(new java.util.ArrayList<>(List.of(
                "-cp", System.getProperty("java.class.path"),
                LocalClaudeCodeCommandRunnerSessionTest.class.getName())));
        request.getArguments().addAll(List.of(helperArguments));
        request.setPrompt("");
        return request;
    }

    private String javaCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    public static void main(String[] arguments) throws Exception {
        if ("spawn-child".equals(arguments[0])) {
            new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin",
                            System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    LocalClaudeCodeCommandRunnerSessionTest.class.getName(),
                    "write-after", arguments[1], arguments[2]).start();
            Thread.sleep(10000L);
        } else if ("write-after".equals(arguments[0])) {
            Thread.sleep(Long.parseLong(arguments[2]));
            Files.writeString(Path.of(arguments[1]), "finished");
        } else if ("fail-after".equals(arguments[0])) {
            Files.writeString(Path.of(arguments[1]), "attempt\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            Thread.sleep(Long.parseLong(arguments[2]));
            System.exit(1);
        }
    }
}
