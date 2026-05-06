package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalTaskEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * EVAL-V2 M3a (b2): renamed from {@code EvalRunRepository}; backs
 * {@link EvalTaskEntity} on table {@code t_eval_task} (V52).
 *
 * <p>Method names are preserved verbatim from the legacy
 * {@code EvalRunRepository} so existing callers
 * ({@code PromptImproverService} / {@code SkillAbEvalService} / {@code AbEvalPipeline} /
 * {@code EvalController} / {@code EvalOrchestrator}) only need an import-path
 * + entity-type swap, not a query-shape change.
 */
@Repository
public interface EvalTaskRepository extends JpaRepository<EvalTaskEntity, String> {

    List<EvalTaskEntity> findByAgentDefinitionIdOrderByStartedAtDesc(String agentDefinitionId);

    /**
     * Paged variant used by {@code EvalController#getScenarioRecentRuns} to bound
     * the {@code scenario_results_json} blobs we hydrate per request. Spring Data
     * derives the pagination/sort from the {@link Pageable}.
     */
    List<EvalTaskEntity> findByAgentDefinitionIdOrderByStartedAtDesc(String agentDefinitionId,
                                                                    Pageable pageable);

    Optional<EvalTaskEntity> findTopByAgentDefinitionIdAndStatusOrderByStartedAtDesc(
            String agentDefinitionId, String status);

    /** Rate limit check: only count active/successful tasks, not FAILED ghost rows. */
    List<EvalTaskEntity> findByAgentDefinitionIdAndStatusInAndStartedAtAfter(
            String agentDefinitionId, List<String> statuses, Instant since);

    /** EVAL-V2 M3a (b2): Tasks tab list filter. */
    List<EvalTaskEntity> findByAgentDefinitionIdAndStatusOrderByStartedAtDesc(
            String agentDefinitionId, String status);
}
