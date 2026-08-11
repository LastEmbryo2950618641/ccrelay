package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiRelayGrantRepository extends JpaRepository<AiRelayGrantEntity, Long> {

    Optional<AiRelayGrantEntity> findByGrantId(String grantId);

    Optional<AiRelayGrantEntity> findByRequestId(String requestId);

    List<AiRelayGrantEntity> findBySessionIdOrderByCreateTimeDesc(String sessionId);
}
