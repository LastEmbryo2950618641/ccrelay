package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateProgress;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteSelfReplicateExecutorImplProgressTest {

    @Test
    void pollsAsyncOperationAndPublishesNodeProgress() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RemoteSelfReplicateExecutorImpl executor = new RemoteSelfReplicateExecutorImpl(restTemplate);
        SelfReplicateRequest request = new SelfReplicateRequest();
        request.setTimeoutMs(10_000L);
        request.setProgressPollIntervalMs(500L);
        SelfReplicateResponse accepted = response("RUNNING", null, progress("operation-1", 1024L, 2048L, 1000L));
        accepted.setOperationId("operation-1");
        SelfReplicateResponse completed = response("WAIT_REGISTER", true,
                progress("operation-1", 2048L, 2048L, 2000L));
        completed.setOperationId("operation-1");
        when(restTemplate.postForObject(anyString(), eq(request), eq(SelfReplicateResponse.class))).thenReturn(accepted);
        when(restTemplate.getForObject(anyString(), eq(SelfReplicateResponse.class))).thenReturn(completed);
        List<SelfReplicateProgress> observed = new ArrayList<>();

        SelfReplicateResponse result = executor.execute(
                "http://127.0.0.1:18192/api/ai/remote-cc/chat", request, observed::add);

        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        assertEquals("WAIT_REGISTER", result.getStatus());
        assertEquals(2, observed.size());
        assertEquals(Long.valueOf(2048L), observed.get(1).getBytesTransferred());
    }

    private SelfReplicateResponse response(String status, Boolean success, SelfReplicateProgress progress) {
        SelfReplicateResponse response = new SelfReplicateResponse();
        response.setAccepted(true);
        response.setSuccess(success);
        response.setStatus(status);
        response.setProgress(progress);
        return response;
    }

    private SelfReplicateProgress progress(String operationId, long bytes, long total, long updatedTime) {
        SelfReplicateProgress progress = new SelfReplicateProgress();
        progress.setOperationId(operationId);
        progress.setPhase("COPYING_ARTIFACT");
        progress.setProgressPercent((int) (bytes * 100L / total));
        progress.setBytesTransferred(bytes);
        progress.setTotalBytes(total);
        progress.setUpdatedTime(updatedTime);
        return progress;
    }
}
