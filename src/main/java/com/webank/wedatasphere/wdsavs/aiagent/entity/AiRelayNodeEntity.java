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
@Table(name = "wdsavs_ai_relay_node")
public class AiRelayNodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id")
    private String nodeId;

    @Column(name = "host")
    private String host;

    @Column(name = "port")
    private Integer port;

    @Column(name = "relay_endpoint")
    private String relayEndpoint;

    @Column(name = "version")
    private String version;

    @Column(name = "protocol_version")
    private String protocolVersion;

    @Column(name = "status")
    private String status;

    @Column(name = "capabilities_json", columnDefinition = "MEDIUMTEXT")
    private String capabilitiesJson;

    @Column(name = "workspace_root")
    private String workspaceRoot;

    @Column(name = "environment_summary_json", columnDefinition = "MEDIUMTEXT")
    private String environmentSummaryJson;

    @Column(name = "last_heartbeat_time")
    private String lastHeartbeatTime;

    @Column(name = "register_time")
    private String registerTime;

    @Column(name = "create_time")
    private String createTime;

    @Column(name = "update_time")
    private String updateTime;
}
