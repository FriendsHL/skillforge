package com.skillforge.server.skill;

import java.util.List;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.1 B1-fix (2026-05-18): published by the
 * synchronous {@code SkillCreatorService.dispatchEvaluation} transaction
 * after it has finished writing the 2N child {@code SessionEntity} rows
 * (each with {@code skill_overrides_json} + {@code eval_context_json}
 * stamped). Consumed by the matching
 * {@code SkillCreatorEvalCoordinator.onSkillEvalDispatchReady} listener
 * <em>after the publishing transaction commits</em> — at which point the
 * async {@code ChatService.chatAsync} reads see the freshly-persisted child
 * rows including their override columns.
 *
 * <p><b>Why the event indirection</b>: java-reviewer Phase 2.0 r1 caught
 * the upstream-transaction race that the original {@code dispatchEvaluation}
 * had — calling {@code chatService.chatAsync} <em>inside</em> the @Transactional
 * sync path queues the async {@code runLoop} on a separate thread which then
 * starts a fresh transaction. That fresh transaction sees the world
 * <em>before</em> the outer dispatch transaction committed, so
 * {@code freshSession.getSkillOverridesJson()} reads NULL and the
 * skill-override branch in {@code ChatService.runLoop} silently falls back
 * to {@code agent.skillIds}. End result: both with_skill / without_skill
 * child sessions run the agent's actual skill list — eval metric is bogus.
 *
 * <p>The {@code @TransactionalEventListener(AFTER_COMMIT)} pattern (already
 * used by V6 {@code OptimizationEventAutoTriggerListener}) gives us the
 * exact-once-committed semantic we need: listener only fires when the
 * dispatching transaction successfully commits, and runs in its own
 * {@code REQUIRES_NEW} transaction so any chatAsync failure doesn't bubble
 * back into a not-yet-cleaned-up parent context.
 *
 * @param draftId         {@link com.skillforge.server.entity.SkillDraftEntity#getId()}
 * @param childSessionIds the 2N child {@code SessionEntity.id}s the listener
 *                        must {@code chatAsync(...)} in order
 * @param userId          owning user (passed through to chatAsync for the
 *                        new turn)
 * @param taskBySession   per-session task prompt (mapped from each child's
 *                        eval scenario at dispatch time)
 */
public record SkillEvalDispatchReadyEvent(
        String draftId,
        List<String> childSessionIds,
        Long userId,
        java.util.Map<String, String> taskBySession
) {
}
