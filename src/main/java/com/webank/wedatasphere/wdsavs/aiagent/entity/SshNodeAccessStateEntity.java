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
@Table(name = "wdsavs_ai_ssh_node_access_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_ssh_identity_node", columnNames = {"cluster_id", "node_key"}))
public class SshNodeAccessStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_id", nullable = false, length = 128)
    private String clusterId;

    @Column(name = "node_key", nullable = false, length = 256)
    private String nodeKey;

    @Column(name = "bootstrap_credential_scope", length = 16)
    private String bootstrapCredentialScope;

    @Column(name = "bootstrap_username", length = 128)
    private String bootstrapUsername;

    @Column(name = "runtime_username", length = 128)
    private String runtimeUsername;

    @Column(name = "os_type", length = 16)
    private String osType;

    @Column(name = "account_status", length = 32)
    private String accountStatus;

    @Column(name = "key_install_status", length = 32)
    private String keyInstallStatus;

    @Column(name = "center_access_status", length = 32)
    private String centerAccessStatus;

    @Column(name = "mutual_access_status", length = 32)
    private String mutualAccessStatus;

    @Column(name = "privilege_summary_json", columnDefinition = "MEDIUMTEXT")
    private String privilegeSummaryJson;

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @Column(name = "last_error_summary", length = 1000)
    private String lastErrorSummary;

    @Column(name = "last_verified_time", length = 32)
    private String lastVerifiedTime;

    @Column(name = "updated_time", length = 32)
    private String updatedTime;
}
