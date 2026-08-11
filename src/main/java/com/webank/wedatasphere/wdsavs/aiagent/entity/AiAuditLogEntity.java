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
@Table(name = "wdsavs_ai_audit_log")
public class AiAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "audit_id")
    private String auditId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "source_node_id")
    private String sourceNodeId;

    @Column(name = "target_node_id")
    private String targetNodeId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "decision")
    private String decision;

    @Column(name = "detail_json", columnDefinition = "MEDIUMTEXT")
    private String detailJson;

    @Column(name = "operator_type")
    private String operatorType;

    @Column(name = "operator_id")
    private String operatorId;

    @Column(name = "created_time")
    private String createdTime;
}
