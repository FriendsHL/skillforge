package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalScenarioDraftRepository extends JpaRepository<EvalScenarioEntity, String> {

    List<EvalScenarioEntity> findByAgentIdOrderByCreatedAtDesc(String agentId);

    List<EvalScenarioEntity> findByAgentId(String agentId);

    List<EvalScenarioEntity> findByStatus(String status);

    List<EvalScenarioEntity> findByAgentIdAndStatus(String agentId, String status);

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4 (2026-05-16): used by
     * {@code PromptImproverService.runAbTestAgainst} to resolve the agent's
     * canonical scenario split (default {@code "held_out"}) when the /run-ab
     * caller doesn't supply explicit {@code evalScenarioIds}. Returns empty
     * when the agent has no scenarios in the requested split — caller falls
     * back to the ephemeral path (ratify #4).
     */
    List<EvalScenarioEntity> findByAgentIdAndSplit(String agentId, String split);
}
