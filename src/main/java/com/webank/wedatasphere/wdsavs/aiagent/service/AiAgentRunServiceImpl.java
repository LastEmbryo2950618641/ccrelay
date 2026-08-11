package com.webank.wedatasphere.wdsavs.aiagent.service;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiAgentRunEntity;
import com.webank.wedatasphere.wdsavs.aiagent.repository.AiAgentRunRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AiAgentRunServiceImpl implements AiAgentRunService {

    private final AiAgentRunRepository agentRunRepository;

    public AiAgentRunServiceImpl(AiAgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    @Override
    public String startRun(String taskId, String sessionId, String nodeId, String agentRole) {
        return startRun(UUID.randomUUID().toString(), taskId, sessionId, nodeId, agentRole);
    }

    @Override
    public String startRun(String agentRunId, String taskId, String sessionId, String nodeId, String agentRole) {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(taskId, "taskId");
        requireNonBlank(sessionId, "sessionId");
        String normalizedAgentRunId = agentRunId.trim();
        if (agentRunRepository.findByAgentRunId(normalizedAgentRunId).isPresent()) {
            return normalizedAgentRunId;
        }
        String now = String.valueOf(System.currentTimeMillis());
        AiAgentRunEntity entity = new AiAgentRunEntity();
        entity.setAgentRunId(normalizedAgentRunId);
        entity.setTaskId(taskId.trim());
        entity.setSessionId(sessionId.trim());
        entity.setNodeId(trimToNull(nodeId));
        entity.setAgentRole(defaultRole(agentRole));
        entity.setStatus("RUNNING");
        entity.setStartTime(now);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        agentRunRepository.save(entity);
        return entity.getAgentRunId();
    }

    @Override
    public void completeRun(String agentRunId, String outputSummary) {
        finishRun(agentRunId, "SUCCESS", outputSummary);
    }

    @Override
    public void failRun(String agentRunId, String outputSummary) {
        finishRun(agentRunId, "FAILED", outputSummary);
    }

    @Override
    public void cancelRun(String agentRunId, String outputSummary) {
        finishRun(agentRunId, "CANCELLED", outputSummary);
    }

    @Override
    public List<AiAgentRunEntity> listRuns(String taskId) {
        requireNonBlank(taskId, "taskId");
        List<AiAgentRunEntity> runs = agentRunRepository.findByTaskIdOrderByCreateTimeAsc(taskId.trim());
        return runs == null ? List.of() : runs;
    }

    private void finishRun(String agentRunId, String status, String outputSummary) {
        requireNonBlank(agentRunId, "agentRunId");
        AiAgentRunEntity entity = agentRunRepository.findByAgentRunId(agentRunId.trim())
                .orElseThrow(() -> new IllegalArgumentException("Agent run not found: " + agentRunId));
        if ("SUCCESS".equals(entity.getStatus()) || "FAILED".equals(entity.getStatus()) || "CANCELLED".equals(entity.getStatus())) {
            return;
        }
        String now = String.valueOf(System.currentTimeMillis());
        entity.setStatus(status);
        entity.setOutputSummary(outputSummary);
        entity.setEndTime(now);
        entity.setUpdateTime(now);
        agentRunRepository.save(entity);
    }

    private void requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private String defaultRole(String agentRole) {
        String value = trimToNull(agentRole);
        return value == null ? "AGENT" : value;
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
