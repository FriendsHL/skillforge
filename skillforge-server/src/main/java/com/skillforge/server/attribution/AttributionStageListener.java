package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.event.SkillAbCompletedEvent;
import com.skillforge.server.improve.event.BehaviorRulePromotedEvent;
import com.skillforge.server.improve.event.PromptPromotedEvent;
import com.skillforge.server.repository.OptimizationEventRepository;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * V3.2 stage-mirror listener — closes the V3 attribution → A/B → promote loop
 * by watching existing subsystem ApplicationEvents and writing back
 * {@link OptimizationEventEntity#getStage()} accordingly.
 *
 * <p>Decoupled by design: this listener does NOT trigger A/B runs (operators
 * still kick those off via the V1 dashboard SkillAbPanel / PromptImprover
 * flow). It only mirrors completion + promotion outcomes into the V3 timeline,
 * so {@code /insights/optimization-events} reflects the full causal chain
 * without operator-manual stage-marking.
 *
 * <p>Listener semantics:
 * <ul>
 *   <li>{@link SkillAbCompletedEvent} → find optimization event by ab_run_id;
 *       set stage to {@code ab_passed} (if {@code promoted=true}) or
 *       {@code ab_failed} otherwise.</li>
 *   <li>{@link PromptPromotedEvent} → find optimization event by
 *       candidate_prompt_version_id; set stage to {@code promoted}.</li>
 *   <li>{@link BehaviorRulePromotedEvent} → find optimization event by
 *       candidate_behavior_rule_version_id; set stage to {@code promoted}
 *       (V4 MULTI-SURFACE-FLYWHEEL Phase 1.3). Unlike the prompt variant
 *       this lookup is direct (no Long.parseLong skip) because the V83 column
 *       is VARCHAR(36) matching {@link com.skillforge.server.entity.BehaviorRuleVersionEntity#getId()}.</li>
 * </ul>
 *
 * <p>{@code @Transactional(REQUIRES_NEW)} so the listener's write is
 * independent of the firing service's tx (Spring 6.1 default + V2 W2 lesson:
 * REQUIRED on a listener fired after-commit silently no-ops because the
 * outer tx is already gone).
 *
 * <p>The listener is **silent** when no matching event row exists — that's the
 * common case for non-attribution-originated A/B and promote flows (manual or
 * cron-driven self-improve). Only V3-attributed candidates land in
 * t_optimization_event with their A/B run id stamped, so the lookup returns
 * empty for the rest.
 */
@Component
public class AttributionStageListener {

    private static final Logger log = LoggerFactory.getLogger(AttributionStageListener.class);

    private final OptimizationEventRepository eventRepository;
    private final AttributionEventBroadcaster broadcaster;

    public AttributionStageListener(OptimizationEventRepository eventRepository,
                                    AttributionEventBroadcaster broadcaster) {
        this.eventRepository = eventRepository;
        this.broadcaster = broadcaster;
    }

    /**
     * SkillAb completion → mirror into optimization event. The event's
     * {@code abRunId} is a {@code Long} in t_optimization_event but the A/B
     * event ships a {@code String} (UUID-typed) id — we attempt {@link
     * Long#parseLong} and skip if not numeric (legacy String-id A/B runs from
     * earlier eras).
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSkillAbCompleted(SkillAbCompletedEvent event) {
        Long abRunId = parseLongOrNull(event.getEvolutionAbRunId());
        if (abRunId == null) {
            log.debug("[AttributionStageListener] skill-ab event has non-numeric runId={} — skip mirror",
                    event.getEvolutionAbRunId());
            return;
        }
        List<OptimizationEventEntity> matches = eventRepository.findByAbRunId(abRunId);
        if (matches.isEmpty()) {
            // Not an attribution-originated A/B — normal for manual / cron paths.
            log.debug("[AttributionStageListener] no optimization event linked to abRunId={} — skip",
                    abRunId);
            return;
        }
        String newStage = event.isPromoted()
                ? OptimizationEventEntity.STAGE_AB_PASSED
                : OptimizationEventEntity.STAGE_AB_FAILED;
        for (OptimizationEventEntity oe : matches) {
            String previousStage = oe.getStage();
            // Only advance if currently in ab_running (defensive: ignore
            // duplicates / stale events that arrive after a manual edit).
            if (!OptimizationEventEntity.STAGE_AB_RUNNING.equals(previousStage)) {
                log.debug("[AttributionStageListener] event {} not in ab_running (was={}) — skip mirror",
                        oe.getId(), previousStage);
                continue;
            }
            oe.setStage(newStage);
            oe.setUpdatedAt(Instant.now());
            // candidateSkillId may have been written at A/B-trigger time; if
            // not, stamp it here from the event so timeline drills correctly.
            if (oe.getCandidateSkillId() == null && event.getSkillId() != null) {
                oe.setCandidateSkillId(event.getSkillId());
            }
            eventRepository.save(oe);
            log.info("[AttributionStageListener] skill-ab completion mirrored: eventId={} "
                    + "{} → {} (promoted={} abRunId={})",
                    oe.getId(), previousStage, newStage, event.isPromoted(), abRunId);
            broadcaster.broadcastStageTransition(oe, previousStage);
        }
    }

    /**
     * Prompt promotion → mirror to {@code promoted} stage. Fired by
     * {@code PromptPromotionService.evaluateAndPromote} after writing
     * {@code t_prompt_version.status=active} and bumping
     * {@code t_agent.prompt_version_id}.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPromptPromoted(PromptPromotedEvent event) {
        // PromptVersion ids are UUIDs (varchar). Optimization event stores
        // candidatePromptVersionId as a Long (BIGINT). The schema mismatch is
        // pre-existing — V3 attribution writes a hash or skip — for V3.2 we
        // try numeric parse and skip on UUID. (Backlog: align ID types.)
        Long versionId = parseLongOrNull(event.versionId());
        if (versionId == null) {
            log.debug("[AttributionStageListener] prompt-promoted event versionId={} non-numeric — "
                    + "schema mismatch (BIGINT vs UUID), skip mirror", event.versionId());
            return;
        }
        List<OptimizationEventEntity> matches = eventRepository.findByCandidatePromptVersionId(versionId);
        if (matches.isEmpty()) {
            log.debug("[AttributionStageListener] no optimization event linked to "
                    + "candidatePromptVersionId={} — skip", versionId);
            return;
        }
        for (OptimizationEventEntity oe : matches) {
            String previousStage = oe.getStage();
            // Accept advancing from either ab_passed (typical path) or
            // candidate_ready (operator skipped A/B and promoted directly).
            if (!OptimizationEventEntity.STAGE_AB_PASSED.equals(previousStage)
                    && !OptimizationEventEntity.STAGE_CANDIDATE_READY.equals(previousStage)) {
                log.debug("[AttributionStageListener] event {} not in ab_passed / candidate_ready "
                        + "(was={}) — skip prompt-promote mirror", oe.getId(), previousStage);
                continue;
            }
            oe.setStage(OptimizationEventEntity.STAGE_PROMOTED);
            oe.setUpdatedAt(Instant.now());
            eventRepository.save(oe);
            log.info("[AttributionStageListener] prompt-promoted mirrored: eventId={} {} → promoted "
                    + "(versionId={})", oe.getId(), previousStage, versionId);
            broadcaster.broadcastStageTransition(oe, previousStage);
        }
    }

    /**
     * V4 MULTI-SURFACE-FLYWHEEL Phase 1.3: behavior_rule promotion → mirror
     * to {@code promoted} stage. Fired by
     * {@code BehaviorRulePromotionService.promote} after the candidate row's
     * {@code status='active' + promotedAt=now} flip + retire of the prior
     * active row (V82 partial UNIQUE invariant preserved).
     *
     * <p>Like {@link #onPromptPromoted} but without the {@code Long.parseLong}
     * skip path — {@code candidate_behavior_rule_version_id} is VARCHAR(36)
     * (matches {@code BehaviorRuleVersionEntity.id}), so the lookup is direct.
     *
     * <p>{@code REQUIRES_NEW} same rationale as the sister listeners: the
     * promote service's tx has already committed when this listener fires
     * (Spring 6.1 default ApplicationEvent ordering), so we need our own tx
     * to issue the stage-mirror write.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBehaviorRulePromoted(BehaviorRulePromotedEvent event) {
        String versionId = event.versionId();
        if (versionId == null || versionId.isBlank()) {
            log.debug("[AttributionStageListener] behavior-rule-promoted event has blank versionId — skip");
            return;
        }
        List<OptimizationEventEntity> matches = eventRepository
                .findByCandidateBehaviorRuleVersionId(versionId);
        if (matches.isEmpty()) {
            // Not an attribution-originated behavior_rule promote — normal
            // for manual / dashboard-direct paths.
            log.debug("[AttributionStageListener] no optimization event linked to "
                    + "candidateBehaviorRuleVersionId={} — skip", versionId);
            return;
        }
        for (OptimizationEventEntity oe : matches) {
            String previousStage = oe.getStage();
            // Accept advancing from either ab_passed (typical path once
            // behavior_rule A/B wired in Phase 1.4) or candidate_ready
            // (operator skipped A/B and promoted directly via dashboard).
            // Same predicate as PromptPromoted listener.
            if (!OptimizationEventEntity.STAGE_AB_PASSED.equals(previousStage)
                    && !OptimizationEventEntity.STAGE_CANDIDATE_READY.equals(previousStage)) {
                log.debug("[AttributionStageListener] event {} not in ab_passed / candidate_ready "
                        + "(was={}) — skip behavior-rule-promote mirror", oe.getId(), previousStage);
                continue;
            }
            oe.setStage(OptimizationEventEntity.STAGE_PROMOTED);
            oe.setUpdatedAt(Instant.now());
            eventRepository.save(oe);
            log.info("[AttributionStageListener] behavior-rule-promoted mirrored: eventId={} {} → promoted "
                    + "(versionId={})", oe.getId(), previousStage, versionId);
            broadcaster.broadcastStageTransition(oe, previousStage);
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
