package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateProgress;
import com.webank.wedatasphere.wdsavs.aiagent.model.SelfReplicateResponse;

import java.util.function.Consumer;

public interface RemoteSelfReplicateExecutor {

    SelfReplicateResponse execute(String relayEndpoint, SelfReplicateRequest request);

    default SelfReplicateResponse execute(String relayEndpoint, SelfReplicateRequest request,
                                          Consumer<SelfReplicateProgress> progressConsumer) {
        SelfReplicateResponse response = execute(relayEndpoint, request);
        if (response != null && response.getProgress() != null && progressConsumer != null) {
            progressConsumer.accept(response.getProgress());
        }
        return response;
    }
}
