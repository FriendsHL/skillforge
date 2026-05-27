package com.skillforge.server.attribution;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.BehaviorRuleImproverService;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.3 — handles operator approve/reject decisions on
 * attribution-curator proposals and triggers the appropriate downstream
 * candidate-generation pipeline (skill draft creation OR prompt version
 * creation, per the proposal's surface).
 *
 * <p><b>WS broadcast trade-off (Phase 1.4 reviewer-noted)</b>: stage transitions
 * are broadcast via {@link AttributionEventBroadcaster} synchronously inside
 * the {@code @Transactional(REQUIRED)} boundary, BEFORE the outer commit. This
 * means if the outer tx subsequently rolls back, FE clients receive a phantom
 * stage notification. For V3 dogfood this is accepted: (1) all sub-service
 * failures are absorbed in {@link #runCandidateGeneration}'s catch-block;
 * (2) child services use {@code REQUIRES_NEW} so their rollback doesn't
 * propagate; (3) remaining rollback paths (DB connection loss after first save)
 * are rare. Production deployment SHOULD wrap broadcasts in
 * {@code TransactionSynchronizationManager.registerSynchronization(afterCommit)}
 * matching the {@link com.skillforge.server.improve.SkillDraftService}
 * approveDraft pattern.
 *
 * <p>Stage state machine (enforced via {@link #ALLOWED_TRANSITIONS}):
 * <pre>
 *   dispatch_initiated  → proposal_pending          (ProposeOptimizationTool, NOT this service)
 *   dispatch_initiated  → proposal_rejected         (curator agent reject path, NOT this service)
 *   proposal_pending    → proposal_approved         (this.approve)
 *   proposal_pending    → proposal_rejected         (this.reject)
 *   proposal_approved   → candidate_generating      (this.approve, post-skill/prompt dispatch)
 *   candidate_generating→ candidate_ready           (Phase 1.4 + downstream)
 *   candidate_generating→ candidate_failed          (this.approve catch-block)
 *   candidate_ready     → ab_running                (Phase 1.4 wiring)
 *   ab_running          → ab_passed | ab_failed     (Phase 1.4 wiring)
 *   ab_passed           → canary_started            (Phase 1.5 wiring)
 *   canary_started      → promoted | rolled_back    (V2 CanaryRolloutService)
 *   promoted            → verified                  (Phase 2)
 *   proposal_rejected   → (terminal)
 *   candidate_failed    → (terminal)
 * </pre>
 *
 * <p>{@code @Transactional(REQUIRED)}: the approve path needs the stage
 * transitions and the candidate creation to land atomically — if SkillDraft /
 * PromptVersion creation throws, we want stage=candidate_failed persisted in
 * the same transaction so the operator sees the failure on next dashboard
 * refresh. The catch-block deliberately does NOT rethrow so the tx commits.
 *
 * <p>Per ratify (2026-05-15): {@link PromptImproverService#startImprovementFromAttribution}
 * BYPASSES the agent-level 24h cooldown — V3 enforces a single cooldown via
 * {@code t_optimization_event.cooldown_expires_at}.
 */
@Service
@Transactional(propagation = Propagation.REQUIRED)
public class AttributionApprovalService {

    private static final Logger log = LoggerFactory.getLogger(AttributionApprovalService.class);

    /**
     * Surfaces this service knows how to dispatch. Defensive duplicate of
     * {@code ProposeOptimizationTool.ALLOWED_SURFACES} — we re-check at the
     * approval layer because the operator could in theory approve a row whose
     * surface column was hand-edited via SQL.
     *
     * <p>V4 MULTI-SURFACE-FLYWHEEL Phase 1.3: {@code behavior_rule} added.
     * {@link ProposeOptimizationTool} curator emission still rejects
     * {@code behavior_rule} (it's not yet a curator-output surface; events with
     * this surface arrive via direct admin insertion / Phase 1.4 dashboard
     * creation), but operator-driven {@code approve()} on such an event flows
     * through {@link #dispatchBehaviorRuleSurface}.
     */
    static final Set<String> APPROVABLE_SURFACES = Set.of(
            OptimizationEventEntity.SURFACE_SKILL,
            OptimizationEventEntity.SURFACE_PROMPT,
            OptimizationEventEntity.SURFACE_BEHAVIOR_RULE);

    /**
     * Stage flow whitelist. Read as: from-stage → set of legal next stages.
     * Stages not present as a key are terminal (no outbound transitions);
     * a transition target absent from the corresponding value is rejected
     * with {@link IllegalStateException}.
     *
     * <p>Includes the full V3 lifecycle even though Phase 1.3 only exercises
     * proposal_* and candidate_* — Phase 1.4/1.5 wiring will reuse this map
     * via {@link #validateTransition} so invariants stay in one place.
     */
    static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.ofEntries(
            Map.entry(OptimizationEventEntity.STAGE_DISPATCH_INITIATED, Set.of(
                    OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                    OptimizationEventEntity.STAGE_PROPOSAL_REJECTED)),
            Map.entry(OptimizationEventEntity.STAGE_PROPOSAL_PENDING, Set.of(
                    OptimizationEventEntity.STAGE_PROPOSAL_APPROVED,
                    OptimizationEventEntity.STAGE_PROPOSAL_REJECTED)),
            Map.entry(OptimizationEventEntity.STAGE_PROPOSAL_APPROVED, Set.of(
                    OptimizationEventEntity.STAGE_CANDIDATE_GENERATING)),
            Map.entry(OptimizationEventEntity.STAGE_CANDIDATE_GENERATING, Set.of(
                    OptimizationEventEntity.STAGE_CANDIDATE_READY,
                    OptimizationEventEntity.STAGE_CANDIDATE_FAILED)),
            // Phase 1.4 — operator-triggered manual retry. Re-enters the
            // candidate generation pipeline; same outcome edges as the
            // initial proposal_approved → candidate_generating transition.
            Map.entry(OptimizationEventEntity.STAGE_CANDIDATE_FAILED, Set.of(
                    OptimizationEventEntity.STAGE_CANDIDATE_GENERATING)),
            Map.entry(OptimizationEventEntity.STAGE_CANDIDATE_READY, Set.of(
                    OptimizationEventEntity.STAGE_AB_RUNNING,
                    // ★ 2026-05-24 V1 r3.3 fix: behavior_rule surface 无 A/B 路径
                    // (V5.1 backlog)，candidate_ready 在 V4 是 behavior_rule 的
                    // 终态。允许 candidate_ready → candidate_generating 让 operator
                    // 可点 Retry 重生 candidate (跟 candidate_failed → candidate_generating
                    // 对称)。surface=skill/prompt 走 ab_running 是预期路径，但允许
                    // 这条 transition 不破坏：retryCandidateGeneration 在 service
                    // 层仍按 surface 防滥用。
                    OptimizationEventEntity.STAGE_CANDIDATE_GENERATING)),
            Map.entry(OptimizationEventEntity.STAGE_AB_RUNNING, Set.of(
                    OptimizationEventEntity.STAGE_AB_PASSED,
                    OptimizationEventEntity.STAGE_AB_FAILED)),
            // FLYWHEEL-LOOP-CLOSURE Phase 1.2 (2026-05-16): add the direct
            // ab_passed → promoted edge so the dogfood single-user flow can
            // skip the canary stage entirely (V6 logic disable; the canary
            // schema + code stays dormant for a future multi-user re-enable).
            // ab_passed → canary_started is kept for backward compatibility:
            // when canary is re-enabled, the old edge is the natural path.
            Map.entry(OptimizationEventEntity.STAGE_AB_PASSED, Set.of(
                    OptimizationEventEntity.STAGE_CANARY_STARTED,
                    OptimizationEventEntity.STAGE_PROMOTED)),
            Map.entry(OptimizationEventEntity.STAGE_CANARY_STARTED, Set.of(
                    OptimizationEventEntity.STAGE_PROMOTED,
                    OptimizationEventEntity.STAGE_ROLLED_BACK)),
            Map.entry(OptimizationEventEntity.STAGE_PROMOTED, Set.of(
                    OptimizationEventEntity.STAGE_VERIFIED)));

    private final OptimizationEventRepository eventRepository;
    private final SkillDraftService skillDraftService;
    private final PromptImproverService promptImproverService;
    private final BehaviorRuleImproverService behaviorRuleImproverService;
    private final AgentRepository agentRepository;
    private final AttributionEventBroadcaster broadcaster;
    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.3 (2026-05-16) — publishes
     * {@link OptimizationEventStageChangeEvent} after every stage transition
     * write so {@link OptimizationEventAutoTriggerListener} can auto-trigger
     * the surface-specific A/B run on {@code candidate_ready}. AFTER_COMMIT
     * + REQUIRES_NEW on the listener side means the publish is safe even if
     * the surrounding tx later rolls back (no event delivery on rollback).
     */
    private final ApplicationEventPublisher eventPublisher;

    public AttributionApprovalService(OptimizationEventRepository eventRepository,
                                      SkillDraftService skillDraftService,
                                      PromptImproverService promptImproverService,
                                      BehaviorRuleImproverService behaviorRuleImproverService,
                                      AttributionEventBroadcaster broadcaster,
                                      ApplicationEventPublisher eventPublisher) {
        this(eventRepository, skillDraftService, promptImproverService, behaviorRuleImproverService,
                broadcaster, eventPublisher, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AttributionApprovalService(OptimizationEventRepository eventRepository,
                                      SkillDraftService skillDraftService,
                                      PromptImproverService promptImproverService,
                                      BehaviorRuleImproverService behaviorRuleImproverService,
                                      AttributionEventBroadcaster broadcaster,
                                      ApplicationEventPublisher eventPublisher,
                                      AgentRepository agentRepository) {
        this.eventRepository = eventRepository;
        this.skillDraftService = skillDraftService;
        this.promptImproverService = promptImproverService;
        this.behaviorRuleImproverService = behaviorRuleImproverService;
        this.agentRepository = agentRepository;
        this.broadcaster = broadcaster;
        this.eventPublisher = eventPublisher;
    }

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.3 (2026-05-16) — single helper that builds
     * the {@link OptimizationEventStageChangeEvent} record from the current
     * entity state + publishes via {@link ApplicationEventPublisher}.
     *
     * <p>Called immediately after every {@code eventRepository.save(event)} +
     * existing {@code broadcaster.broadcastStageTransition(...)} so the
     * AFTER_COMMIT listener observes the same persisted state that the WS
     * broadcaster pushed. The publish itself is cheap (Spring publishes into
     * a thread-local sink + replays at commit-time); the actual listener work
     * happens on the {@code abEvalLoopExecutor} pool in a fresh transaction.
     */
    private void publishStageChange(OptimizationEventEntity event, String fromStage) {
        eventPublisher.publishEvent(new OptimizationEventStageChangeEvent(
                event.getId(),
                fromStage,
                event.getStage(),
                event.getSurfaceType(),
                event.getAgentId(),
                event.getPatternId(),
                event.getCandidatePromptVersionUuid(),
                event.getCandidateSkillDraftUuid(),
                event.getCandidateBehaviorRuleVersionId()));
    }

    /**
     * Operator approves a proposal_pending event. Flips stage to
     * proposal_approved → candidate_generating → triggers the surface-specific
     * candidate generator → on success advances to candidate_ready (Phase 1.4
     * downstream sets it to ab_running etc.); on failure persists
     * candidate_failed without rethrowing.
     *
     * @param eventId         event id to approve
     * @param approverUserId  approver (logged for audit; no FK to t_user yet)
     * @return the persisted event after all transitions
     * @throws IllegalStateException     if stage is not proposal_pending or surface is unsupported
     * @throws IllegalArgumentException  if eventId is null or not found
     */
    public OptimizationEventEntity approve(Long eventId, Long approverUserId) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        OptimizationEventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("optimization event not found: " + eventId));

        validateTransition(event.getStage(), OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);
        if (!APPROVABLE_SURFACES.contains(event.getSurfaceType())) {
            throw new IllegalStateException("approve: unsupported surface=" + event.getSurfaceType()
                    + " for eventId=" + eventId
                    + " (V4 ratify — approvable: skill / prompt / behavior_rule)");
        }

        // Stage 1: proposal_pending → proposal_approved
        String stagePending = event.getStage();
        event.setStage(OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);
        event = eventRepository.save(event);
        // Broadcast in-tx (V3 dogfood trade-off; see class javadoc).
        broadcaster.broadcastStageTransition(event, stagePending);
        // Phase 1.3: stage-change domain event for the AFTER_COMMIT listener.
        publishStageChange(event, stagePending);
        log.info("[AttributionApproval] approved eventId={} approverUserId={} surface={}",
                eventId, approverUserId, event.getSurfaceType());

        return runCandidateGeneration(event, approverUserId,
                OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);
    }

    /**
     * Phase 1.4 — operator-triggered manual retry for {@code candidate_failed}
     * events (e.g. transient LLM provider outage that the original
     * {@link #approve} catch-block recorded). Re-enters the candidate generation
     * pipeline; same {@code candidate_generating → candidate_ready /
     * candidate_failed} outcome edges as {@link #approve}.
     *
     * <p>Per tech-design.md §6 + Phase 1.3 reviewer fix: auto-retry is NOT done
     * by the cron dispatcher (would burn LLM budget on systematic failures);
     * retry is exclusively operator-driven via this method.
     *
     * @param eventId         {@code candidate_failed} event id
     * @param approverUserId  operator triggering retry (logged for audit)
     * @return event in {@code candidate_ready} (success) or
     *         {@code candidate_failed} (still failing) state
     * @throws IllegalStateException     if stage != candidate_failed or surface unsupported
     * @throws IllegalArgumentException  if eventId null / not found
     */
    public OptimizationEventEntity retryCandidateGeneration(Long eventId, Long approverUserId) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        OptimizationEventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("optimization event not found: " + eventId));

        validateTransition(event.getStage(), OptimizationEventEntity.STAGE_CANDIDATE_GENERATING);
        if (!APPROVABLE_SURFACES.contains(event.getSurfaceType())) {
            throw new IllegalStateException("retry: unsupported surface=" + event.getSurfaceType()
                    + " for eventId=" + eventId
                    + " (V4 ratify — retryable: skill / prompt / behavior_rule)");
        }
        log.info("[AttributionApproval] retrying eventId={} approverUserId={} surface={} (was candidate_failed)",
                eventId, approverUserId, event.getSurfaceType());

        return runCandidateGeneration(event, approverUserId,
                OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
    }

    /**
     * Shared candidate-generation runner used by both {@link #approve} (after
     * the proposal_pending → proposal_approved write) and
     * {@link #retryCandidateGeneration} (after the candidate_failed →
     * candidate_generating write — well, this method handles that write
     * internally so the caller doesn't repeat it).
     *
     * <p>Steps:
     * <ol>
     *   <li>Stage → candidate_generating + save + broadcast (if not already there)</li>
     *   <li>switch surface → SkillDraftService / PromptImproverService</li>
     *   <li>On success: stage → candidate_ready</li>
     *   <li>On RuntimeException: stage → candidate_failed + description prefix</li>
     *   <li>save final state + broadcast</li>
     * </ol>
     *
     * @param event             event currently in {@code proposal_approved}
     *                          (called from approve) or {@code candidate_failed}
     *                          (called from retry)
     * @param approverUserId    user triggering the action
     * @param previousStageHint stage we just transitioned out of (for broadcast
     *                          previousStage field on the candidate_generating
     *                          event)
     */
    private OptimizationEventEntity runCandidateGeneration(OptimizationEventEntity event,
                                                            Long approverUserId,
                                                            String previousStageHint) {
        // Move to candidate_generating (interim state visible to dashboard
        // during the potentially-slow candidate gen call).
        event.setStage(OptimizationEventEntity.STAGE_CANDIDATE_GENERATING);
        event = eventRepository.save(event);
        // Broadcast in-tx (V3 dogfood trade-off; see class javadoc).
        broadcaster.broadcastStageTransition(event, previousStageHint);
        // Phase 1.3: stage-change domain event for the AFTER_COMMIT listener.
        publishStageChange(event, previousStageHint);

        // Dispatch surface-specific candidate generation. catch RuntimeException
        // to surface failures as candidate_failed rather than rolling the outer
        // transaction (Phase 1.3 reviewer fix used REQUIRES_NEW on the child
        // services to prevent rollback contamination).
        String stageGenerating = OptimizationEventEntity.STAGE_CANDIDATE_GENERATING;
        try {
            switch (event.getSurfaceType()) {
                case OptimizationEventEntity.SURFACE_SKILL -> dispatchSkillSurface(event);
                case OptimizationEventEntity.SURFACE_PROMPT -> dispatchPromptSurface(event, approverUserId);
                case OptimizationEventEntity.SURFACE_BEHAVIOR_RULE ->
                        dispatchBehaviorRuleSurface(event, approverUserId);
                default ->
                        // Unreachable due to caller validation, but keep switch exhaustive.
                        throw new IllegalStateException(
                                "runCandidateGeneration: unhandled surface=" + event.getSurfaceType());
            }
            event.setStage(OptimizationEventEntity.STAGE_CANDIDATE_READY);
        } catch (RuntimeException e) {
            log.error("[AttributionApproval] candidate generation FAILED for eventId={} surface={}: {}",
                    event.getId(), event.getSurfaceType(), e.getMessage(), e);
            event.setStage(OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
            event.setDescription("[candidate_failed] " + e.getMessage());
        }
        OptimizationEventEntity saved = eventRepository.save(event);
        // Broadcast in-tx (V3 dogfood trade-off; see class javadoc).
        broadcaster.broadcastStageTransition(saved, stageGenerating);
        // Phase 1.3: stage-change domain event for the AFTER_COMMIT listener.
        // Covers both candidate_ready (success branch) and candidate_failed
        // (catch branch); the listener filters by toStage == candidate_ready.
        publishStageChange(saved, stageGenerating);
        return saved;
    }

    /**
     * Operator rejects a proposal_pending event. Stage → proposal_rejected
     * (terminal). {@code reason} is logged + folded into description (no new
     * column added — see Phase 1.3 brief: prefer field reuse).
     *
     * @param eventId         event id to reject
     * @param approverUserId  approver (logged for audit)
     * @param reason          operator-supplied rejection reason (free text)
     * @return the persisted event after stage transition
     */
    public OptimizationEventEntity reject(Long eventId, Long approverUserId, String reason) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        OptimizationEventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("optimization event not found: " + eventId));

        validateTransition(event.getStage(), OptimizationEventEntity.STAGE_PROPOSAL_REJECTED);
        String previousStage = event.getStage();
        event.setStage(OptimizationEventEntity.STAGE_PROPOSAL_REJECTED);
        if (reason != null && !reason.isBlank()) {
            // Prefix description so the original curator-authored text is
            // preserved for audit; null-safe via blank check.
            String existing = event.getDescription() == null ? "" : event.getDescription();
            event.setDescription("[rejected: " + reason.trim() + "] " + existing);
        }
        OptimizationEventEntity saved = eventRepository.save(event);
        // Broadcast in-tx (V3 dogfood trade-off; see class javadoc).
        broadcaster.broadcastStageTransition(saved, previousStage);
        // Phase 1.3: stage-change domain event for the AFTER_COMMIT listener
        // (listener filters by toStage; proposal_rejected is a no-op there).
        publishStageChange(saved, previousStage);
        log.info("[AttributionApproval] rejected eventId={} approverUserId={} reason={}",
                eventId, approverUserId, reason == null ? "(none)" : reason);
        return saved;
    }

    private void dispatchSkillSurface(OptimizationEventEntity event) {
        String suggestedSkillName = "AttrSkill" + event.getPatternId() + "_" + event.getId();
        Long ownerId = resolveSkillOwnerId(event);
        SkillDraftEntity draft = skillDraftService.createDraftFromAttribution(
                event.getId(),
                event.getPatternId(),
                event.getDescription(),
                event.getExpectedImpact(),
                event.getChangeType(),
                ownerId,
                suggestedSkillName);
        // FLYWHEEL-LOOP-CLOSURE Phase 1.2 (V88, 2026-05-16): persist the draft
        // UUID link to the sidecar column so the Phase 1.3 listener / Phase
        // 1.4 /run-ab endpoint can resolve event → draft.
        // The legacy BIGINT candidateSkillId is intentionally left null at
        // draft time — it gets populated later in SkillDraftService.approveDraft
        // when the draft merges into a SkillEntity (BIGINT PK).
        event.setCandidateSkillDraftUuid(draft.getId());
        log.info("[AttributionApproval] eventId={} → skill draft created (draftId={})",
                event.getId(), draft.getId());
    }

    private Long resolveSkillOwnerId(OptimizationEventEntity event) {
        Long agentId = event.getAgentId();
        if (agentId == null) {
            throw new IllegalStateException("dispatchSkillSurface: eventId=" + event.getId()
                    + " has null agentId — cannot resolve skill draft owner");
        }
        if (agentRepository == null) {
            return agentId;
        }
        AgentEntity agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalStateException(
                        "dispatchSkillSurface: agent not found: " + agentId));
        Long ownerId = agent.getOwnerId();
        if (ownerId == null) {
            throw new IllegalStateException("dispatchSkillSurface: agentId=" + agentId
                    + " has null ownerId — cannot resolve skill draft owner");
        }
        return ownerId;
    }

    private void dispatchPromptSurface(OptimizationEventEntity event, Long approverUserId) {
        if (event.getAgentId() == null) {
            throw new IllegalStateException("dispatchPromptSurface: eventId=" + event.getId()
                    + " has null agentId — cannot generate prompt version");
        }
        ImprovementStartResult result = promptImproverService.startImprovementFromAttribution(
                event.getId(),
                String.valueOf(event.getAgentId()),
                event.getDescription(),
                approverUserId);
        // FLYWHEEL-LOOP-CLOSURE Phase 1.2 (V88, 2026-05-16): write the prompt
        // version UUID to the sidecar column. The legacy BIGINT
        // candidatePromptVersionId column stays NULL on the attribution path
        // (PromptVersionEntity.id is a UUID string that won't fit in BIGINT —
        // documented as a known V3.2 link-bug now resolved via V88 sidecar).
        event.setCandidatePromptVersionUuid(result.promptVersionId());
        log.info("[AttributionApproval] eventId={} → prompt version created (versionId={})",
                event.getId(), result.promptVersionId());
    }

    /**
     * V4 MULTI-SURFACE-FLYWHEEL Phase 1.3: behavior_rule surface dispatch
     * branch. Mirrors {@link #dispatchPromptSurface} (also delegates to a
     * REQUIRES_NEW improver service that does synchronous LLM fill +
     * audit-trail rethrow), with the key difference that
     * {@link OptimizationEventEntity#getCandidateBehaviorRuleVersionId()} is
     * VARCHAR(36) (UUID), so the link can be persisted directly here rather
     * than relying on a post-hoc lookup by description prefix.
     *
     * <p><b>Tx isolation</b>: {@code BehaviorRuleImproverService.startImprovementFromAttribution}
     * uses {@code Propagation.REQUIRES_NEW} (same pattern as V3.1
     * PromptImproverService). Any LLM failure surfaces as a thrown exception
     * that the outer {@link #runCandidateGeneration} catch-block converts to
     * {@code stage=candidate_failed} without rolling back the child tx's
     * version row save (audit trail preserved per V3.1 reviewer fix).
     *
     * @param event          optimization event in {@code candidate_generating} stage
     * @param approverUserId operator triggering the approve (logged for audit)
     */
    private void dispatchBehaviorRuleSurface(OptimizationEventEntity event, Long approverUserId) {
        if (event.getAgentId() == null) {
            throw new IllegalStateException("dispatchBehaviorRuleSurface: eventId=" + event.getId()
                    + " has null agentId — cannot generate behavior_rule version");
        }
        ImprovementStartResult result = behaviorRuleImproverService.startImprovementFromAttribution(
                event.getId(),
                String.valueOf(event.getAgentId()),
                event.getDescription(),
                approverUserId);
        // BehaviorRuleVersionEntity.id is a UUID string → fits cleanly in the
        // V83 candidate_behavior_rule_version_id VARCHAR(36) column, no skip
        // path needed (unlike the skill/prompt Long-vs-UUID mismatch above).
        event.setCandidateBehaviorRuleVersionId(result.promptVersionId());
        log.info("[AttributionApproval] eventId={} → behavior_rule version created (versionId={})",
                event.getId(), result.promptVersionId());
    }

    /**
     * Throws {@link IllegalStateException} if the {@code from → to} transition
     * isn't in {@link #ALLOWED_TRANSITIONS}. Package-private for tests.
     */
    void validateTransition(String fromStage, String toStage) {
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(fromStage, Set.of());
        if (!allowed.contains(toStage)) {
            throw new IllegalStateException("Illegal stage transition: "
                    + (fromStage == null ? "<null>" : fromStage)
                    + " → " + toStage
                    + " (allowed: " + allowed + ")");
        }
    }

    /** Test/debug helper — exposes the latest persisted state without a stale ref. */
    Optional<OptimizationEventEntity> reload(Long eventId) {
        return eventRepository.findById(eventId);
    }
}
