package com.webank.wedatasphere.wdsavs.aiagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SshDeployProgress {
    private String phase;
    private Integer progressPercent;
    private Long bytesTransferred;
    private Long totalBytes;
    private Long updatedTime;
    private String message;
}
