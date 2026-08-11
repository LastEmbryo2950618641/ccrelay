package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiTaskRepository extends JpaRepository<AiTaskEntity, Long> {

    Optional<AiTaskEntity> findByTaskId(String taskId);

    Optional<AiTaskEntity> findByRequestId(String requestId);

    List<AiTaskEntity> findBySessionIdOrderByCreateTimeDesc(String sessionId);

    List<AiTaskEntity> findByParentTaskId(String parentTaskId);

    List<AiTaskEntity> findByParentTaskIdOrderByCreateTimeAsc(String parentTaskId);

    List<AiTaskEntity> findByStatusOrderByCreateTimeAsc(String status);
}
