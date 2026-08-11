package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiModelConfigRepository extends JpaRepository<AiModelConfigEntity, Long> {

    Optional<AiModelConfigEntity> findByProviderCode(String providerCode);

    List<AiModelConfigEntity> findAllByOrderByDefaultConfigDescDisplayNameAsc();

    List<AiModelConfigEntity> findByEnabledTrueOrderByDefaultConfigDescDisplayNameAsc();

    Optional<AiModelConfigEntity> findFirstByDefaultConfigTrueAndEnabledTrue();

    List<AiModelConfigEntity> findByDefaultConfigTrueAndIdNot(Long id);
}
