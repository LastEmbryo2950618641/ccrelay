package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionMessageQueueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiSessionMessageQueueRepository extends JpaRepository<AiSessionMessageQueueEntity, Long> {

    Optional<AiSessionMessageQueueEntity> findBySessionIdAndTargetNodeIdAndRequestId(
            String sessionId, String targetNodeId, String requestId);

    List<AiSessionMessageQueueEntity> findByStatusInOrderByCreateTimeAscIdAsc(Collection<String> statuses);

    List<AiSessionMessageQueueEntity> findBySessionIdAndTargetNodeIdAndStatusInOrderByCreateTimeAscIdAsc(
            String sessionId, String targetNodeId, Collection<String> statuses);

    List<AiSessionMessageQueueEntity> findBySessionIdAndStatusIn(
            String sessionId, Collection<String> statuses);

    List<AiSessionMessageQueueEntity> findByStatusAndWakeRequiredTrueAndWakeDispatchedFalseOrderByUpdateTimeAsc(
            String status);
}
