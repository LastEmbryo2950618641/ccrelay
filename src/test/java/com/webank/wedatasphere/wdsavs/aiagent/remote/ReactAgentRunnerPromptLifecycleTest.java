package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatMessage;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactAgentRunnerPromptLifecycleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void postPromptUsesPrivateCandidateThenPublishesSecondAnswer() throws Exception {
        RemoteCcRelayProperties properties = propertiesWithPost();
        List<RemoteCcExecutionRequest> calls = new ArrayList<>();
        RemoteCcRelayService service = new RemoteCcRelayService(properties, request -> {
            calls.add(request);
            if (calls.size() == 1) {
                AiChatResponse candidate = new AiChatResponse("候选答案", "SUCCESS", "candidate-trace");
                candidate.setMetadata(new LinkedHashMap<>(Map.of(
                        RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA, true)));
                return candidate;
            }
            return new AiChatResponse("最终答案", "SUCCESS", "final-trace");
        });

        AiChatResponse response = service.relay(request());

        assertEquals("最终答案", response.getAnswer());
        assertEquals(2, calls.size());
        RemoteCcExecutionRequest candidate = calls.get(0);
        RemoteCcExecutionRequest finalization = calls.get(1);
        assertTrue(candidate.isPrivateDraft());
        assertEquals("REACT_CANDIDATE", candidate.getExecutionPhase());
        assertTrue(candidate.getPrompt().contains("CC_PRE idempotency key: PRE:session-post"));
        assertTrue(candidate.getPrompt().contains("本轮先确认输入完整"));
        assertFalse(candidate.getRetryPrompt().contains("本轮先确认输入完整"));
        assertFalse(finalization.isPrivateDraft());
        assertEquals("POST_FINALIZATION", finalization.getExecutionPhase());
        assertEquals(candidate.getModelSessionId(), finalization.getModelSessionId());
        assertTrue(finalization.isResumeModelSession());
        assertTrue(finalization.getPrompt().contains("CC_POST"));
        assertTrue(finalization.getPrompt().contains("最终回复必须简洁并复核事实"));
        assertTrue(finalization.getPrompt().contains("候选答案"));
        int tools = finalization.getArguments().indexOf("--tools");
        assertTrue(tools >= 0);
        assertEquals("", finalization.getArguments().get(tools + 1));
        assertFalse(finalization.getEnvironment().containsKey("CCRELAY_CENTER_URL"));
        assertEquals(true, response.getMetadata().get(RemoteSessionContextSynchronizer.MODEL_SESSION_STARTED_METADATA));
    }

    @Test
    void postFailureDoesNotPublishCandidateAnswer() throws Exception {
        RemoteCcRelayProperties properties = propertiesWithPost();
        List<RemoteCcExecutionRequest> calls = new ArrayList<>();
        RemoteCcRelayService service = new RemoteCcRelayService(properties, request -> {
            calls.add(request);
            return calls.size() == 1
                    ? new AiChatResponse("不得发布的候选", "SUCCESS", "candidate-trace")
                    : new AiChatResponse("finalization unavailable", "FAILED", "final-trace");
        });

        AiChatResponse response = service.relay(request());

        assertEquals("POST_FINALIZATION_FAILED", response.getStatus());
        assertFalse(response.getAnswer().contains("不得发布的候选"));
        assertEquals(2, calls.size());
    }

    @Test
    void noPostPromptUsesSingleVisibleModelCall() {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setWorkingDirectory(temporaryDirectory.toString());
        properties.setPromptMetadataPath(temporaryDirectory.resolve("empty-prompts.json").toString());
        List<RemoteCcExecutionRequest> calls = new ArrayList<>();
        RemoteCcRelayService service = new RemoteCcRelayService(properties, request -> {
            calls.add(request);
            return new AiChatResponse("直接答案", "SUCCESS", "trace");
        });

        AiChatResponse response = service.relay(request());

        assertEquals("直接答案", response.getAnswer());
        assertEquals(1, calls.size());
        assertFalse(calls.get(0).isPrivateDraft());
    }

    private RemoteCcRelayProperties propertiesWithPost() throws Exception {
        RemoteCcRelayProperties properties = new RemoteCcRelayProperties();
        properties.setWorkingDirectory(temporaryDirectory.toString());
        properties.setCenterRegisterEndpoint("http://center.example:18191/api/relay/register");
        properties.setPromptMetadataPath(temporaryDirectory.resolve("post-prompts.json").toString());
        Path postPath = temporaryDirectory.resolve("post.md");
        Path prePath = temporaryDirectory.resolve("pre.md");
        Files.writeString(postPath, "最终回复必须简洁并复核事实", StandardCharsets.UTF_8);
        Files.writeString(prePath, "本轮先确认输入完整", StandardCharsets.UTF_8);
        RelayPromptMetadata metadata = promptMetadata("final-check", "POST", postPath);
        RelayPromptMetadata preMetadata = promptMetadata("input-check", "PRE", prePath);
        RelayPromptCatalogState state = new RelayPromptCatalogState();
        state.setCatalogSha256("post-revision");
        state.setPrompts(List.of(preMetadata, metadata));
        new RelayPromptMetadataStore(properties).save(state);
        return properties;
    }

    private RelayPromptMetadata promptMetadata(String promptId, String type, Path path) throws Exception {
        RelayPromptMetadata metadata = new RelayPromptMetadata();
        metadata.setPromptId(promptId);
        metadata.setType(type);
        metadata.setOrder(1);
        String postSha256 = fileSha256(path);
        metadata.setCenterSha256(postSha256);
        metadata.setInstalledSha256(postSha256);
        metadata.setStatus("INSTALLED");
        metadata.setContentPath(path.toString());
        return metadata;
    }

    private String fileSha256(Path path) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setMessages(List.of(new AiChatMessage("user", "请检查服务")));
        request.setMetadata(new LinkedHashMap<>(Map.of(
                "sessionId", "session-post",
                "modelSessionId", "11111111-1111-1111-1111-111111111111",
                "resumeModelSession", false)));
        return request;
    }
}
