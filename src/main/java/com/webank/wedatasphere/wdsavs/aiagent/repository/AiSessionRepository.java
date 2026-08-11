package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiSessionRepository extends JpaRepository<AiSessionEntity, Long> {

    Optional<AiSessionEntity> findBySessionId(String sessionId);

    List<AiSessionEntity> findByStatusOrderByUpdateTimeDesc(String status);
}
