package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiRelayNodeRepository extends JpaRepository<AiRelayNodeEntity, Long> {

    Optional<AiRelayNodeEntity> findByNodeId(String nodeId);

    List<AiRelayNodeEntity> findByStatusOrderByUpdateTimeDesc(String status);
}
