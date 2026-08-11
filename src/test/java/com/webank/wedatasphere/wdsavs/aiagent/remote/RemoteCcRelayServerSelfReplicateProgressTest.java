package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateProgress;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteCcRelayServerSelfReplicateProgressTest {

    @Test
    void acceptsAsyncSelfReplicateAndExposesProgressSnapshot() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        CountDownLatch release = new CountDownLatch(1);
        RemoteSelfReplicateService selfReplicateService = mock(RemoteSelfReplicateService.class);
        when(selfReplicateService.execute(any(SelfReplicateRequest.class), any())).thenAnswer(invocation -> {
            SelfReplicateRequest request = invocation.getArgument(0);
            Consumer<SelfReplicateProgress> consumer = invocation.getArgument(1);
            SelfReplicateProgress preparing = new SelfReplicateProgress();
            preparing.setTaskId(request.getTaskId());
            preparing.setTargetNodeId(request.getTargetNodeId());
            preparing.setPhase("PREPARING_DIRECTORY");
            preparing.setProgressPercent(20);
            preparing.setBytesTransferred(0L);
            preparing.setUpdatedTime(System.currentTimeMillis());
            consumer.accept(preparing);
            SelfReplicateProgress progress = new SelfReplicateProgress();
            progress.setTaskId(request.getTaskId());
            progress.setTargetNodeId(request.getTargetNodeId());
            progress.setPhase("COPYING_ARTIFACT");
            progress.setProgressPercent(50);
            progress.setBytesTransferred(1024L);
            progress.setTotalBytes(2048L);
            progress.setUpdatedTime(System.currentTimeMillis());
            consumer.accept(progress);
            assertTrue(release.await(5, TimeUnit.SECONDS));
            SelfReplicateResponse response = new SelfReplicateResponse();
            response.setAccepted(true);
            response.setSuccess(true);
            response.setStatus("WAIT_REGISTER");
            return response;
        });
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(port);
        RemoteCcRelayServer server = new RemoteCcRelayServer(
                properties,
                mock(RemoteCcRelayService.class),
                selfReplicateService);
        server.start();
        try {
            RestTemplate restTemplate = new RestTemplate();
            SelfReplicateRequest request = new SelfReplicateRequest();
            request.setTaskId("deploy-progress-task");
            request.setTargetNodeId("target:18192");
            SelfReplicateResponse accepted = restTemplate.postForObject(
                    "http://127.0.0.1:" + port + "/internal/deploy/self-replicate",
                    request,
                    SelfReplicateResponse.class);

            assertNotNull(accepted);
            assertEquals("RUNNING", accepted.getStatus());
            assertEquals("deploy-progress-task", accepted.getOperationId());
            SelfReplicateResponse running = waitForPhase(restTemplate, port, "COPYING_ARTIFACT");
            assertNotNull(running);
            assertEquals("COPYING_ARTIFACT", running.getProgress().getPhase());
            assertEquals(Long.valueOf(1024L), running.getProgress().getBytesTransferred());
            release.countDown();
            SelfReplicateResponse completed = waitForCompletion(restTemplate, port);
            assertTrue(Boolean.TRUE.equals(completed.getSuccess()));
            assertEquals("WAIT_REGISTER", completed.getStatus());
        } finally {
            release.countDown();
            server.stop();
        }
    }

    private SelfReplicateResponse waitForCompletion(RestTemplate restTemplate, int port) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            SelfReplicateResponse response = restTemplate.getForObject(
                    "http://127.0.0.1:" + port + "/internal/deploy/self-replicate/deploy-progress-task",
                    SelfReplicateResponse.class);
            if (response != null && !"RUNNING".equals(response.getStatus())) {
                return response;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Self replicate operation did not complete");
    }

    private SelfReplicateResponse waitForPhase(RestTemplate restTemplate, int port, String phase) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            SelfReplicateResponse response = restTemplate.getForObject(
                    "http://127.0.0.1:" + port + "/internal/deploy/self-replicate/deploy-progress-task",
                    SelfReplicateResponse.class);
            if (response != null && response.getProgress() != null && phase.equals(response.getProgress().getPhase())) {
                return response;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Self replicate progress phase did not arrive: " + phase);
    }
}
