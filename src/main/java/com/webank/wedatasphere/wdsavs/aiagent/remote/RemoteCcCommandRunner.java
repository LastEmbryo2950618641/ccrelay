package com.webank.wedatasphere.wdsavs.aiagent.remote;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiChatResponse;

public interface RemoteCcCommandRunner {

    AiChatResponse execute(RemoteCcExecutionRequest request);
}
