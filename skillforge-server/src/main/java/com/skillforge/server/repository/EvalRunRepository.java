package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EvalRunRepository extends JpaRepository<EvalRunEntity, String> {

    List<EvalRunEntity> findByAgentDefinitionIdOrderByStartedAtDesc(String agentDefinitionId);

    /**
     * EVAL-V2 M0 (B1 fix): paged variant used by
     * {@code EvalController#getScenarioRecentRuns} to bound the
     * scenario_results_json blobs we hydrate per request. Spring Data
     * derives the pagination/sort from the {@link Pageable}.
     */
    List<EvalRunEntity> findByAgentDefinitionIdOrderByStartedAtDesc(String agentDefinitionId,
                                                                   Pageable pageable);

    Optional<EvalRunEntity> findTopByAgentDefinitionIdAndStatusOrderByStartedAtDesc(
            String agentDefinitionId, String status);

    // Rate limit check: only count active/successful runs, not FAILED/error ghost runs
    List<EvalRunEntity> findByAgentDefinitionIdAndStatusInAndStartedAtAfter(
            String agentDefinitionId, List<String> statuses, Instant since);
}
