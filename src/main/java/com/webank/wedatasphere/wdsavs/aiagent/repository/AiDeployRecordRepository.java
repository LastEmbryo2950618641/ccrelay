package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiDeployRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiDeployRecordRepository extends JpaRepository<AiDeployRecordEntity, Long> {

    Optional<AiDeployRecordEntity> findByDeployId(String deployId);

    List<AiDeployRecordEntity> findByTaskIdOrderByCreateTimeAsc(String taskId);
}
