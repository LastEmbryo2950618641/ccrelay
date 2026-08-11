package com.webank.wedatasphere.wdsavs.aiagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelfReplicateProgress {
    private String operationId;
    private String taskId;
    private String sourceNodeId;
    private String targetNodeId;
    private String phase;
    private Integer progressPercent;
    private Long bytesTransferred;
    private Long totalBytes;
    private Long bytesPerSecond;
    private Long estimatedRemainingMs;
    private Long startedTime;
    private Long updatedTime;
    private Long elapsedMs;
    private String message;
}
