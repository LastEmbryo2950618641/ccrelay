package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSessionContextEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiSessionContextEventRepository extends JpaRepository<AiSessionContextEventEntity, Long> {

    Optional<AiSessionContextEventEntity> findByEventId(String eventId);

    List<AiSessionContextEventEntity> findBySessionIdAndIdGreaterThanOrderByIdAsc(
            String sessionId, Long cursor, Pageable pageable);

    Optional<AiSessionContextEventEntity> findTopBySessionIdOrderByIdDesc(String sessionId);
}
