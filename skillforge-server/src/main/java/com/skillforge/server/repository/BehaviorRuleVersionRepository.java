package com.skillforge.server.repository;

import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — repository for the third surface's
 * version table (mirrors {@code PromptVersionRepository}).
 */
@Repository
public interface BehaviorRuleVersionRepository extends JpaRepository<BehaviorRuleVersionEntity, String> {

    /**
     * Look up the currently active version for an agent (≤1 row per agent via
     * V82 partial UNIQUE {@code uq_brv_one_active}). Returns empty when the
     * agent has no DB-backed active version — callers fall back to the startup
     * {@code BehaviorRuleRegistry} baseline.
     */
    Optional<BehaviorRuleVersionEntity> findByAgentIdAndStatus(String agentId, String status);

    List<BehaviorRuleVersionEntity> findByAgentIdOrderByVersionNumberDesc(String agentId);

    @Query("SELECT MAX(v.versionNumber) FROM BehaviorRuleVersionEntity v WHERE v.agentId = :agentId")
    Optional<Integer> findMaxVersionNumber(@Param("agentId") String agentId);
}
