package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.Data;

@Data
public class AiSessionContextEventView {

    private Long cursor;
    private String eventId;
    private String sessionId;
    private String taskId;
    private String senderType;
    private String senderId;
    private String targetNodeId;
    private String role;
    private String content;
    private String contentType;
    private String contentRef;
    private String createdTime;
    private String checksum;
}
