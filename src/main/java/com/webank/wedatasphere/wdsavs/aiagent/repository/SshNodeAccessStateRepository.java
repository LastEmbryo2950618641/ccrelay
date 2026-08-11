package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.SshNodeAccessStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SshNodeAccessStateRepository extends JpaRepository<SshNodeAccessStateEntity, Long> {
    List<SshNodeAccessStateEntity> findByClusterIdOrderByNodeKeyAsc(String clusterId);

    Optional<SshNodeAccessStateEntity> findByClusterIdAndNodeKey(String clusterId, String nodeKey);

    void deleteByClusterId(String clusterId);
}
