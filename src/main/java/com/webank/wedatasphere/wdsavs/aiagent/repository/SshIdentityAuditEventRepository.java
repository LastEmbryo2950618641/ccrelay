package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.SshIdentityAuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SshIdentityAuditEventRepository extends JpaRepository<SshIdentityAuditEventEntity, Long> {
    List<SshIdentityAuditEventEntity> findByClusterIdOrderByCreatedTimeDesc(String clusterId);
}
