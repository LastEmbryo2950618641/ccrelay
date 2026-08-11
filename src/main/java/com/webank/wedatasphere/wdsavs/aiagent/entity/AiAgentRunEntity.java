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
@Table(name = "wdsavs_ai_agent_run")
public class AiAgentRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_run_id")
    private String agentRunId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "node_id")
    private String nodeId;

    @Column(name = "agent_role")
    private String agentRole;

    @Column(name = "status")
    private String status;

    @Column(name = "input_summary", columnDefinition = "TEXT")
    private String inputSummary;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "start_time")
    private String startTime;

    @Column(name = "end_time")
    private String endTime;

    @Column(name = "create_time")
    private String createTime;

    @Column(name = "update_time")
    private String updateTime;
}
