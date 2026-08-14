package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiPromptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiPromptRepository extends JpaRepository<AiPromptEntity, Long> {

    Optional<AiPromptEntity> findByPromptId(String promptId);
}
