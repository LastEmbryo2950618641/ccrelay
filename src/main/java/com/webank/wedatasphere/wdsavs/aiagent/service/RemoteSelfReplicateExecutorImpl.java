package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateProgress;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.function.Consumer;

@Service
public class RemoteSelfReplicateExecutorImpl implements RemoteSelfReplicateExecutor {

    private final RestTemplate restTemplate;

    public RemoteSelfReplicateExecutorImpl(RestTemplate aiRestTemplate) {
        this.restTemplate = aiRestTemplate;
    }

    @Override
    public SelfReplicateResponse execute(String relayEndpoint, SelfReplicateRequest request) {
        return execute(relayEndpoint, request, progress -> { });
    }

    @Override
    public SelfReplicateResponse execute(String relayEndpoint, SelfReplicateRequest request,
                                         Consumer<SelfReplicateProgress> progressConsumer) {
        if (isBlank(relayEndpoint)) {
            throw new IllegalArgumentException("source relay endpoint is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("SelfReplicateRequest is required");
        }
        SelfReplicateResponse response = restTemplate.postForObject(resolveSelfReplicateEndpoint(relayEndpoint), request,
                SelfReplicateResponse.class);
        if (response == null) {
            throw new IllegalStateException("Remote self replicate returned empty response");
        }
        publishProgress(response, progressConsumer);
        if (!"RUNNING".equalsIgnoreCase(response.getStatus()) || isBlank(response.getOperationId())) {
            return response;
        }
        long operationTimeoutMs = request.getTimeoutMs() == null || request.getTimeoutMs() <= 0L
                ? 10 * 60 * 1000L
                : request.getTimeoutMs();
        long deadline = System.currentTimeMillis() + operationTimeoutMs + 60_000L;
        long pollIntervalMs = request.getProgressPollIntervalMs() == null || request.getProgressPollIntervalMs() <= 0L
                ? 2000L
                : Math.max(500L, request.getProgressPollIntervalMs());
        long heartbeatIntervalMs = request.getProgressHeartbeatIntervalMs() == null
                || request.getProgressHeartbeatIntervalMs() <= 0L
                ? 10_000L
                : Math.max(1000L, request.getProgressHeartbeatIntervalMs());
        Long lastUpdatedTime = response.getProgress() == null ? null : response.getProgress().getUpdatedTime();
        long lastPublishedTime = System.currentTimeMillis();
        String statusEndpoint = resolveSelfReplicateEndpoint(relayEndpoint) + "/" + response.getOperationId();
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Remote self replicate polling was interrupted", e);
            }
            SelfReplicateResponse current = restTemplate.getForObject(statusEndpoint, SelfReplicateResponse.class);
            if (current == null) {
                continue;
            }
            Long updatedTime = current.getProgress() == null ? null : current.getProgress().getUpdatedTime();
            long now = System.currentTimeMillis();
            if ((updatedTime != null && !updatedTime.equals(lastUpdatedTime)) || now - lastPublishedTime >= heartbeatIntervalMs) {
                publishProgress(current, progressConsumer);
                lastUpdatedTime = updatedTime;
                lastPublishedTime = now;
            }
            if (!"RUNNING".equalsIgnoreCase(current.getStatus())) {
                return current;
            }
        }
        throw new IllegalStateException("Remote self replicate progress polling timed out");
    }

    private void publishProgress(SelfReplicateResponse response, Consumer<SelfReplicateProgress> progressConsumer) {
        if (response != null && response.getProgress() != null && progressConsumer != null) {
            progressConsumer.accept(response.getProgress());
        }
    }

    private String resolveSelfReplicateEndpoint(String relayEndpoint) {
        try {
            URI uri = URI.create(relayEndpoint);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/internal/deploy/self-replicate", null, null)
                    .toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid source relay endpoint: " + relayEndpoint, e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
