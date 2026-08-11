package com.webank.wedatasphere.wdsavs.aiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "wdsavs_ai_session_context_event")
public class AiSessionContextEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "sender_type")
    private String senderType;

    @Column(name = "sender_id")
    private String senderId;

    @Column(name = "target_node_id")
    private String targetNodeId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "content_ref")
    private String contentRef;

    @Column(name = "created_time", nullable = false)
    private String createdTime;

    @Column(name = "checksum", nullable = false)
    private String checksum;
}
