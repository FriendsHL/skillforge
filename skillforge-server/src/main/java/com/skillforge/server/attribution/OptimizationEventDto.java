package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.4 — wire shape for
 * {@link com.skillforge.server.attribution.AttributionEventController}
 * responses. Mirrors {@link OptimizationEventEntity} 1:1 so the FE has all the
 * timeline-relevant fields without a second round-trip.
 *
 * <p>Records' canonical Jackson serialisation handles all the column types we
 * need:
 * <ul>
 *   <li>{@code Instant} → ISO-8601 string (JavaTimeModule pre-registered in
 *       SkillForgeConfig per V1 W4 footgun)</li>
 *   <li>{@code BigDecimal confidence} → JSON number</li>
 *   <li>nullable Long fk fields → JSON null</li>
 * </ul>
 */
public record OptimizationEventDto(
        Long id,
        Long patternId,
        Long agentId,
        String surfaceType,
        String changeType,
        String description,
        String expectedImpact,
        BigDecimal confidence,
        String risk,
        String stage,
        Long candidateSkillId,
        Long candidatePromptVersionId,
        Long abRunId,
        Long canaryId,
        String attributionSessionId,
        Instant cooldownExpiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static OptimizationEventDto from(OptimizationEventEntity e) {
        if (e == null) return null;
        return new OptimizationEventDto(
                e.getId(),
                e.getPatternId(),
                e.getAgentId(),
                e.getSurfaceType(),
                e.getChangeType(),
                e.getDescription(),
                e.getExpectedImpact(),
                e.getConfidence(),
                e.getRisk(),
                e.getStage(),
                e.getCandidateSkillId(),
                e.getCandidatePromptVersionId(),
                e.getAbRunId(),
                e.getCanaryId(),
                e.getAttributionSessionId(),
                e.getCooldownExpiresAt(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
