package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.3 (2026-05-16) — auto-triggers the surface-
 * specific A/B run when an attribution event transitions to
 * {@code candidate_ready}. Closes缺口 B+C from the tech-design root-cause
 * analysis: V3 has the {@code candidate_ready → ab_running} edge in
 * {@link AttributionApprovalService#ALLOWED_TRANSITIONS} but nobody actually
 * fires the transition until an operator hand-clicks "Run A/B" in the
 * dashboard. This listener bridges the gap so dogfood approve → A/B → promote
 * runs unattended.
 *
 * <p><b>Triple-annotation pattern (Spring 6.1+ P11 lesson)</b>:
 * <ul>
 *   <li>{@code @Async("abEvalLoopExecutor")} — the listener does network
 *       (LLM) + DB work that can take 10s+; running synchronously would
 *       block the publishing thread (which is the HTTP/cron caller, not a
 *       pool thread).</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — fire only after
 *       the publishing tx commits. Otherwise a publisher rollback would
 *       still trigger a "phantom" A/B run that the dashboard never sees the
 *       attribution event for.</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — the publishing tx is already
 *       committed and unavailable for joining; any DB writes here (currently
 *       none; Phase 1.4 will add /run-ab persistence) need a fresh tx.</li>
 * </ul>
 *
 * <p><b>Surface routing</b>:
 * <ul>
 *   <li>{@code prompt} → call {@code PromptImproverService.runAbTestAgainst}
 *       (Phase 1.4 wires the actual call; Phase 1.3 logs intent with the
 *       resolved UUID so dogfood verification can confirm wiring even
 *       before /run-ab lands).</li>
 *   <li>{@code skill} → call {@code SkillDraftService.triggerAbTestFromDraft}
 *       (same Phase 1.4 placeholder).</li>
 *   <li>{@code behavior_rule} → explicit skip + log. V4 BehaviorRule lacks
 *       a dynamic-candidate A/B runner (it promotes deterministically on
 *       creation); V5.1 backlog covers re-introducing per-rule A/B.</li>
 * </ul>
 *
 * <p>On any dispatch exception, log + fire
 * {@link AttributionEventBroadcaster#broadcastStageTransition} with stage
 * {@code ab_failed} so the dashboard reflects the failure (operator can
 * manually retry via the existing Phase 1.4 retry endpoint).
 *
 * <p>Killed by config {@code skillforge.flywheel.auto-trigger-ab-on-candidate-ready=false}
 * (default {@code true}) — gives operators a quick lever if the listener
 * misbehaves in production without redeploying.
 */
@Component
@ConditionalOnProperty(
        name = "skillforge.flywheel.auto-trigger-ab-on-candidate-ready",
        havingValue = "true",
        matchIfMissing = true)
public class OptimizationEventAutoTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(OptimizationEventAutoTriggerListener.class);

    private final PromptImproverService promptImproverService;
    private final SkillDraftService skillDraftService;
    private final AttributionEventBroadcaster broadcaster;

    public OptimizationEventAutoTriggerListener(PromptImproverService promptImproverService,
                                                SkillDraftService skillDraftService,
                                                AttributionEventBroadcaster broadcaster) {
        this.promptImproverService = promptImproverService;
        this.skillDraftService = skillDraftService;
        this.broadcaster = broadcaster;
    }

    /**
     * Phase 1.3 entry point — see class javadoc for the triple-annotation
     * rationale. Synchronously returns {@code void}; failures are caught,
     * logged, and mirrored as {@code ab_failed} for FE visibility.
     */
    @Async("abEvalLoopExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStageCandidateReady(OptimizationEventStageChangeEvent event) {
        if (event == null || event.eventId() == null) {
            return;
        }
        // Kill-switch is now realised via @ConditionalOnProperty above: when the
        // flag is set to false, Spring skips creating this bean entirely (so no
        // listener registration → no event delivery). The bean's presence here
        // is itself proof of enabled state — no per-call gate needed.
        // Filter on the only stage we react to. Other transitions (proposal_*,
        // candidate_generating, candidate_failed, ab_*) are no-ops; logging at
        // TRACE keeps INFO clean while still observable in debug builds.
        if (!OptimizationEventEntity.STAGE_CANDIDATE_READY.equals(event.toStage())) {
            log.trace("[FlywheelAutoTrigger] non-candidate-ready transition, skip "
                    + "eventId={} toStage={}", event.eventId(), event.toStage());
            return;
        }

        try {
            switch (safeSurface(event.surfaceType())) {
                case OptimizationEventEntity.SURFACE_PROMPT ->
                        dispatchPromptAutoAb(event);
                case OptimizationEventEntity.SURFACE_SKILL ->
                        dispatchSkillAutoAb(event);
                case OptimizationEventEntity.SURFACE_BEHAVIOR_RULE ->
                        dispatchBehaviorRuleAutoAb(event);
                default -> log.warn("[FlywheelAutoTrigger] unknown surface={} "
                                + "eventId={} — skip auto A/B",
                        event.surfaceType(), event.eventId());
            }
        } catch (RuntimeException ex) {
            // Mirror failure as ab_failed so the dashboard shows the auto-trigger
            // attempt + its outcome; operator can manually retry via Phase 1.4
            // retry endpoint without needing a server-side log dive.
            log.error("[FlywheelAutoTrigger] auto A/B trigger FAILED eventId={} "
                            + "surface={} toStage={}: {}",
                    event.eventId(), event.surfaceType(), event.toStage(),
                    ex.getMessage(), ex);
            broadcastAbFailed(event);
        }
    }

    /**
     * Phase 1.4 — real call into {@link PromptImproverService#runAbTestAgainst}.
     * Baseline {@code null} = agent's current active prompt; scenarios {@code null}
     * = trigger ratify #7-E ephemeral fallback (attribution path always passes
     * null because the curator doesn't carry scenarios). Package-private for
     * unit-test spy/verify.
     */
    void dispatchPromptAutoAb(OptimizationEventStageChangeEvent event) {
        if (event.candidatePromptVersionUuid() == null) {
            log.warn("[FlywheelAutoTrigger] candidate_ready eventId={} has null "
                            + "candidatePromptVersionUuid; skip auto A/B (Phase 1.2 V88 "
                            + "sidecar should populate this on every prompt dispatch)",
                    event.eventId());
            return;
        }
        String abRunId = promptImproverService.runAbTestAgainst(
                event.agentId() == null ? null : String.valueOf(event.agentId()),
                /*baselineVersionId*/ null,
                event.candidatePromptVersionUuid(),
                /*evalScenarioIds*/ null);
        log.info("[FlywheelAutoTrigger] auto-triggered prompt A/B: eventId={} "
                        + "agentId={} candidatePromptVersionUuid={} abRunId={}",
                event.eventId(), event.agentId(), event.candidatePromptVersionUuid(), abRunId);
    }

    /**
     * Phase 1.4 — real call into {@link SkillDraftService#startAbTestFromDraft}.
     * Scenarios {@code null} → ratify #7-E ephemeral fallback (V88 sidecar
     * draft UUID → originating event.patternId → pattern.members → extract).
     */
    void dispatchSkillAutoAb(OptimizationEventStageChangeEvent event) {
        if (event.candidateSkillDraftUuid() == null) {
            log.warn("[FlywheelAutoTrigger] candidate_ready eventId={} has null "
                            + "candidateSkillDraftUuid; skip auto A/B (Phase 1.2 V88 "
                            + "sidecar should populate this on every skill dispatch)",
                    event.eventId());
            return;
        }
        String abRunId = skillDraftService.startAbTestFromDraft(
                event.candidateSkillDraftUuid(),
                /*evalScenarioIds*/ null);
        log.info("[FlywheelAutoTrigger] auto-triggered skill A/B: eventId={} "
                        + "agentId={} candidateSkillDraftUuid={} abRunId={}",
                event.eventId(), event.agentId(), event.candidateSkillDraftUuid(), abRunId);
    }

    /**
     * V4 BehaviorRule promotes deterministically on creation — there is no
     * runner to fire per-rule A/B against today. V5.1 backlog covers
     * re-introducing the dynamic-candidate flow. Log at INFO so dogfood
     * traces show the explicit skip rather than silent black-hole.
     */
    void dispatchBehaviorRuleAutoAb(OptimizationEventStageChangeEvent event) {
        log.info("[FlywheelAutoTrigger] behavior_rule auto-AB not supported "
                        + "(V5.1 backlog — V4 BehaviorRule lacks dynamic candidate "
                        + "flow); skip eventId={} candidateBehaviorRuleVersionId={}",
                event.eventId(), event.candidateBehaviorRuleVersionId());
    }

    /**
     * Fire the {@code ab_failed} stage WS toast so the dashboard reflects the
     * auto-trigger outcome. We synthesise the broadcast payload directly
     * (rather than loading the entity + flipping stage in DB) because:
     * <ol>
     *   <li>Persisting stage=ab_failed would require validating the
     *       transition (candidate_ready → ab_failed isn't in
     *       ALLOWED_TRANSITIONS — we don't want to widen the state machine
     *       just for a non-persistent UI signal).</li>
     *   <li>The actual stage stays at candidate_ready; operator can retry
     *       via Phase 1.4 retry endpoint without manual stage-undo.</li>
     * </ol>
     */
    private void broadcastAbFailed(OptimizationEventStageChangeEvent event) {
        try {
            broadcaster.broadcastStageTransition(
                    event.eventId(),
                    event.patternId(),
                    OptimizationEventEntity.STAGE_AB_FAILED,
                    event.toStage(),
                    Instant.now());
        } catch (Exception broadcastEx) {
            // Don't let a broadcast failure mask the original error in logs.
            log.warn("[FlywheelAutoTrigger] failed to broadcast ab_failed for "
                            + "eventId={}: {}", event.eventId(), broadcastEx.getMessage());
        }
    }

    /** {@code null}-safe surface accessor so the switch on it doesn't NPE. */
    private static String safeSurface(String surfaceType) {
        return surfaceType == null ? "" : surfaceType;
    }
}
