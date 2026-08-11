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
@Table(name = "wdsavs_ai_session")
public class AiSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "session_type")
    private String sessionType;

    @Column(name = "initiator_type")
    private String initiatorType;

    @Column(name = "initiator_id")
    private String initiatorId;

    @Column(name = "source_node_id")
    private String sourceNodeId;

    @Column(name = "status")
    private String status;

    @Column(name = "context_summary", columnDefinition = "TEXT")
    private String contextSummary;

    @Column(name = "collaboration_mode")
    private String collaborationMode;

    @Column(name = "coordinator_node_id")
    private String coordinatorNodeId;

    @Column(name = "coordinator_epoch")
    private Long coordinatorEpoch;

    @Column(name = "collaboration_policy_json", columnDefinition = "TEXT")
    private String collaborationPolicyJson;

    @Column(name = "expire_at")
    private String expireAt;

    @Column(name = "create_time")
    private String createTime;

    @Column(name = "update_time")
    private String updateTime;
}
