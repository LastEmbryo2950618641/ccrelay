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
@Table(name = "wdsavs_ai_ssh_identity_audit_event")
public class SshIdentityAuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "cluster_id", nullable = false, length = 128)
    private String clusterId;

    @Column(name = "operator_id", length = 256)
    private String operatorId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "result", nullable = false, length = 32)
    private String result;

    @Column(name = "account_mode", length = 32)
    private String accountMode;

    @Column(name = "effective_capability", length = 32)
    private String effectiveCapability;

    @Column(name = "key_fingerprint", length = 256)
    private String keyFingerprint;

    @Column(name = "target_summary_json", length = 1024)
    private String targetSummaryJson;

    @Column(name = "error_summary", length = 1024)
    private String errorSummary;

    @Column(name = "revision")
    private Long revision;

    @Column(name = "created_time", nullable = false, length = 32)
    private String createdTime;
}
