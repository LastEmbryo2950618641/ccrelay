package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRelayHeartbeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiRelayHeartbeatRepository extends JpaRepository<AiRelayHeartbeatEntity, Long> {

    List<AiRelayHeartbeatEntity> findTop20ByNodeIdOrderByHeartbeatTimeDesc(String nodeId);

    List<AiRelayHeartbeatEntity> findTop1ByNodeIdOrderByHeartbeatTimeDesc(String nodeId);
}
