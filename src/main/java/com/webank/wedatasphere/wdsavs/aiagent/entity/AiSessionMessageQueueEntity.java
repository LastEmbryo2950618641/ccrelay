package com.webank.wedatasphere.wdsavs.aiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

@Data
@Entity
@Table(name = "wdsavs_ai_session_message_queue",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_session_message_request",
                columnNames = {"session_id", "target_node_id", "request_id"}),
        indexes = {
                @Index(name = "idx_ai_session_message_queue_key", columnList = "session_id,target_node_id,status"),
                @Index(name = "idx_ai_session_message_due", columnList = "status,next_attempt_time"),
                @Index(name = "idx_ai_session_message_wake", columnList = "wake_required,wake_dispatched")
        })
public class AiSessionMessageQueueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "queue_id", nullable = false, unique = true)
    private String queueId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "source_node_id")
    private String sourceNodeId;

    @Column(name = "target_node_id", nullable = false)
    private String targetNodeId;

    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "request_payload_json", columnDefinition = "TEXT", nullable = false)
    private String requestPayloadJson;

    @Column(name = "response_payload_json", columnDefinition = "TEXT")
    private String responsePayloadJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "next_attempt_time")
    private Long nextAttemptTime;

    @Column(name = "lease_until")
    private Long leaseUntil;

    @Column(name = "deferred", nullable = false)
    private Boolean deferred;

    @Column(name = "wake_required", nullable = false)
    private Boolean wakeRequired;

    @Column(name = "wake_dispatched", nullable = false)
    private Boolean wakeDispatched;

    @Column(name = "create_time", nullable = false)
    private String createTime;

    @Column(name = "update_time", nullable = false)
    private String updateTime;
}
