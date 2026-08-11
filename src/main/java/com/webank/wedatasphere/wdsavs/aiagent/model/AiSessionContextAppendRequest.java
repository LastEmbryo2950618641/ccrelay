package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class AiSessionContextAppendRequest {

    private String eventId;
    private String taskId;
    private String senderType;
    private String senderId;
    private String targetNodeId;
    private String role;
    private String content;
    private String contentType;
    private String contentRef;
}
