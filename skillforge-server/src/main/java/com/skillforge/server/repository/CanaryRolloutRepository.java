package com.skillforge.server.repository;

import com.skillforge.server.entity.CanaryRolloutEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.1: JPA access for {@link CanaryRolloutEntity}.
 *
 * <p>Phase 1.1 ships only the two narrow queries Phase 1.2 / 1.4 need.
 * Generic search / pagination is intentionally deferred until a dashboard
 * use case actually requires it.
 */
public interface CanaryRolloutRepository extends JpaRepository<CanaryRolloutEntity, Long> {

    /**
     * Phase 1.2: look up the active canary (if any) for a given agent + baseline
     * skill name. Called by {@code CanaryAllocator.allocate} on every session
     * skill resolution — must stay fast (covered by
     * {@code idx_canary_rollout_agent_surface} + JPA cache).
     *
     * <p>"Active" = {@code rollout_stage = 'canary'}; production / rolled_back /
     * disabled rows must NOT match (the partial UNIQUE index already enforces
     * at most one such row per agent+surface, but we still filter explicitly
     * for safety on H2 unit tests that lack the partial index).
     *
     * @return the single active canary, or empty if none.
     */
    @Query("""
            SELECT c FROM CanaryRolloutEntity c
            WHERE c.agentId = :agentId
              AND c.surfaceType = 'skill'
              AND c.baselineSkillName = :baselineSkillName
              AND c.rolloutStage = 'canary'
            """)
    Optional<CanaryRolloutEntity> findActiveCanaryForSkill(
            @Param("agentId") Long agentId,
            @Param("baselineSkillName") String baselineSkillName);

    /**
     * Phase 1.4: list all rollouts in a given stage. Used by metrics-collector
     * to iterate {@code rollout_stage='canary'} rows during hourly aggregation.
     * Ordered by id so iteration is deterministic across ticks.
     */
    @Query("""
            SELECT c FROM CanaryRolloutEntity c
            WHERE c.rolloutStage = :stage
            ORDER BY c.id ASC
            """)
    List<CanaryRolloutEntity> findByRolloutStage(@Param("stage") String stage);
}
