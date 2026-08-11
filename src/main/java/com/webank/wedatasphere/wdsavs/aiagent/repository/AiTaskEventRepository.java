package com.webank.wedatasphere.wdsavs.aiagent.repository;

import com.webank.wedatasphere.wdsavs.aiagent.entity.AiTaskEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiTaskEventRepository extends JpaRepository<AiTaskEventEntity, Long> {

    Optional<AiTaskEventEntity> findByEventId(String eventId);

    List<AiTaskEventEntity> findByTaskIdOrderBySequenceNoAsc(String taskId);

    List<AiTaskEventEntity> findByTaskIdOrderBySequenceNoAsc(String taskId, Pageable pageable);

    List<AiTaskEventEntity> findByTaskIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(String taskId, Long sequenceNo);

    List<AiTaskEventEntity> findByTaskIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(String taskId, Long sequenceNo, Pageable pageable);

    List<AiTaskEventEntity> findByTaskIdAndCreatedTimeMsGreaterThanEqualOrderBySequenceNoAsc(String taskId, Long createdTimeMs);

    List<AiTaskEventEntity> findByTaskIdAndCreatedTimeMsGreaterThanEqualOrderBySequenceNoAsc(String taskId, Long createdTimeMs, Pageable pageable);

    List<AiTaskEventEntity> findByTaskIdAndSequenceNoGreaterThanEqualAndCreatedTimeMsGreaterThanEqualOrderBySequenceNoAsc(
            String taskId,
            Long sequenceNo,
            Long createdTimeMs);

    List<AiTaskEventEntity> findByTaskIdAndSequenceNoGreaterThanEqualAndCreatedTimeMsGreaterThanEqualOrderBySequenceNoAsc(
            String taskId,
            Long sequenceNo,
            Long createdTimeMs,
            Pageable pageable);
}
