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
@Table(name = "wdsavs_ai_relay_grant")
public class AiRelayGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grant_id")
    private String grantId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "source_node_id")
    private String sourceNodeId;

    @Column(name = "target_node_id")
    private String targetNodeId;

    @Column(name = "allowed_capabilities_json", columnDefinition = "MEDIUMTEXT")
    private String allowedCapabilitiesJson;

    @Column(name = "signed_token", columnDefinition = "MEDIUMTEXT")
    private String signedToken;

    @Column(name = "status")
    private String status;

    @Column(name = "expire_at")
    private String expireAt;

    @Column(name = "revoked_at")
    private String revokedAt;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "create_time")
    private String createTime;

    @Column(name = "update_time")
    private String updateTime;
}
