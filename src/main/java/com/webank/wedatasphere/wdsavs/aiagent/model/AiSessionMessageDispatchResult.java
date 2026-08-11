package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class AiSessionMessageDispatchResult {
    private String queueId;
    private String status;
    private boolean queued;
    private AiChatResponse response;
}
