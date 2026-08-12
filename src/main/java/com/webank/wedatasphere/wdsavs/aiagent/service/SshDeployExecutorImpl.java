package com.webank.wedatasphere.wdsavs.aiagent.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SshDeployExecutorImpl implements SshDeployExecutor {

    private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final int DEFAULT_RELAY_PORT = 18091;
    private static final String DEFAULT_RELAY_PORT_RANGE = "18091-18191";
    private static final String DEFAULT_PRODUCT_NAME = "ccrelay";
    private static final String DEFAULT_REMOTE_DIRECTORY_NAME = "ccrelay";
    private static final String DEFAULT_REMOTE_DIRECTORY_TEMPLATE = "/home/${sshUser}/${productName}/${host}-${relayPort}";
    private static final String REMOTE_DIRECTORY_TEMPLATE_KEY = "wdsavs.ai.relay.remote-directory-template";
    private static final String REMOTE_DIRECTORY_TEMPLATE_ENV = "WDSAVS_AI_RELAY_REMOTE_DIRECTORY_TEMPLATE";
    private static final String PRODUCT_NAME_KEY = "wdsavs.ai.relay.product-name";
    private static final String PRODUCT_NAME_ENV = "WDSAVS_AI_RELAY_PRODUCT_NAME";
    private static final String RELAY_PORT_RANGE_KEY = "wdsavs.ai.relay.port-range";
    private static final String RELAY_PORT_RANGE_ENV = "WDSAVS_AI_RELAY_PORT_RANGE";
    private static final String RELAY_AUTO_PORT_ENABLED_KEY = "wdsavs.ai.relay.auto-port-selection-enabled";
    private static final String RELAY_AUTO_PORT_ENABLED_ENV = "WDSAVS_AI_RELAY_AUTO_PORT_SELECTION_ENABLED";
    private static final List<String> DIRECTORY_ARCHIVE_EXCLUDES = List.of(
            "*.bak*",
            "*.db",
            "*.db-*",
            "*.log",
            "*.pid",
            "*.sqlite",
            "*.sqlite-*",
            "*.tmp",
            "*.extract",
            "*.extract/*",
            ".git",
            ".git/*",
            ".cache",
            ".cache/*",
            "runtime-windows",
            "runtime-windows/*",
            "bin/claude",
            "config/claude-runtime/skills",
            "config/claude-runtime/skills/*",
            "config/*node-id*.txt");

    @Override
    public SshDeployResult deploy(SshDeployRequest request) {
        validate(request);
        reportProgress(request, "VALIDATING", 2, 0L, null, "部署请求校验完成");
        Integer relayPort = null;
        String remoteDirectory = null;
        try {
            int port = request.getPort() == null || request.getPort() <= 0 ? 22 : request.getPort();
            long timeoutMs = timeoutMs(request);
            String target = request.getUsername() + "@" + request.getHost();
            relayPort = resolveRelayPort(request, port, target, timeoutMs);
            remoteDirectory = resolveRemoteDirectory(request, relayPort);
            reportProgress(request, "TARGET_RESOLVED", 10, 0L, null,
                    "目标端口和工作目录已确定");
            if (isLocalTarget(request)) {
                reportProgress(request, "LOCAL_INSTALL", 25, 0L, null, "开始本机安装 Relay");
                SshDeployResult localResult = deployLocal(request, relayPort, remoteDirectory, timeoutMs);
                reportProgress(request, localResult.isSuccess() ? "WAIT_REGISTER" : "FAILED",
                        localResult.isSuccess() ? 100 : 25, 0L, null,
                        localResult.isSuccess() ? "Relay 已启动，等待中心注册与心跳" : "本机 Relay 安装失败");
                return localResult;
            }
            String scriptName = fileName(request.getScriptPath());
            String artifactName = request.getArtifactPath() == null || request.getArtifactPath().trim().isEmpty()
                    ? null
                    : fileName(request.getArtifactPath());
            boolean artifactDirectory = artifactName != null && isArtifactDirectory(request, timeoutMs);
            String remoteArtifactPath = artifactName == null ? null
                    : resolveRemoteArtifactPath(remoteDirectory, artifactName, artifactDirectory);

            if (Boolean.TRUE.equals(request.getReplaceExistingRelay())) {
                reportProgress(request, "STOPPING_EXISTING_RELAY", 15, 0L, null, "停止目标端口上的旧 Relay");
                CommandResult stopExisting = run(command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(port), target,
                        remoteShell(stopExistingRelayCommand(relayPort))), timeoutMs);
                if (stopExisting.exitCode != 0) {
                    reportProgress(request, "FAILED", 15, 0L, null, "停止旧 Relay 失败");
                    return withResolvedLayout(stopExisting.toDeployResult(), relayPort, remoteDirectory);
                }
            }

            reportProgress(request, "PREPARING_DIRECTORY", 20, 0L, null,
                    Boolean.TRUE.equals(request.getReplaceExistingRelay())
                            ? "清理已有 Relay 文件并重建目标工作目录"
                            : "准备目标工作目录");
            CommandResult mkdirResult = run(command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(port), target,
                    prepareRemoteDirectoryCommand(remoteDirectory,
                            Boolean.TRUE.equals(request.getReplaceExistingRelay()))), timeoutMs);
            if (mkdirResult.exitCode != 0) {
                reportProgress(request, "FAILED", 20, 0L, null, "准备目标工作目录失败");
                return withResolvedLayout(mkdirResult.toDeployResult(), relayPort, remoteDirectory);
            }

            if (artifactName != null) {
                Long totalBytes = estimateTransferBytes(request.getArtifactPath());
                reportProgress(request, "COPYING_ARTIFACT", 25, 0L, totalBytes, "开始传输 Relay 制品");
                CommandResult copyArtifact = copyPath(request, port, target, request.getArtifactPath(), remoteArtifactPath,
                        timeoutMs, new TransferProgress(request, "COPYING_ARTIFACT", 25, 75, totalBytes));
                if (copyArtifact.exitCode != 0) {
                    reportProgress(request, "FAILED", 25, 0L, totalBytes, "Relay 制品传输失败");
                    return withResolvedLayout(copyArtifact.toDeployResult(), relayPort, remoteDirectory);
                }
                reportProgress(request, "ARTIFACT_COPIED", 75, totalBytes, totalBytes, "Relay 制品传输完成");
            }

            reportProgress(request, "COPYING_INSTALLER", 80, 0L, null, "传输安装脚本");
            CommandResult copyScript = copyPath(request, port, target, request.getScriptPath(), remoteDirectory + "/" + scriptName,
                    timeoutMs, null);
            if (copyScript.exitCode != 0) {
                reportProgress(request, "FAILED", 80, 0L, null, "安装脚本传输失败");
                return withResolvedLayout(copyScript.toDeployResult(), relayPort, remoteDirectory);
            }

            String remoteScript = remoteDirectory + "/" + scriptName;
            List<String> remoteArgs = new ArrayList<>();
            if (artifactName != null) {
                remoteArgs.add(singleQuote(remoteArtifactPath));
            }
            List<String> commandArguments = commandArgumentsWithRelayPort(request.getCommandArguments(), relayPort);
            if (commandArguments != null) {
                for (String arg : commandArguments) {
                    remoteArgs.add(singleQuote(arg));
                }
            }
            String remoteCommand = "/bin/sh " + singleQuote(remoteScript)
                    + (remoteArgs.isEmpty() ? "" : " " + String.join(" ", remoteArgs));
            reportProgress(request, "STARTING_RELAY", 90, 0L, null, "启动 Relay 并等待本机健康检查");
            CommandResult execute = run(command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(port), target, remoteCommand), timeoutMs);
            reportProgress(request, execute.exitCode == 0 ? "WAIT_REGISTER" : "FAILED",
                    execute.exitCode == 0 ? 100 : 90, 0L, null,
                    execute.exitCode == 0 ? "Relay 已启动，等待中心注册与心跳" : "Relay 启动或健康检查失败");
            return withResolvedLayout(execute.toDeployResult(), relayPort, remoteDirectory);
        } catch (Exception e) {
            reportProgress(request, "FAILED", null, 0L, null, e.getMessage());
            return withResolvedLayout(new SshDeployResult(false, null, "", e.getMessage()), relayPort, remoteDirectory);
        }
    }

    private void validate(SshDeployRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("SshDeployRequest is required");
        }
        requireText(request.getHost(), "host");
        requireText(request.getUsername(), "username");
        requireText(request.getScriptPath(), "scriptPath");
        if (!localPathExists(request.getScriptPath()) && !hasRemoteSource(request)) {
            throw new IllegalArgumentException("scriptPath does not exist: " + request.getScriptPath());
        }
        if (request.getArtifactPath() != null && !request.getArtifactPath().trim().isEmpty()) {
            if (!localPathExists(request.getArtifactPath()) && !hasRemoteSource(request)) {
                throw new IllegalArgumentException("artifactPath does not exist: " + request.getArtifactPath());
            }
        }
    }

    boolean isLocalTarget(SshDeployRequest request) {
        try {
            String host = request.getHost().trim().toLowerCase();
            String localHost = java.net.InetAddress.getLocalHost().getHostName().toLowerCase();
            String localAddress = java.net.InetAddress.getLocalHost().getHostAddress();
            return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                    || host.equals(localHost) || host.equals(localAddress);
        } catch (Exception ignored) {
            return false;
        }
    }

    private SshDeployResult deployLocal(SshDeployRequest request, int relayPort, String remoteDirectory,
                                        long timeoutMs) throws Exception {
        Path sourceArtifact = localExistingPath(request.getArtifactPath());
        Path sourceScript = localExistingPath(request.getScriptPath());
        if (sourceArtifact == null || !Files.isDirectory(sourceArtifact)) {
            return withResolvedLayout(new SshDeployResult(false, null, "", "Local artifact directory does not exist"), relayPort, remoteDirectory);
        }
        Path targetDirectory = Path.of(remoteDirectory);
        Files.createDirectories(targetDirectory);
        if (sourceScript != null && Files.isRegularFile(sourceScript)) {
            Files.copy(sourceScript, targetDirectory.resolve(sourceScript.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        copyDirectoryLocal(sourceArtifact, targetDirectory);
        Path script = targetDirectory.resolve(sourceScript == null ? "install-relay.sh" : sourceScript.getFileName().toString());
        List<String> command = new ArrayList<>();
        command.add("/bin/sh");
        command.add(script.toString());
        command.add(targetDirectory.toString());
        command.addAll(commandArgumentsWithRelayPort(request.getCommandArguments(), relayPort));
        CommandResult result = run(command, timeoutMs);
        return withResolvedLayout(result.toDeployResult(), relayPort, remoteDirectory);
    }

    private void copyDirectoryLocal(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            paths.forEach(path -> {
                try {
                    Path destination = target.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    throw new LocalCopyException(e);
                }
            });
        } catch (LocalCopyException e) {
            throw e.cause;
        }
    }

    private boolean isArtifactDirectory(SshDeployRequest request, long timeoutMs) throws Exception {
        Path localArtifact = localExistingPath(request.getArtifactPath());
        if (localArtifact != null) {
            return Files.isDirectory(localArtifact);
        }
        if (!hasRemoteSource(request)) {
            return false;
        }
        String sourceTarget = request.getSourceUsername() + "@" + request.getSourceHost();
        int sourcePort = request.getSourcePort() == null || request.getSourcePort() <= 0 ? 22 : request.getSourcePort();
        return probeRemotePath(sourcePort, sourceTarget, request.getArtifactPath(), timeoutMs).kind == RemotePathKind.DIRECTORY;
    }

    String resolveRemoteArtifactPath(String remoteDirectory, String artifactName, boolean artifactDirectory) {
        return artifactDirectory ? remoteDirectory : remoteDirectory + "/" + artifactName;
    }

    private static final class LocalCopyException extends RuntimeException {
        private final Exception cause;

        private LocalCopyException(Exception cause) {
            this.cause = cause;
        }
    }

    String resolveRemoteDirectory(SshDeployRequest request, int relayPort) {
        if (request.getRemoteDirectory() != null && !request.getRemoteDirectory().trim().isEmpty()) {
            return request.getRemoteDirectory().replace("\\", "/");
        }
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("sshUser", request.getUsername());
        variables.put("loginUser", request.getUsername());
        variables.put("host", request.getHost());
        variables.put("relayPort", String.valueOf(relayPort));
        variables.put("productName", runtimeConfigValue(PRODUCT_NAME_KEY, PRODUCT_NAME_ENV, DEFAULT_PRODUCT_NAME));
        variables.put("nodeId", request.getHost() + ":" + relayPort);
        String template = runtimeConfigValue(REMOTE_DIRECTORY_TEMPLATE_KEY, REMOTE_DIRECTORY_TEMPLATE_ENV, DEFAULT_REMOTE_DIRECTORY_TEMPLATE);
        String rendered = renderTemplate(template, variables);
        if (rendered == null || rendered.trim().isEmpty()) {
            return "/home/" + request.getUsername() + "/" + DEFAULT_REMOTE_DIRECTORY_NAME;
        }
        return rendered.replace("\\", "/");
    }

    int resolveRelayPort(SshDeployRequest request, int sshPort, String target, long timeoutMs) throws Exception {
        Integer explicitPort = request.getRelayPort();
        if (explicitPort == null || explicitPort <= 0) {
            explicitPort = relayPortFromCommandArguments(request.getCommandArguments());
        }
        if (explicitPort != null && explicitPort > 0) {
            if (Boolean.TRUE.equals(request.getReplaceExistingRelay())
                    || isRemotePortAvailable(sshPort, target, explicitPort, timeoutMs)) {
                return explicitPort;
            }
            throw new IllegalStateException("Requested relay port is already in use: " + explicitPort);
        }

        int defaultPort = DEFAULT_RELAY_PORT;
        if (!runtimeBoolean(RELAY_AUTO_PORT_ENABLED_KEY, RELAY_AUTO_PORT_ENABLED_ENV, true)) {
            return defaultPort;
        }

        List<Integer> candidates = parsePortCandidates(runtimeConfigValue(RELAY_PORT_RANGE_KEY, RELAY_PORT_RANGE_ENV, DEFAULT_RELAY_PORT_RANGE), defaultPort);
        for (Integer candidate : candidates) {
            if (candidate != null && candidate > 0 && isRemotePortAvailable(sshPort, target, candidate, timeoutMs)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No available relay port found in configured range");
    }

    boolean isRemotePortAvailable(int sshPort, String target, int relayPort, long timeoutMs) throws Exception {
        CommandResult result = run(remotePortProbeCommand(sshPort, target, relayPort), timeoutMs);
        if (result.exitCode == 255) {
            throw new IllegalStateException("Failed to probe remote relay port " + relayPort + ": " + valueOrDefault(result.stderr, ""));
        }
        return result.exitCode == 0;
    }

    List<String> remotePortProbeCommand(int sshPort, String target, int relayPort) {
        String probeCommand = String.join(" && ",
                "/bin/sh -c " + singleQuote(
                        "if command -v ss >/dev/null 2>&1; then " +
                                "if ss -ltn 2>/dev/null | /usr/bin/awk -v port=\":"
                                + relayPort + "\" '$4 ~ port { found=1 } END { exit found ? 0 : 1 }'; then exit 1; else exit 0; fi; " +
                                "elif command -v lsof >/dev/null 2>&1; then if lsof -iTCP:" + relayPort + " -sTCP:LISTEN >/dev/null 2>&1; then exit 1; else exit 0; fi; " +
                                "else exit 0; fi"));
        return command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
                "-p", String.valueOf(sshPort), target, probeCommand);
    }

    List<Integer> parsePortCandidates(String specification, int defaultPort) {
        List<Integer> candidates = new ArrayList<>();
        if (specification == null || specification.trim().isEmpty()) {
            candidates.add(defaultPort);
            return candidates;
        }
        for (String part : specification.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int dash = token.indexOf('-');
            if (dash > 0) {
                int start = parsePort(token.substring(0, dash), defaultPort);
                int end = parsePort(token.substring(dash + 1), start);
                if (end < start) {
                    int swap = start;
                    start = end;
                    end = swap;
                }
                for (int current = start; current <= end; current++) {
                    candidates.add(current);
                }
                continue;
            }
            candidates.add(parsePort(token, defaultPort));
        }
        if (candidates.isEmpty()) {
            candidates.add(defaultPort);
        }
        return candidates;
    }

    String renderTemplate(String template, Map<String, String> variables) {
        String result = template == null ? null : template;
        if (result == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace("${" + entry.getKey() + "}", value);
        }
        return result;
    }

    List<String> commandArgumentsWithRelayPort(List<String> commandArguments, int relayPort) {
        List<String> resolved = new ArrayList<>();
        boolean hasRelayPort = false;
        if (commandArguments != null) {
            for (String argument : commandArguments) {
                if (argument != null && (argument.startsWith("--server.port=") || argument.startsWith("--wdsavs.ai.remote-cc.relay.port="))) {
                    hasRelayPort = true;
                }
                if (argument != null && !argument.trim().isEmpty()) {
                    resolved.add(argument);
                }
            }
        }
        if (!hasRelayPort) {
            resolved.add(0, "--server.port=" + relayPort);
        }
        return resolved;
    }

    String stopExistingRelayCommand(int relayPort) {
        String awkProgram = "/RemoteCcRelayServer/ && ("
                + "$0 ~ (\"--server.port=\" port \"([[:space:]]|$)\") || "
                + "$0 ~ (\"--wdsavs.ai.remote-cc.relay.port=\" port \"([[:space:]]|$)\")) { print $1 }";
        return "relay_pids=\"$(/bin/ps -eo pid=,args= 2>/dev/null | /usr/bin/awk -v port="
                + singleQuote(String.valueOf(relayPort)) + " " + singleQuote(awkProgram) + ")\"; "
                + "if [ -n \"$relay_pids\" ]; then "
                + "/bin/kill $relay_pids >/dev/null 2>&1 || true; "
                + "remaining=\"$relay_pids\"; attempts=0; "
                + "while [ -n \"$remaining\" ] && [ \"$attempts\" -lt 10 ]; do "
                + "/bin/sleep 1; next=\"\"; for pid in $remaining; do "
                + "if /bin/kill -0 \"$pid\" >/dev/null 2>&1; then next=\"$next $pid\"; fi; done; "
                + "remaining=\"$next\"; attempts=$((attempts + 1)); done; "
                + "if [ -n \"$remaining\" ]; then /bin/kill -9 $remaining >/dev/null 2>&1 || true; /bin/sleep 1; fi; "
                + "for pid in $remaining; do if /bin/kill -0 \"$pid\" >/dev/null 2>&1; then "
                + "echo \"Failed to stop existing RemoteCcRelayServer PID $pid on port " + relayPort + "\" >&2; exit 41; fi; done; "
                + "fi";
    }

    String prepareRemoteDirectoryCommand(String remoteDirectory, boolean replaceExistingRelay) {
        String quoted = singleQuote(remoteDirectory);
        if (replaceExistingRelay) {
            return "/bin/rm -rf -- " + quoted + " && /bin/mkdir -p " + quoted;
        }
        return "/bin/mkdir -p " + quoted;
    }

    private Integer relayPortFromCommandArguments(List<String> commandArguments) {
        if (commandArguments == null) {
            return null;
        }
        for (String argument : commandArguments) {
            if (argument == null) {
                continue;
            }
            if (argument.startsWith("--server.port=")) {
                return parsePort(argument.substring("--server.port=".length()), -1);
            }
            if (argument.startsWith("--wdsavs.ai.remote-cc.relay.port=")) {
                return parsePort(argument.substring("--wdsavs.ai.remote-cc.relay.port=".length()), -1);
            }
        }
        return null;
    }

    private String runtimeConfigValue(String key, String envKey, String defaultValue) {
        String property = System.getProperty(key);
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }
        String environment = System.getenv(envKey);
        if (environment != null && !environment.trim().isEmpty()) {
            return environment.trim();
        }
        return defaultValue;
    }

    private boolean runtimeBoolean(String key, String envKey, boolean defaultValue) {
        String value = runtimeConfigValue(key, envKey, String.valueOf(defaultValue));
        return value == null || value.trim().isEmpty() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    private int parsePort(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private SshDeployResult withResolvedLayout(SshDeployResult result, Integer relayPort, String remoteDirectory) {
        if (result == null) {
            return null;
        }
        result.setResolvedRelayPort(relayPort);
        result.setResolvedRemoteDirectory(remoteDirectory);
        return result;
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private CommandResult copyPath(SshDeployRequest request, int port, String target, String sourcePath, String remotePath,
                                   long timeoutMs, TransferProgress transferProgress) throws Exception {
        Path localSource = localExistingPath(sourcePath);
        if (localSource != null && Files.isDirectory(localSource)) {
            return copyLocalDirectory(localSource, port, target, remotePath, timeoutMs, transferProgress);
        }
        if (localSource != null) {
            return copyFile(port, target, localSource, remotePath, shouldMarkExecutable(localSource), timeoutMs);
        }
        return copyRemotePath(request, port, target, sourcePath, remotePath, timeoutMs, transferProgress);
    }

    private CommandResult copyLocalDirectory(Path localSource, int targetPort, String target, String remotePath,
                                             long timeoutMs, TransferProgress transferProgress) throws Exception {
        boolean preserveRuntimeArchive = shouldPreserveRuntimeArchive(localSource);
        Path archiveSource = localSource;
        Path snapshotRoot = null;
        if (preserveRuntimeArchive) {
            snapshotRoot = createStableArchiveSnapshot(localSource);
            archiveSource = snapshotRoot.resolve(localSource.getFileName().toString());
        }
        String sourceParent = parentDirectory(archiveSource.toString().replace('\\', '/'));
        String sourceName = archiveSource.getFileName().toString();
        String extractCommand = buildDirectoryExtractCommand("-", remotePath, sourceName, false);
        try {
            return runPipeline(
                    localDirectoryArchiveCommand(sourceParent, sourceName, preserveRuntimeArchive),
                    command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(targetPort), target,
                            remoteShell(extractCommand)),
                    timeoutMs,
                    transferProgress);
        } finally {
            deleteTree(snapshotRoot);
        }
    }

    private Path createStableArchiveSnapshot(Path source) throws Exception {
        Path parent = source.toAbsolutePath().getParent();
        Path snapshotRoot = Files.createTempDirectory(parent, ".ccrelay-archive-");
        Path snapshot = snapshotRoot.resolve(source.getFileName().toString());
        Files.createDirectories(snapshot);
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws java.io.IOException {
                    Path relative = source.relativize(directory);
                    if (!relative.toString().isEmpty() && shouldExcludeSnapshotPath(relative, true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(snapshot.resolve(relative.toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws java.io.IOException {
                    Path relative = source.relativize(file);
                    if (shouldExcludeSnapshotPath(relative, true)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path destination = snapshot.resolve(relative.toString());
                    Files.createDirectories(destination.getParent());
                    try {
                        Files.createLink(destination, file);
                    } catch (Exception ignored) {
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return snapshotRoot;
        } catch (Exception e) {
            deleteTree(snapshotRoot);
            throw e;
        }
    }

    private boolean shouldExcludeSnapshotPath(Path relative, boolean preserveRuntimeArchive) {
        String normalized = relative.toString().replace('\\', '/');
        String fileName = relative.getFileName() == null ? "" : relative.getFileName().toString();
        if (normalized.equals("runtime") || normalized.startsWith("runtime/")) {
            return preserveRuntimeArchive;
        }
        if (normalized.equals("runtime-windows") || normalized.startsWith("runtime-windows/")) {
            return true;
        }
        if (normalized.equals(".git") || normalized.startsWith(".git/")
                || normalized.equals(".cache") || normalized.startsWith(".cache/")
                || normalized.contains(".extract")) {
            return true;
        }
        if (fileName.endsWith(".log") || fileName.endsWith(".pid") || fileName.endsWith(".tmp")
                || fileName.endsWith(".db") || fileName.matches(".*\\.db-.+")
                || fileName.endsWith(".sqlite") || fileName.matches(".*\\.sqlite-.+")
                || fileName.matches(".*\\.bak.*")) {
            return true;
        }
        return normalized.equals("bin/claude") || normalized.matches("config/.+node-id.*\\.txt");
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private CommandResult copyRemotePath(SshDeployRequest request, int port, String target,
                                         String sourcePath, String remotePath, long timeoutMs,
                                         TransferProgress transferProgress) throws Exception {
        String sourceTarget = request.getSourceUsername() + "@" + request.getSourceHost();
        int sourcePort = request.getSourcePort() == null || request.getSourcePort() <= 0 ? 22 : request.getSourcePort();
        RemotePathStatus remoteStatus = probeRemotePath(sourcePort, sourceTarget, sourcePath, timeoutMs);
        if (remoteStatus.kind == RemotePathKind.MISSING) {
            String message = "Remote source path does not exist: " + sourcePath;
            return new CommandResult(remoteStatus.probeResult == null ? 1 : remoteStatus.probeResult.exitCode,
                    remoteStatus.probeResult == null ? "" : remoteStatus.probeResult.stdout,
                    remoteStatus.probeResult == null ? message : valueOrDefault(remoteStatus.probeResult.stderr, message));
        }
        if (remoteStatus.kind == RemotePathKind.DIRECTORY) {
            return copyRemoteDirectory(sourcePort, sourceTarget, sourcePath, port, target, remotePath, timeoutMs,
                    transferProgress);
        }
        return copyRemoteFile(sourcePort, sourceTarget, sourcePath, port, target, remotePath,
                shouldMarkExecutable(sourcePath), timeoutMs, transferProgress);
    }

    private CommandResult copyFile(int port, String target, Path source, String remotePath, boolean executable,
                                   long timeoutMs) throws Exception {
        String parent = parentDirectory(remotePath);
        CommandResult mkdir = run(command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(port), target,
                "/bin/mkdir -p " + singleQuote(parent)), timeoutMs);
        if (mkdir.exitCode != 0) {
            return mkdir;
        }
        String shellCommand = "/bin/cat > " + singleQuote(remotePath);
        if (executable) {
            shellCommand += " && /usr/bin/chmod +x " + singleQuote(remotePath);
        }
        return runWithInput(command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(port), target,
                remoteShell(shellCommand)), source.toFile(), timeoutMs);
    }

    private CommandResult copyRemoteFile(int sourcePort, String sourceTarget, String sourcePath,
                                         int targetPort, String target, String remotePath, boolean executable,
                                         long timeoutMs, TransferProgress transferProgress) throws Exception {
        String parent = parentDirectory(remotePath);
        CommandResult mkdir = run(command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(targetPort), target,
                "/bin/mkdir -p " + singleQuote(parent)), timeoutMs);
        if (mkdir.exitCode != 0) {
            return mkdir;
        }
        String readCommand = "/bin/cat " + singleQuote(sourcePath);
        String writeCommand = "/bin/cat > " + singleQuote(remotePath);
        if (executable) {
            writeCommand += " && /usr/bin/chmod +x " + singleQuote(remotePath);
        }
        return runPipeline(
                command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(sourcePort), sourceTarget,
                        remoteShell(readCommand)),
                command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(targetPort), target,
                        remoteShell(writeCommand)),
                timeoutMs,
                transferProgress);
    }

    private CommandResult copyRemoteDirectory(int sourcePort, String sourceTarget, String sourcePath,
                                              int targetPort, String target, String remotePath, long timeoutMs,
                                              TransferProgress transferProgress) throws Exception {
        String sourceParent = parentDirectory(sourcePath.replace('\\', '/'));
        String sourceName = fileName(sourcePath.replace('\\', '/'));
        boolean preserveRuntimeArchive = shouldPreserveRuntimeArchive(sourcePort, sourceTarget, sourcePath, timeoutMs);
        String archiveCommand = remoteDirectoryArchiveCommand(sourceParent, sourceName, preserveRuntimeArchive);
        String extractCommand = buildDirectoryExtractCommand("-", remotePath, sourceName, false);
        return runPipeline(
                command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(sourcePort), sourceTarget,
                        remoteShell(archiveCommand)),
                command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(targetPort), target,
                        remoteShell(extractCommand)),
                timeoutMs,
                transferProgress);
    }

    private String buildDirectoryExtractCommand(String archivePath, String remotePath, String extractedName, boolean removeArchive) {
        String remoteParent = parentDirectory(remotePath);
        String tempExtractRoot = remotePath + ".extract";
        String command = "/bin/rm -rf " + singleQuote(remotePath) + " " + singleQuote(tempExtractRoot)
                + " && /bin/mkdir -p " + singleQuote(remoteParent)
                + " && /bin/mkdir -p " + singleQuote(tempExtractRoot)
                + " && /bin/tar -xf " + singleQuote(archivePath) + " -C " + singleQuote(tempExtractRoot)
                + " && /bin/mv " + singleQuote(tempExtractRoot + "/" + extractedName) + " " + singleQuote(remotePath)
                + " && /bin/rm -rf " + singleQuote(tempExtractRoot);
        if (removeArchive) {
            command += " && /bin/rm -f " + singleQuote(archivePath);
        }
        return command;
    }

    List<String> localDirectoryArchiveCommand(String sourceParent, String sourceName, boolean preserveRuntimeArchive) {
        List<String> archiveCommand = new ArrayList<>();
        archiveCommand.add(tarCommandName());
        archiveCommand.add("-C");
        archiveCommand.add(sourceParent);
        archiveCommand.addAll(directoryExcludeArgs(sourceName, preserveRuntimeArchive));
        archiveCommand.add("-cf");
        archiveCommand.add("-");
        archiveCommand.add(sourceName);
        return archiveCommand;
    }

    String remoteDirectoryArchiveCommand(String sourceParent, String sourceName, boolean preserveRuntimeArchive) {
        if (preserveRuntimeArchive) {
            return remoteSlimDirectoryArchiveCommand(sourceParent, sourceName);
        }
        List<String> parts = new ArrayList<>();
        parts.add("/bin/tar -C " + singleQuote(sourceParent));
        for (String exclude : directoryExcludeArgs(sourceName, preserveRuntimeArchive)) {
            parts.add(singleQuote(exclude));
        }
        parts.add("-cf - " + singleQuote(sourceName));
        return String.join(" ", parts);
    }

    String remoteSlimDirectoryArchiveCommand(String sourceParent, String sourceName) {
        String sourceRoot = sourceName;
        List<String> command = new ArrayList<>();
        command.add("cd " + singleQuote(sourceParent));
        command.add("/usr/bin/find " + singleQuote(sourceRoot)
                + " \\( -path " + singleQuote(sourceRoot + "/runtime")
                + " -o -path " + singleQuote(sourceRoot + "/runtime-windows")
                + " -o -path " + singleQuote(sourceRoot + "/.git")
                + " -o -path " + singleQuote(sourceRoot + "/.cache")
                + " -o -name " + singleQuote("*.extract")
                + " \\) -prune -o -type f"
                + " ! -name " + singleQuote("*.bak*")
                + " ! -name " + singleQuote("*.db")
                + " ! -name " + singleQuote("*.db-*")
                + " ! -name " + singleQuote("*.log")
                + " ! -name " + singleQuote("*.pid")
                + " ! -name " + singleQuote("*.sqlite")
                + " ! -name " + singleQuote("*.sqlite-*")
                + " ! -name " + singleQuote("*.tmp")
                + " ! -path " + singleQuote(sourceRoot + "/bin/claude")
                + " ! -path " + singleQuote(sourceRoot + "/config/claude-runtime/skills")
                + " ! -path " + singleQuote(sourceRoot + "/config/claude-runtime/skills/*")
                + " ! -path " + singleQuote(sourceRoot + "/config/*node-id*.txt")
                + " -print0");
        command.add("/bin/tar --null --files-from=- -cf -");
        return String.join(" && ", command.subList(0, 1)) + " && "
                + command.get(1) + " | " + command.get(2);
    }

    List<String> directoryExcludeArgs(String sourceName, boolean preserveRuntimeArchive) {
        List<String> excludes = new ArrayList<>();
        for (String pattern : DIRECTORY_ARCHIVE_EXCLUDES) {
            excludes.add("--exclude=" + sourceName + "/" + pattern);
        }
        if (preserveRuntimeArchive) {
            excludes.add("--exclude=" + sourceName + "/runtime");
            excludes.add("--exclude=" + sourceName + "/runtime/*");
        } else {
            excludes.add("--exclude=" + sourceName + "/runtime.tar.gz");
        }
        return excludes;
    }

    boolean shouldPreserveRuntimeArchive(Path sourceDirectory) {
        if (sourceDirectory == null) {
            return false;
        }
        return Files.exists(sourceDirectory.resolve("runtime.tar.gz"));
    }

    boolean shouldPreserveRuntimeArchive(int sourcePort, String sourceTarget, String sourcePath, long timeoutMs) throws Exception {
        String normalized = sourcePath.replace('\\', '/');
        String runtimeArchivePath = normalized + "/runtime.tar.gz";
        RemotePathStatus runtimeArchive = probeRemotePath(sourcePort, sourceTarget, runtimeArchivePath, timeoutMs);
        return runtimeArchive.kind == RemotePathKind.FILE;
    }

    private RemotePathStatus probeRemotePath(int port, String target, String sourcePath, long timeoutMs) throws Exception {
        String probeCommand = "if [ -d " + singleQuote(sourcePath) + " ]; then echo DIRECTORY; "
                + "elif [ -f " + singleQuote(sourcePath) + " ]; then echo FILE; "
                + "else echo MISSING; fi";
        CommandResult result = run(command(sshCommandName(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-p", String.valueOf(port), target,
                remoteShell(probeCommand)), timeoutMs);
        String status = valueOrDefault(result.stdout, "").trim();
        if (result.exitCode != 0) {
            return new RemotePathStatus(RemotePathKind.MISSING, result);
        }
        if ("DIRECTORY".equals(status)) {
            return new RemotePathStatus(RemotePathKind.DIRECTORY, result);
        }
        if ("FILE".equals(status)) {
            return new RemotePathStatus(RemotePathKind.FILE, result);
        }
        return new RemotePathStatus(RemotePathKind.MISSING, result);
    }

    boolean shouldMarkExecutable(Path source) {
        String normalized = source.toString().replace('\\', '/');
        String fileName = source.getFileName() == null ? "" : source.getFileName().toString();
        return fileName.endsWith(".sh")
                || normalized.contains("/runtime/")
                || normalized.contains("/bin/")
                || normalized.endsWith("/jspawnhelper");
    }

    boolean shouldMarkExecutable(String sourcePath) {
        return shouldMarkExecutable(Path.of(fileName(sourcePath)));
    }

    String parentDirectory(String remotePath) {
        int index = remotePath.lastIndexOf('/');
        return index <= 0 ? "/" : remotePath.substring(0, index);
    }

    String fileName(String path) {
        String normalized = valueOrDefault(path, "").replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private List<String> command(String... parts) {
        return List.of(parts);
    }

    private String singleQuote(String value) {
        String text = value == null ? "" : value;
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private String sshCommandName() {
        return preferredCommand("/usr/bin/ssh", "ssh");
    }

    private String tarCommandName() {
        return preferredCommand("/bin/tar", "tar");
    }

    private String remoteShell(String command) {
        return "/bin/sh -c " + singleQuote(command);
    }

    private String preferredCommand(String absolutePath, String fallback) {
        if (File.separatorChar == '/' && new File(absolutePath).canExecute()) {
            return absolutePath;
        }
        return fallback;
    }

    private CommandResult run(List<String> command, long timeoutMs) throws Exception {
        return runWithInput(command, null, timeoutMs);
    }

    private CommandResult runPipeline(List<String> sourceCommand, List<String> targetCommand, long timeoutMs,
                                      TransferProgress transferProgress) throws Exception {
        Process sourceProcess = new ProcessBuilder(sourceCommand).start();
        Process targetProcess = new ProcessBuilder(targetCommand).start();
        StringBuilder stdout = new StringBuilder();
        StringBuilder sourceStderr = new StringBuilder();
        StringBuilder targetStderr = new StringBuilder();
        Thread sourceErrReader = reader(sourceProcess.getErrorStream(), sourceStderr);
        Thread targetOutReader = reader(targetProcess.getInputStream(), stdout);
        Thread targetErrReader = reader(targetProcess.getErrorStream(), targetStderr);
        sourceErrReader.start();
        targetOutReader.start();
        targetErrReader.start();
        Thread pump = pipe(sourceProcess.getInputStream(), targetProcess.getOutputStream(), transferProgress);
        pump.start();
        boolean sourceFinished = sourceProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!sourceFinished) {
            sourceProcess.destroyForcibly();
            targetProcess.destroyForcibly();
            return new CommandResult(-1, stdout.toString(), "Source command timed out: " + sourceStderr);
        }
        pump.join();
        boolean targetFinished = targetProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!targetFinished) {
            targetProcess.destroyForcibly();
            return new CommandResult(-1, stdout.toString(), "Target command timed out: " + targetStderr);
        }
        sourceErrReader.join();
        targetOutReader.join();
        targetErrReader.join();
        String combinedStderr = joinNonBlank(sourceStderr.toString(), targetStderr.toString());
        int exitCode = sourceProcess.exitValue() != 0 ? sourceProcess.exitValue() : targetProcess.exitValue();
        return new CommandResult(exitCode, stdout.toString(), combinedStderr);
    }

    private CommandResult runWithInput(List<String> command, File inputFile, long timeoutMs) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (inputFile != null) {
            builder.redirectInput(inputFile);
        }
        Process process = builder.start();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = reader(process.getInputStream(), stdout);
        Thread errReader = reader(process.getErrorStream(), stderr);
        outReader.start();
        errReader.start();
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(-1, stdout.toString(), "Command timed out: " + stderr);
        }
        outReader.join();
        errReader.join();
        return new CommandResult(process.exitValue(), stdout.toString(), stderr.toString());
    }

    private Thread reader(java.io.InputStream stream, StringBuilder sink) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!sink.isEmpty()) {
                        sink.append(System.lineSeparator());
                    }
                    sink.append(line);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private Thread pipe(InputStream inputStream, OutputStream outputStream, TransferProgress transferProgress) {
        return new Thread(() -> {
            try (InputStream in = inputStream; OutputStream out = outputStream) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    if (transferProgress != null) {
                        transferProgress.addBytes(read);
                    }
                }
                out.flush();
            } catch (Exception ignored) {
            }
        });
    }

    private Long estimateTransferBytes(String sourcePath) {
        Path source = localExistingPath(sourcePath);
        if (source == null) {
            return null;
        }
        try {
            if (Files.isRegularFile(source)) {
                return Files.size(source);
            }
            if (!Files.isDirectory(source)) {
                return null;
            }
            boolean preserveRuntimeArchive = shouldPreserveRuntimeArchive(source);
            AtomicLong total = new AtomicLong();
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    Path relative = source.relativize(directory);
                    if (!relative.toString().isEmpty() && shouldExcludeSnapshotPath(relative, preserveRuntimeArchive)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    Path relative = source.relativize(file);
                    if (!shouldExcludeSnapshotPath(relative, preserveRuntimeArchive)) {
                        total.addAndGet(Math.max(0L, attributes.size()));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return total.get() > 0L ? total.get() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void reportProgress(SshDeployRequest request, String phase, Integer percent,
                                Long bytesTransferred, Long totalBytes, String message) {
        if (request == null || request.getProgressListener() == null) {
            return;
        }
        Integer normalizedPercent = percent == null ? null : Math.max(0, Math.min(100, percent));
        request.getProgressListener().onProgress(new SshDeployProgress(
                phase,
                normalizedPercent,
                bytesTransferred,
                totalBytes,
                System.currentTimeMillis(),
                message));
    }

    private boolean hasRemoteSource(SshDeployRequest request) {
        return request != null
                && !isBlank(request.getSourceHost())
                && !isBlank(request.getSourceUsername());
    }

    private long timeoutMs(SshDeployRequest request) {
        if (request != null && request.getTimeoutMs() != null && request.getTimeoutMs() > 0L) {
            return request.getTimeoutMs();
        }
        return DEFAULT_TIMEOUT_MS;
    }

    private boolean localPathExists(String path) {
        return localExistingPath(path) != null;
    }

    private Path localExistingPath(String path) {
        if (isBlank(path)) {
            return null;
        }
        try {
            Path localPath = Path.of(path);
            return Files.exists(localPath) ? localPath : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String joinNonBlank(String first, String second) {
        if (isBlank(first)) {
            return second == null ? "" : second;
        }
        if (isBlank(second)) {
            return first;
        }
        return first + System.lineSeparator() + second;
    }

    private final class TransferProgress {
        private final SshDeployRequest request;
        private final String phase;
        private final int startPercent;
        private final int endPercent;
        private final Long totalBytes;
        private long bytesTransferred;
        private long lastReportTime;

        private TransferProgress(SshDeployRequest request, String phase, int startPercent, int endPercent, Long totalBytes) {
            this.request = request;
            this.phase = phase;
            this.startPercent = startPercent;
            this.endPercent = endPercent;
            this.totalBytes = totalBytes;
        }

        private synchronized void addBytes(long count) {
            bytesTransferred += Math.max(0L, count);
            long now = System.currentTimeMillis();
            if (now - lastReportTime < 1000L && (totalBytes == null || bytesTransferred < totalBytes)) {
                return;
            }
            lastReportTime = now;
            int percent = startPercent;
            if (totalBytes != null && totalBytes > 0L) {
                double ratio = Math.min(1.0d, (double) bytesTransferred / (double) totalBytes);
                percent = startPercent + (int) Math.floor((endPercent - startPercent) * ratio);
            }
            reportProgress(request, phase, Math.min(endPercent, percent), bytesTransferred, totalBytes,
                    "正在传输 Relay 制品");
        }
    }

    private static class CommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        private SshDeployResult toDeployResult() {
            return new SshDeployResult(exitCode == 0, exitCode, summarize(stdout), summarize(stderr));
        }

        private static String summarize(String text) {
            if (text == null) {
                return "";
            }
            return text.length() <= 4000 ? text : text.substring(0, 4000);
        }
    }

    private enum RemotePathKind {
        FILE,
        DIRECTORY,
        MISSING
    }

    private static class RemotePathStatus {
        private final RemotePathKind kind;
        private final CommandResult probeResult;

        private RemotePathStatus(RemotePathKind kind, CommandResult probeResult) {
            this.kind = kind;
            this.probeResult = probeResult;
        }
    }
}

