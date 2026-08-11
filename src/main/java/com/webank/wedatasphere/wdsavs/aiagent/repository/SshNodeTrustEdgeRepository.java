package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.SshNodeTrustEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SshNodeTrustEdgeRepository extends JpaRepository<SshNodeTrustEdgeEntity, Long> {
    List<SshNodeTrustEdgeEntity> findByClusterIdOrderBySourceNodeKeyAscTargetNodeKeyAsc(String clusterId);

    Optional<SshNodeTrustEdgeEntity> findByClusterIdAndSourceNodeKeyAndTargetNodeKey(
            String clusterId, String sourceNodeKey, String targetNodeKey);

    void deleteByClusterId(String clusterId);
}
