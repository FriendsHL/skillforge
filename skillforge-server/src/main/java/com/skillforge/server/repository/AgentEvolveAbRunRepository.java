package com.skillforge.server.repository;

import com.skillforge.server.entity.AgentEvolveAbRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1: repository for the whole-agent A/B run
 * rows ({@code t_agent_evolve_ab_run}).
 */
@Repository
public interface AgentEvolveAbRunRepository extends JpaRepository<AgentEvolveAbRunEntity, String> {

    /**
     * §7 W1 — most-recent COMPLETED whole-agent A/B run for an agent. Used by
     * {@code AgentEvolveAbEvalService} to source the prior winner whose
     * {@code candidate_bundle_json} the incoming {@code skip_baseline} run's
     * baseline bundle must structurally match before the cached rate is trusted.
     */
    Optional<AgentEvolveAbRunEntity> findFirstByAgentIdAndStatusOrderByCompletedAtDesc(
            String agentId, String status);

    /**
     * §7 W5 — supersede-dedup skeleton (mirrors behavior_rule INV-6): the most
     * recent in-flight (PENDING/RUNNING) run for the same agent + identical
     * candidate bundle, so {@code startAgentAb} can mark it SUPERSEDED before
     * starting a fresh run for the same candidate.
     */
    Optional<AgentEvolveAbRunEntity> findFirstByAgentIdAndCandidateBundleJsonAndStatusInOrderByStartedAtDesc(
            String agentId, String candidateBundleJson, Collection<String> statuses);
}
