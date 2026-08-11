package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiAuditLogRepository extends JpaRepository<AiAuditLogEntity, Long> {

    Optional<AiAuditLogEntity> findByAuditId(String auditId);

    List<AiAuditLogEntity> findBySessionIdOrderByCreatedTimeDesc(String sessionId);

    List<AiAuditLogEntity> findByTaskIdOrderByCreatedTimeDesc(String taskId);

    List<AiAuditLogEntity> findByEventTypeOrderByCreatedTimeDesc(String eventType);
}
