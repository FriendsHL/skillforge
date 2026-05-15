package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.repository.OptimizationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     */
    static final Set<String> APPROVABLE_SURFACES = Set.of(
            OptimizationEventEntity.SURFACE_SKILL,
            OptimizationEventEntity.SURFACE_PROMPT);

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
            Map.entry(OptimizationEventEntity.STAGE_CANDIDATE_READY, Set.of(
                    OptimizationEventEntity.STAGE_AB_RUNNING)),
            Map.entry(OptimizationEventEntity.STAGE_AB_RUNNING, Set.of(
                    OptimizationEventEntity.STAGE_AB_PASSED,
                    OptimizationEventEntity.STAGE_AB_FAILED)),
            Map.entry(OptimizationEventEntity.STAGE_AB_PASSED, Set.of(
                    OptimizationEventEntity.STAGE_CANARY_STARTED)),
            Map.entry(OptimizationEventEntity.STAGE_CANARY_STARTED, Set.of(
                    OptimizationEventEntity.STAGE_PROMOTED,
                    OptimizationEventEntity.STAGE_ROLLED_BACK)),
            Map.entry(OptimizationEventEntity.STAGE_PROMOTED, Set.of(
                    OptimizationEventEntity.STAGE_VERIFIED)));

    private final OptimizationEventRepository eventRepository;
    private final SkillDraftService skillDraftService;
    private final PromptImproverService promptImproverService;

    public AttributionApprovalService(OptimizationEventRepository eventRepository,
                                      SkillDraftService skillDraftService,
                                      PromptImproverService promptImproverService) {
        this.eventRepository = eventRepository;
        this.skillDraftService = skillDraftService;
        this.promptImproverService = promptImproverService;
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
                    + " (V3 ratify #6 — only skill/prompt approvable)");
        }

        // Stage 1: proposal_pending → proposal_approved
        event.setStage(OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);
        event = eventRepository.save(event);
        log.info("[AttributionApproval] approved eventId={} approverUserId={} surface={}",
                eventId, approverUserId, event.getSurfaceType());

        // Stage 2: proposal_approved → candidate_generating (interim state visible
        // to dashboard during the (potentially-slow) candidate gen call).
        event.setStage(OptimizationEventEntity.STAGE_CANDIDATE_GENERATING);
        event = eventRepository.save(event);

        // Stage 3: dispatch surface-specific candidate generation. catch ALL to
        // surface failures as candidate_failed rather than rolling the whole
        // transaction — the operator needs to see "we tried and it failed" not
        // "approval mysteriously didn't take".
        try {
            switch (event.getSurfaceType()) {
                case OptimizationEventEntity.SURFACE_SKILL -> dispatchSkillSurface(event, approverUserId);
                case OptimizationEventEntity.SURFACE_PROMPT -> dispatchPromptSurface(event, approverUserId);
                default ->
                        // Unreachable due to validation above, but keep switch exhaustive
                        // so future surface additions force a compile-time decision.
                        throw new IllegalStateException("approve: unhandled surface=" + event.getSurfaceType());
            }
            // Successful generation → candidate_ready (Phase 1.4 will wire the
            // automatic candidate_ready → ab_running transition).
            event.setStage(OptimizationEventEntity.STAGE_CANDIDATE_READY);
        } catch (RuntimeException e) {
            log.error("[AttributionApproval] candidate generation FAILED for eventId={} surface={}: {}",
                    eventId, event.getSurfaceType(), e.getMessage(), e);
            event.setStage(OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
            // Stash the failure reason in description (overrides curator's
            // proposal description). Phase 1.4 dashboard timeline can render
            // both via attributionEventId pivot if needed.
            event.setDescription("[candidate_failed] " + e.getMessage());
        }
        return eventRepository.save(event);
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
        event.setStage(OptimizationEventEntity.STAGE_PROPOSAL_REJECTED);
        if (reason != null && !reason.isBlank()) {
            // Prefix description so the original curator-authored text is
            // preserved for audit; null-safe via blank check.
            String existing = event.getDescription() == null ? "" : event.getDescription();
            event.setDescription("[rejected: " + reason.trim() + "] " + existing);
        }
        OptimizationEventEntity saved = eventRepository.save(event);
        log.info("[AttributionApproval] rejected eventId={} approverUserId={} reason={}",
                eventId, approverUserId, reason == null ? "(none)" : reason);
        return saved;
    }

    private void dispatchSkillSurface(OptimizationEventEntity event, Long approverUserId) {
        String suggestedSkillName = "AttrSkill" + event.getPatternId() + "_" + event.getId();
        SkillDraftEntity draft = skillDraftService.createDraftFromAttribution(
                event.getId(),
                event.getPatternId(),
                event.getDescription(),
                event.getExpectedImpact(),
                event.getChangeType(),
                approverUserId != null ? approverUserId : event.getAgentId(),
                suggestedSkillName);
        // SkillDraftEntity.id is a String UUID; OptimizationEventEntity stores
        // candidateSkillId as Long — drafts approved into SkillEntity (BIGINT
        // PK) will populate candidateSkillId at SkillDraftService.approveDraft
        // time. For the draft phase we just log the linkage.
        log.info("[AttributionApproval] eventId={} → skill draft created (draftId={})",
                event.getId(), draft.getId());
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
        // candidatePromptVersionId is a Long column but PromptVersionEntity.id
        // is a String UUID. We can't fit a UUID into BIGINT — log the link for
        // now; Phase 1.4 may revisit (e.g. add candidatePromptVersionUuid or
        // change column type) once the dashboard surface concretely needs it.
        log.info("[AttributionApproval] eventId={} → prompt version created (versionId={})",
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
