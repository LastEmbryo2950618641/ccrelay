package com.webank.wedatasphere.wdsavs.aiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Entity
@Table(name = "wdsavs_ai_relay_heartbeat")
public class AiRelayHeartbeatEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id")
    private String nodeId;

    @Column(name = "status")
    private String status;

    @Column(name = "active_sessions")
    private Integer activeSessions;

    @Column(name = "cpu_load")
    private BigDecimal cpuLoad;

    @Column(name = "memory_usage")
    private Long memoryUsage;

    @Column(name = "last_task_time")
    private String lastTaskTime;

    @Column(name = "detail_json", columnDefinition = "MEDIUMTEXT")
    private String detailJson;

    @Column(name = "heartbeat_time")
    private String heartbeatTime;
}
