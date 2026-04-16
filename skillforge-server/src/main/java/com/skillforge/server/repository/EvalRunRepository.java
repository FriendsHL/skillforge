package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EvalRunRepository extends JpaRepository<EvalRunEntity, String> {

    List<EvalRunEntity> findByAgentDefinitionIdOrderByStartedAtDesc(String agentDefinitionId);

    Optional<EvalRunEntity> findTopByAgentDefinitionIdAndStatusOrderByStartedAtDesc(
            String agentDefinitionId, String status);

    // Rate limit check: only count active/successful runs, not FAILED/error ghost runs
    List<EvalRunEntity> findByAgentDefinitionIdAndStatusInAndStartedAtAfter(
            String agentDefinitionId, List<String> statuses, Instant since);
}
