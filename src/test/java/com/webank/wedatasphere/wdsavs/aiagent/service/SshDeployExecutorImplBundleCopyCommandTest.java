package com.webank.wedatasphere.wdsavs.aiagent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class SshDeployExecutorImplBundleCopyCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldMarkShellScriptsExecutable() throws Exception {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();
        Path script = Files.createFile(tempDir.resolve("install-relay.sh"));

        assertTrue(executor.shouldMarkExecutable(script));
    }

    @Test
    void shouldMarkBundledRuntimeBinaryExecutable() throws Exception {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();
        Path runtimeBin = Files.createDirectories(tempDir.resolve("runtime/bin"));
        Path javaBin = Files.createFile(runtimeBin.resolve("java"));

        assertTrue(executor.shouldMarkExecutable(javaBin));
    }

    @Test
    void shouldNotMarkRegularDataFilesExecutable() throws Exception {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();
        Path dataFile = Files.createFile(tempDir.resolve("app.jar"));

        assertFalse(executor.shouldMarkExecutable(dataFile));
    }

    @Test
    void shouldResolveRemoteParentDirectory() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        assertEquals("/home/liuqi/ccrelay", executor.parentDirectory("/home/liuqi/ccrelay/app.jar"));
    }

    @Test
    void shouldResolveRemoteFileNameFromUnixStylePath() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        assertEquals("install-relay.sh", executor.fileName("/home/liuqi/ccrelay/install-relay.sh"));
    }

    @Test
    void shouldMarkRemoteShellScriptExecutable() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        assertTrue(executor.shouldMarkExecutable("/home/liuqi/ccrelay/install-relay.sh"));
    }

    @Test
    void shouldRecognizeLocalhostAsLocalDeploymentTarget() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();
        SshDeployRequest request = new SshDeployRequest();
        request.setHost("127.0.0.1");
        request.setUsername("ccrelay");

        assertTrue(executor.isLocalTarget(request));
    }

    @Test
    void shouldCompressDirectoryArchiveAndExcludeRuntimeNoise() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        var command = executor.localDirectoryArchiveCommand("/home/liuqi", "ccrelay", false);

        assertTrue(command.contains("-cf"));
        assertTrue(command.contains("--exclude=ccrelay/*.bak*"));
        assertTrue(command.contains("--exclude=ccrelay/*.log"));
        assertTrue(command.contains("--exclude=ccrelay/runtime-windows"));
        assertTrue(command.contains("--exclude=ccrelay/bin/claude"));
        assertTrue(command.contains("--exclude=ccrelay/config/claude-runtime/skills"));
        assertTrue(command.contains("--exclude=ccrelay/*.db"));
        assertFalse(command.contains("--exclude=ccrelay/tools/*.tgz"));
    }

    @Test
    void shouldBuildRemoteCompressedArchiveCommandWithExcludes() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        String command = executor.remoteDirectoryArchiveCommand("/home/liuqi", "ccrelay", false);

        assertTrue(command.contains("-cf -"));
        assertTrue(command.contains("--exclude=ccrelay/*.bak*"));
        assertTrue(command.contains("--exclude=ccrelay/runtime.tar.gz"));
        assertTrue(command.contains("--exclude=ccrelay/config/*node-id*.txt"));
    }

    @Test
    void shouldArchiveSlimRemoteBundleFromStableFileList() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        String command = executor.remoteDirectoryArchiveCommand(
                "/home/ccrelay/ccrelay", "node-18194", true);

        assertTrue(command.contains("/usr/bin/find 'node-18194'"));
        assertTrue(command.contains("-path 'node-18194/runtime'"));
        assertTrue(command.contains("! -path 'node-18194/bin/claude'"));
        assertTrue(command.contains("! -name '*.db'"));
        assertTrue(command.contains("/bin/tar --null --files-from=- -cf -"));
        assertFalse(command.contains("--exclude=node-18194/runtime.tar.gz"));
    }

    @Test
    void shouldKeepRuntimeArchiveForSlimBundleWithoutExtractedRuntime() throws Exception {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();
        Path bundle = Files.createDirectories(tempDir.resolve("ccrelay-slim"));
        Files.createFile(bundle.resolve("runtime.tar.gz"));

        assertTrue(executor.shouldPreserveRuntimeArchive(bundle));

        var command = executor.localDirectoryArchiveCommand(tempDir.toString(), bundle.getFileName().toString(), true);

        assertFalse(command.contains("--exclude=ccrelay-slim/runtime.tar.gz"));
    }

    @Test
    void shouldInstallDirectoryArtifactIntoExactRemoteDirectory() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        assertEquals(
                "/home/ccrelay/ccrelay/47.93.195.246-18192",
                executor.resolveRemoteArtifactPath(
                        "/home/ccrelay/ccrelay/47.93.195.246-18192",
                        "source-bundle",
                        true));
        assertEquals(
                "/home/ccrelay/ccrelay/47.93.195.246-18192/runtime.tar.gz",
                executor.resolveRemoteArtifactPath(
                        "/home/ccrelay/ccrelay/47.93.195.246-18192",
                        "runtime.tar.gz",
                        false));
    }

    @Test
    void shouldPreferRuntimeArchiveForFullBundleWithExtractedRuntime() throws Exception {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();
        Path bundle = Files.createDirectories(tempDir.resolve("ccrelay"));
        Files.createFile(bundle.resolve("runtime.tar.gz"));
        Files.createDirectories(bundle.resolve("runtime/bin"));
        Files.createFile(bundle.resolve("runtime/bin/java"));

        assertTrue(executor.shouldPreserveRuntimeArchive(bundle));

        var command = executor.localDirectoryArchiveCommand(tempDir.toString(), bundle.getFileName().toString(), true);

        assertFalse(command.contains("--exclude=ccrelay/runtime.tar.gz"));
        assertTrue(command.contains("--exclude=ccrelay/runtime"));
        assertFalse(command.contains("--exclude=ccrelay/tools/*.tgz"));
    }

    @Test
    void shouldRenderDefaultRemoteDirectoryWithHostAndRelayPort() {
        withSystemProperty("wdsavs.ai.relay.remote-directory-template", "/home/${sshUser}/${productName}/${host}-${relayPort}", () -> {
            withSystemProperty("wdsavs.ai.relay.product-name", "ccrelay", () -> {
                SshDeployExecutorImpl executor = new SshDeployExecutorImpl();
                SshDeployRequest request = new SshDeployRequest();
                request.setHost("10.0.0.8");
                request.setUsername("tester");

                assertEquals("/home/tester/ccrelay/10.0.0.8-18092", executor.resolveRemoteDirectory(request, 18092));
            });
        });
    }

    @Test
    void shouldPickFirstAvailableRelayPortFromConfiguredRange() throws Exception {
        withSystemProperty("wdsavs.ai.relay.port-range", "18091-18093", () -> {
            withSystemProperty("wdsavs.ai.relay.auto-port-selection-enabled", "true", () -> {
                PortAwareExecutor executor = new PortAwareExecutor(Set.of(18091, 18092));
                SshDeployRequest request = new SshDeployRequest();

                assertEquals(18093, executor.resolveRelayPort(request, 22, "tester@10.0.0.8", 1000L));
            });
        });
    }

    @Test
    void shouldPreserveMissingResolvedPortWhenRemoteProbeFails() throws Exception {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl() {
            @Override
            boolean isRemotePortAvailable(int sshPort, String target, int relayPort, long timeoutMs) {
                throw new IllegalStateException("Host key verification failed");
            }
        };
        SshDeployRequest request = new SshDeployRequest();
        request.setHost("10.0.0.8");
        request.setUsername("tester");
        request.setScriptPath("/remote/install-relay.sh");
        request.setSourceHost("source");
        request.setSourceUsername("tester");

        SshDeployResult result = executor.deploy(request);

        assertFalse(result.isSuccess());
        assertNull(result.getResolvedRelayPort());
        assertTrue(result.getStderrSummary().contains("Host key verification failed"));
        assertFalse(result.getStderrSummary().contains("intValue"));
    }

    @Test
    void shouldUseNonStrictHostKeyCheckingForRemoteProbe() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        String command = String.join(" ", executor.remotePortProbeCommand(22, "tester@10.0.0.8", 18091));

        assertTrue(command.contains("StrictHostKeyChecking=no"));
        assertFalse(command.contains("StrictHostKeyChecking=accept-new"));
    }

    @Test
    void shouldAllowExplicitOccupiedPortOnlyForReplacement() throws Exception {
        PortAwareExecutor executor = new PortAwareExecutor(Set.of(18091));
        SshDeployRequest request = new SshDeployRequest();
        request.setRelayPort(18091);
        request.setReplaceExistingRelay(true);

        assertEquals(18091, executor.resolveRelayPort(request, 22, "tester@10.0.0.8", 1000L));
    }

    @Test
    void shouldReplaceExistingRelayByDefault() {
        assertTrue(new SshDeployRequest().getReplaceExistingRelay());
    }

    @Test
    void shouldCleanProductDirectoryBeforeReplacementDeployment() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        String command = executor.prepareRemoteDirectoryCommand(
                "/home/ccrelay/ccrelay/10.0.0.8-18192", true);

        assertTrue(command.startsWith("/bin/rm -rf -- "));
        assertTrue(command.contains("&& /bin/mkdir -p"));
        assertTrue(command.contains("10.0.0.8-18192"));
    }

    @Test
    void shouldStopOnlyRelayProcessMatchingReplacementPort() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        String command = executor.stopExistingRelayCommand(18091);

        assertTrue(command.contains("/bin/ps -eo pid=,args="));
        assertTrue(command.contains("/usr/bin/awk"));
        assertTrue(command.contains("RemoteCcRelayServer"));
        assertTrue(command.contains("--server.port="));
        assertTrue(command.contains("--wdsavs.ai.remote-cc.relay.port="));
        assertTrue(command.contains("port='18091'"));
        assertTrue(command.contains("/bin/kill $relay_pids"));
        assertFalse(command.contains("fuser"));
        assertFalse(command.contains("lsof"));
    }

    @Test
    void shouldAddSelectedRelayPortToScriptArguments() {
        SshDeployExecutorImpl executor = new SshDeployExecutorImpl();

        var arguments = executor.commandArgumentsWithRelayPort(java.util.List.of("--foo=bar"), 18093);

        assertEquals("--server.port=18093", arguments.get(0));
        assertTrue(arguments.contains("--foo=bar"));
    }

    private static void withSystemProperty(String key, String value, ThrowingRunnable action) {
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, value);
            action.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class PortAwareExecutor extends SshDeployExecutorImpl {
        private final Set<Integer> busyPorts;

        private PortAwareExecutor(Set<Integer> busyPorts) {
            this.busyPorts = busyPorts;
        }

        @Override
        boolean isRemotePortAvailable(int sshPort, String target, int relayPort, long timeoutMs) {
            return !busyPorts.contains(relayPort);
        }
    }
}
