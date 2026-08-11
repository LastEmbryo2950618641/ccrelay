package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiRuntimeConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiRuntimeConfigRepository extends JpaRepository<AiRuntimeConfigEntity, Long> {

    Optional<AiRuntimeConfigEntity> findByConfigKey(String configKey);

    List<AiRuntimeConfigEntity> findByConfigKeyStartingWithOrderByConfigKeyAsc(String configKeyPrefix);

    List<AiRuntimeConfigEntity> findByOrderByConfigKeyAsc();
}
