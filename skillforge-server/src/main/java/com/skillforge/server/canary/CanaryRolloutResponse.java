package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryRolloutEntity;

import java.time.Instant;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.3: wire-format DTO for the
 * {@code /api/canary/rollouts/...} REST endpoints.
 *
 * <p>Mirrors {@link CanaryRolloutEntity} 1:1 — kept as a record (java.md
 * footgun #6: FE-BE contract) so future field changes are explicit and
 * grep-able. Field names match the entity getters exactly (no kebab/snake
 * remapping) so the FE TS interface can mirror Java field names directly.
 */
public record CanaryRolloutResponse(
        Long id,
        String surfaceType,
        Long agentId,
        String baselineSkillName,
        String candidateSkillName,
        String rolloutStage,
        Integer rolloutPercentage,
        Instant startedAt,
        Instant lastDecisionAt,
        String decision,
        Instant createdAt,
        Instant updatedAt) {

    public static CanaryRolloutResponse from(CanaryRolloutEntity e) {
        return new CanaryRolloutResponse(
                e.getId(),
                e.getSurfaceType(),
                e.getAgentId(),
                e.getBaselineSkillName(),
                e.getCandidateSkillName(),
                e.getRolloutStage(),
                e.getRolloutPercentage(),
                e.getStartedAt(),
                e.getLastDecisionAt(),
                e.getDecision(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
