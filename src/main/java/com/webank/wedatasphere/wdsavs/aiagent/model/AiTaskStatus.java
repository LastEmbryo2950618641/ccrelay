package com.webank.wedatasphere.wdsavs.aiagent.model;

public enum AiTaskStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_DEPLOY,
    PARTIAL_SUCCESS,
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMEOUT
}
