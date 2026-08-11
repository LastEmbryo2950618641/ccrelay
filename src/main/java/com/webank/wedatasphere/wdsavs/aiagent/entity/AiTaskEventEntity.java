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
@Table(name = "wdsavs_ai_task_event")
public class AiTaskEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "payload_json", columnDefinition = "MEDIUMTEXT")
    private String payloadJson;

    @Column(name = "sequence_no")
    private Long sequenceNo;

    @Column(name = "created_time")
    private String createdTime;

    @Column(name = "created_time_ms")
    private Long createdTimeMs;
}
