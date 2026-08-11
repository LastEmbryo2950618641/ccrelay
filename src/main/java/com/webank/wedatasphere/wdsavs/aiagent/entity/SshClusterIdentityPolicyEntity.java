package com.webank.wedatasphere.wdsavs.aiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "wdsavs_ai_ssh_identity_policy")
public class SshClusterIdentityPolicyEntity {

    @Id
    @Column(name = "cluster_id", length = 128)
    private String clusterId;

    @Column(name = "account_mode", nullable = false, length = 32)
    private String accountMode;

    @Column(name = "dedicated_creation_allowed", nullable = false)
    private Boolean dedicatedCreationAllowed;

    @Column(name = "dedicated_username", length = 128)
    private String dedicatedUsername;

    @Column(name = "dedicated_account_status", length = 32)
    private String dedicatedAccountStatus;

    @Column(name = "cluster_key_mode", length = 32)
    private String clusterKeyMode;

    @Column(name = "cluster_key_fingerprint", length = 256)
    private String clusterKeyFingerprint;

    @Column(name = "secret_config_ref", length = 512)
    private String secretConfigRef;

    @Column(name = "center_node_id", length = 256)
    private String centerNodeId;

    @Column(name = "center_to_node_status", length = 32)
    private String centerToNodeStatus;

    @Column(name = "node_to_node_status", length = 32)
    private String nodeToNodeStatus;

    @Column(name = "effective_capability", length = 32)
    private String effectiveCapability;

    @Column(name = "revision", nullable = false)
    private Long revision;

    @Column(name = "created_time", length = 32)
    private String createdTime;

    @Column(name = "updated_time", length = 32)
    private String updatedTime;

    @Column(name = "last_verified_time", length = 32)
    private String lastVerifiedTime;
}
