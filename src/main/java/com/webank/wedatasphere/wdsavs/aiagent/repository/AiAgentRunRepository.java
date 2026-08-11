package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiAgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiAgentRunRepository extends JpaRepository<AiAgentRunEntity, Long> {

    Optional<AiAgentRunEntity> findByAgentRunId(String agentRunId);

    List<AiAgentRunEntity> findByTaskIdOrderByCreateTimeAsc(String taskId);
}
