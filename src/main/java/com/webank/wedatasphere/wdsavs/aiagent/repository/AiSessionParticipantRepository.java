package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiSessionParticipantRepository extends JpaRepository<AiSessionParticipantEntity, Long> {

    Optional<AiSessionParticipantEntity> findBySessionIdAndNodeId(String sessionId, String nodeId);

    List<AiSessionParticipantEntity> findBySessionIdOrderByJoinTimeAsc(String sessionId);
}
