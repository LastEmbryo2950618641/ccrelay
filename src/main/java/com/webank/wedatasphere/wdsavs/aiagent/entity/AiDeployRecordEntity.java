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
@Table(name = "wdsavs_ai_deploy_record")
public class AiDeployRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deploy_id")
    private String deployId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "target_node_id")
    private String targetNodeId;

    @Column(name = "deploy_mode")
    private String deployMode;

    @Column(name = "artifact_version")
    private String artifactVersion;

    @Column(name = "script_path")
    private String scriptPath;

    @Column(name = "artifact_path")
    private String artifactPath;

    @Column(name = "status")
    private String status;

    @Column(name = "stderr_summary", columnDefinition = "TEXT")
    private String stderrSummary;

    @Column(name = "stdout_summary", columnDefinition = "TEXT")
    private String stdoutSummary;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "retryable")
    private Boolean retryable;

    @Column(name = "start_time")
    private String startTime;

    @Column(name = "end_time")
    private String endTime;

    @Column(name = "create_time")
    private String createTime;

    @Column(name = "update_time")
    private String updateTime;
}
