package com.skillforge.server.attribution;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.3 (2026-05-16) — domain event fired by
 * {@link AttributionApprovalService} after every stage transition write on
 * {@code t_optimization_event}. Listened by
 * {@link OptimizationEventAutoTriggerListener} via
 * {@code @TransactionalEventListener(AFTER_COMMIT)} to auto-trigger the
 * surface-specific A/B run when {@link #toStage()} is
 * {@code candidate_ready} (Phase 1.4 wires the actual {@code /run-ab} call).
 *
 * <p>The record carries the V88 (Phase 1.2) UUID sidecar columns
 * {@link #candidatePromptVersionUuid()} / {@link #candidateSkillDraftUuid()}
 * — not the legacy BIGINT {@code candidate_*_id} columns, which can't hold
 * the String UUIDs that {@code PromptVersionEntity.id} / {@code SkillDraftEntity.id}
 * use. The {@link #candidateBehaviorRuleVersionId()} field is already
 * VARCHAR(36) on the legacy column (V83), so no sidecar split was needed.
 *
 * <p>All fields are nullable except {@link #eventId()} / {@link #toStage()};
 * pre-{@code candidate_ready} transitions naturally have null candidate IDs.
 * The listener filters on {@code toStage} and only inspects the matching
 * surface's candidate UUID, so the cross-product of nullability is safe.
 */
public record OptimizationEventStageChangeEvent(
        Long eventId,
        String fromStage,
        String toStage,
        String surfaceType,
        Long agentId,
        Long patternId,
        String candidatePromptVersionUuid,
        String candidateSkillDraftUuid,
        String candidateBehaviorRuleVersionId) {
}
