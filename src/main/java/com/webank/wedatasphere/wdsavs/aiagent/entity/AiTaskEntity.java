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
@Table(name = "wdsavs_ai_task")
public class AiTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "parent_task_id")
    private String parentTaskId;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "task_type")
    private String taskType;

    @Column(name = "status")
    private String status;

    @Column(name = "current_stage")
    private String currentStage;

    @Column(name = "source_node_id")
    private String sourceNodeId;

    @Column(name = "target_node_id")
    private String targetNodeId;

    @Column(name = "request_payload_json", columnDefinition = "MEDIUMTEXT")
    private String requestPayloadJson;

    @Column(name = "result_json", columnDefinition = "MEDIUMTEXT")
    private String resultJson;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "timeout_ms")
    private Long timeoutMs;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "max_retries")
    private Integer maxRetries;

    @Column(name = "start_time")
    private String startTime;

    @Column(name = "end_time")
    private String endTime;

    @Column(name = "create_time")
    private String createTime;

    @Column(name = "update_time")
    private String updateTime;
}
