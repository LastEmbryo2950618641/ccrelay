package com.webank.wedatasphere.wdsavs.aiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

@Data
@Entity
@Table(name = "wdsavs_ai_ssh_node_trust_edge",
        uniqueConstraints = @UniqueConstraint(name = "uk_ssh_identity_edge",
                columnNames = {"cluster_id", "source_node_key", "target_node_key"}))
public class SshNodeTrustEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_id", nullable = false, length = 128)
    private String clusterId;

    @Column(name = "source_node_key", nullable = false, length = 256)
    private String sourceNodeKey;

    @Column(name = "target_node_key", nullable = false, length = 256)
    private String targetNodeKey;

    @Column(name = "runtime_username", length = 128)
    private String runtimeUsername;

    @Column(name = "key_fingerprint", length = 256)
    private String keyFingerprint;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @Column(name = "last_error_summary", length = 1000)
    private String lastErrorSummary;

    @Column(name = "last_verified_time", length = 32)
    private String lastVerifiedTime;
}
