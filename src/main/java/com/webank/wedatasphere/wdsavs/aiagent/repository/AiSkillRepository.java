package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiSkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiSkillRepository extends JpaRepository<AiSkillEntity, Long> {

    Optional<AiSkillEntity> findBySkillId(String skillId);

    List<AiSkillEntity> findAllByOrderBySkillIdAsc();
}
