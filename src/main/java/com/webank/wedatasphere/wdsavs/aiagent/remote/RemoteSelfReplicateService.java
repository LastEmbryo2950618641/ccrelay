package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateProgress;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateResponse;
import com.webank.wedatasphere.wdsavs.aiagent.service.SshDeployExecutor;
import com.webank.wedatasphere.wdsavs.aiagent.service.SshDeployRequest;
import com.webank.wedatasphere.wdsavs.aiagent.service.SshDeployResult;

import java.util.function.Consumer;

public class RemoteSelfReplicateService {

    private final SshDeployExecutor sshDeployExecutor;

    public RemoteSelfReplicateService(SshDeployExecutor sshDeployExecutor) {
        this.sshDeployExecutor = sshDeployExecutor;
    }

    public SelfReplicateResponse execute(SelfReplicateRequest request) {
        return execute(request, progress -> { });
    }

    public SelfReplicateResponse execute(SelfReplicateRequest request, Consumer<SelfReplicateProgress> progressConsumer) {
        if (request == null) {
            throw new IllegalArgumentException("SelfReplicateRequest is required");
        }
        long startedTime = System.currentTimeMillis();
        SshDeployRequest sshRequest = toSshDeployRequest(request);
        sshRequest.setProgressListener(progress -> {
            long updatedTime = progress.getUpdatedTime() == null ? System.currentTimeMillis() : progress.getUpdatedTime();
            progressConsumer.accept(new SelfReplicateProgress(
                    null,
                    request.getTaskId(),
                    request.getSourceNodeId(),
                    request.getTargetNodeId(),
                    progress.getPhase(),
                    progress.getProgressPercent(),
                    progress.getBytesTransferred(),
                    progress.getTotalBytes(),
                    null,
                    null,
                    startedTime,
                    updatedTime,
                    Math.max(0L, updatedTime - startedTime),
                    progress.getMessage()));
        });
        SshDeployResult result = sshDeployExecutor.deploy(sshRequest);
        SelfReplicateResponse response = new SelfReplicateResponse(
                true,
                result.isSuccess(),
                result.isSuccess() ? "WAIT_REGISTER" : "FAILED",
                result.isSuccess() ? "WAIT_REGISTER" : "CENTER_DEPLOY_FALLBACK",
                result.getExitCode(),
                result.getStdoutSummary(),
                result.getStderrSummary());
        response.setResolvedRelayPort(result.getResolvedRelayPort());
        response.setResolvedRemoteDirectory(result.getResolvedRemoteDirectory());
        return response;
    }

    private SshDeployRequest toSshDeployRequest(SelfReplicateRequest request) {
        requireText(request.getHost(), "host");
        requireText(request.getUsername(), "username");
        requireText(request.getScriptPath(), "scriptPath");
        SshDeployRequest sshRequest = new SshDeployRequest();
        sshRequest.setHost(request.getHost());
        sshRequest.setPort(request.getPort() == null || request.getPort() <= 0 ? 22 : request.getPort());
        sshRequest.setUsername(request.getUsername());
        sshRequest.setScriptPath(request.getScriptPath());
        sshRequest.setArtifactPath(request.getArtifactPath());
        sshRequest.setRemoteDirectory(resolveRemoteDirectory(request));
        sshRequest.setRelayPort(request.getRelayPort() == null
                ? nodePort(request.getTargetNodeId()) : request.getRelayPort());
        sshRequest.setReplaceExistingRelay(request.getReplaceExistingRelay());
        sshRequest.setTimeoutMs(request.getTimeoutMs());
        sshRequest.setCommandArguments(request.getCommandArguments());
        return sshRequest;
    }

    private String resolveRemoteDirectory(SelfReplicateRequest request) {
        if (request.getRemoteDirectory() != null && !request.getRemoteDirectory().trim().isEmpty()) {
            return request.getRemoteDirectory().replace("\\", "/");
        }
        return null;
    }

    private Integer nodePort(String nodeId) {
        if (nodeId == null || nodeId.trim().isEmpty()) {
            return null;
        }
        int separator = nodeId.lastIndexOf(':');
        if (separator < 0 || separator == nodeId.length() - 1) {
            return null;
        }
        try {
            int port = Integer.parseInt(nodeId.substring(separator + 1));
            return port > 0 ? port : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
