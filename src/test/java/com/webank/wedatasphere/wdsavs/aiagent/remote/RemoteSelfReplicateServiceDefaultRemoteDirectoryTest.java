package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateProgress;
import com.webank.wedatasphere.wdsavs.aiagent.service.SshDeployExecutor;
import com.webank.wedatasphere.wdsavs.aiagent.service.SshDeployProgress;
import com.webank.wedatasphere.wdsavs.aiagent.service.SshDeployRequest;
import com.webank.wedatasphere.wdsavs.aiagent.service.SshDeployResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RemoteSelfReplicateServiceDefaultRemoteDirectoryTest {

    @Test
    void shouldReturnResolvedLayoutFromExecutor() {
        CapturingExecutor executor = new CapturingExecutor();
        RemoteSelfReplicateService service = new RemoteSelfReplicateService(executor);

        SelfReplicateRequest request = new SelfReplicateRequest();
        request.setHost("127.0.0.1");
        request.setUsername("tester");
        request.setRelayPort(18092);
        request.setScriptPath("/tmp/install-relay.sh");

        var response = service.execute(request);

        assertNotNull(executor.capturedRequest);
        assertNull(executor.capturedRequest.getRemoteDirectory());
        assertEquals(Integer.valueOf(18092), executor.capturedRequest.getRelayPort());
        assertEquals("/home/tester/ccrelay/127.0.0.1-18092", response.getResolvedRemoteDirectory());
        assertEquals(Integer.valueOf(18092), response.getResolvedRelayPort());
    }

    @Test
    void shouldForwardExecutorProgressWithTaskAndNodeContext() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.emitProgress = true;
        RemoteSelfReplicateService service = new RemoteSelfReplicateService(executor);
        SelfReplicateRequest request = new SelfReplicateRequest();
        request.setTaskId("task-1");
        request.setSourceNodeId("source:18192");
        request.setTargetNodeId("target:18192");
        request.setHost("127.0.0.1");
        request.setUsername("tester");
        request.setScriptPath("/tmp/install-relay.sh");
        List<SelfReplicateProgress> progress = new ArrayList<>();

        service.execute(request, progress::add);

        assertEquals(1, progress.size());
        assertEquals("task-1", progress.get(0).getTaskId());
        assertEquals("source:18192", progress.get(0).getSourceNodeId());
        assertEquals("target:18192", progress.get(0).getTargetNodeId());
        assertEquals("COPYING_ARTIFACT", progress.get(0).getPhase());
        assertEquals(Long.valueOf(1024L), progress.get(0).getBytesTransferred());
    }

    private static final class CapturingExecutor implements SshDeployExecutor {
        private SshDeployRequest capturedRequest;
        private boolean emitProgress;

        @Override
        public SshDeployResult deploy(SshDeployRequest request) {
            this.capturedRequest = request;
            if (emitProgress && request.getProgressListener() != null) {
                request.getProgressListener().onProgress(new SshDeployProgress(
                        "COPYING_ARTIFACT", 50, 1024L, 2048L, System.currentTimeMillis(), "copying"));
            }
            SshDeployResult result = new SshDeployResult(true, 0, "ok", "");
            result.setResolvedRelayPort(18092);
            result.setResolvedRemoteDirectory("/home/tester/ccrelay/127.0.0.1-18092");
            return result;
        }
    }
}
