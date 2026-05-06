package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalTaskItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * EVAL-V2 M3a (b2): per-case row backing for the new Items tab; FK on
 * {@code task_id} → {@code t_eval_task.id}.
 */
@Repository
public interface EvalTaskItemRepository extends JpaRepository<EvalTaskItemEntity, Long> {

    /** Items tab list query — preserve insertion order (≈ scenario execution order). */
    List<EvalTaskItemEntity> findByTaskIdOrderByCreatedAtAsc(String taskId);

    /** Recent-runs lookup for a single scenario across tasks. */
    List<EvalTaskItemEntity> findByScenarioIdOrderByCreatedAtDesc(String scenarioId);

    /** OBS trace drill-down: look up the eval item for a given session id. */
    List<EvalTaskItemEntity> findBySessionId(String sessionId);

    /** Cascade delete already done at DB level (FK ON DELETE CASCADE), but
     *  expose for explicit cleanup tests / tear-down paths. */
    long deleteByTaskId(String taskId);

    long countByTaskId(String taskId);
}
