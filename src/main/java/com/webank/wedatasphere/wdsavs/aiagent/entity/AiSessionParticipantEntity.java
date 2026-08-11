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
@Table(name = "wdsavs_ai_session_participant", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ai_session_participant", columnNames = {"session_id", "node_id"})
})
public class AiSessionParticipantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "join_time", nullable = false)
    private String joinTime;

    @Column(name = "update_time", nullable = false)
    private String updateTime;
}
