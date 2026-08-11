package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.model.AiTaskCreateRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployReportRequest;
import com.webank.wedatasphere.wdsavs.aiagent.model.DeployReportResponse;

public interface AiRelayDeployService {

    void triggerDeployAsync(String taskId, AiTaskCreateRequest request);

    DeployReportResponse report(DeployReportRequest request);

    boolean resumeCenterDeploy(String taskId);

    boolean autoCompleteReadyDeployments(String targetNodeId);
}
