package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiAgentRunEntity;

import java.util.List;

public interface AiAgentRunService {

    String startRun(String taskId, String sessionId, String nodeId, String agentRole);

    String startRun(String agentRunId, String taskId, String sessionId, String nodeId, String agentRole);

    void completeRun(String agentRunId, String outputSummary);

    void failRun(String agentRunId, String outputSummary);

    void cancelRun(String agentRunId, String outputSummary);

    List<AiAgentRunEntity> listRuns(String taskId);
}
