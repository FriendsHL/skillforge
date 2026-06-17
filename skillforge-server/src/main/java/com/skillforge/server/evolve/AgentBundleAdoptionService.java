package com.skillforge.server.evolve;

import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.evolve.dto.CandidateBundle;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.improve.PromptPromotionService;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.improve.SkillNameConflictException;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — orchestrates human adoption of a winning candidate
 * bundle across the three optimisation surfaces (prompt / behavior_rule / skill).
 *
 * <p><b>No {@code @Transactional} here on purpose.</b> Each surface delegates to
 * a service whose promote method carries its own transaction, so the three
 * commit independently. This is the AC-4 isolation contract: a failure on one
 * surface (e.g. a skill name conflict) leaves the surfaces that already
 * committed intact — no half-adopted rollback, no silent loss. The result
 * reports each surface's outcome so the FE can show "prompt ✓ / rule ✓ / skill ✗".
 *
 * <p>Ownership is pre-validated for ALL non-null pointers BEFORE any write
 * (fail-fast, mirrors {@code BundleApplicator}'s W7 checks): a cross-agent
 * pointer throws {@link IllegalArgumentException} and nothing is adopted — this
 * is the privilege-escalation guard (a caller can't flip another agent's config
 * by passing its version id). The controller additionally bounds the pointer
 * space to a kept iteration of the run.
 */
@Service
public class AgentBundleAdoptionService {

    private static final Logger log = LoggerFactory.getLogger(AgentBundleAdoptionService.class);

    /**
     * Per-surface outcome. {@code status}: {@code "ok"} (changed), {@code "noop"}
     * (already in the target state), or {@code "failed"} (the surface threw —
     * {@code reason} carries the message). {@code reason} is null for {@code "ok"}.
     *
     * <p>footgun #6 contract — FE mirror: {@code { status: 'ok' | 'noop' | 'failed', reason: string | null }}.
     */
    public record SurfaceResult(String status, String reason) {
        public static SurfaceResult ok()                 { return new SurfaceResult("ok", null); }
        public static SurfaceResult noop(String reason)  { return new SurfaceResult("noop", reason); }
        public static SurfaceResult failed(String reason){ return new SurfaceResult("failed", reason); }

        public boolean isFailed() { return "failed".equals(status); }
    }

    /**
     * The adopt outcome across surfaces. A surface field is {@code null} when the
     * bundle carried no pointer for it. {@code anyFailed} is true iff any non-null
     * surface failed.
     *
     * <p>footgun #6 contract — FE mirror:
     * {@code { prompt: SurfaceResult | null, rule: SurfaceResult | null, skill: SurfaceResult | null, anyFailed: boolean }}.
     */
    public record AdoptResult(SurfaceResult prompt, SurfaceResult rule, SurfaceResult skill, boolean anyFailed) {}

    private final PromptPromotionService promptPromotionService;
    private final BehaviorRulePromotionService behaviorRulePromotionService;
    private final SkillDraftService skillDraftService;
    private final PromptVersionRepository promptVersionRepository;
    private final BehaviorRuleVersionRepository behaviorRuleVersionRepository;
    private final SkillDraftRepository skillDraftRepository;

    public AgentBundleAdoptionService(PromptPromotionService promptPromotionService,
                                      BehaviorRulePromotionService behaviorRulePromotionService,
                                      SkillDraftService skillDraftService,
                                      PromptVersionRepository promptVersionRepository,
                                      BehaviorRuleVersionRepository behaviorRuleVersionRepository,
                                      SkillDraftRepository skillDraftRepository) {
        this.promptPromotionService = promptPromotionService;
        this.behaviorRulePromotionService = behaviorRulePromotionService;
        this.skillDraftService = skillDraftService;
        this.promptVersionRepository = promptVersionRepository;
        this.behaviorRuleVersionRepository = behaviorRuleVersionRepository;
        this.skillDraftRepository = skillDraftRepository;
    }

    /**
     * Adopt {@code bundle} onto {@code agentId}. Ownership is checked up-front
     * for every non-null pointer (throws {@link IllegalArgumentException} before
     * any write on mismatch / missing). Then each surface is adopted in its own
     * transaction, isolated from the others' failures.
     *
     * @param bundle  the pointer tuple (a null pointer = no change on that surface)
     * @param agentId the target agent id (string form of {@code FlywheelRun.agentId})
     * @param userId  the operator id
     * @return the per-surface result; a null surface field means the bundle had
     *         no pointer there
     * @throws IllegalArgumentException a non-null pointer is unknown or belongs
     *                                  to a different agent (ownership)
     */
    public AdoptResult adopt(CandidateBundle bundle, String agentId, Long userId) {
        // FAIL-FAST: validate ownership of every non-null pointer before writing.
        validateOwnership(bundle, agentId);

        SurfaceResult prompt = adoptPrompt(bundle.promptVersionId(), agentId, userId);
        SurfaceResult rule = adoptRule(bundle.behaviorRuleVersionId());
        SurfaceResult skill = adoptSkill(bundle.skillDraftId(), userId);

        boolean anyFailed = isFailed(prompt) || isFailed(rule) || isFailed(skill);
        log.info("[Adopt] bundle adopted for agent {}: prompt={} rule={} skill={} anyFailed={}",
                agentId, statusOf(prompt), statusOf(rule), statusOf(skill), anyFailed);
        return new AdoptResult(prompt, rule, skill, anyFailed);
    }

    // ─── per-surface adopt (each isolated; a throw becomes a failed result) ──

    private SurfaceResult adoptPrompt(String promptVersionId, String agentId, Long userId) {
        if (isBlank(promptVersionId)) {
            return null;
        }
        try {
            promptPromotionService.promoteByHuman(promptVersionId, agentId, userId);
            return SurfaceResult.ok();
        } catch (Exception e) {
            log.warn("[Adopt] prompt surface failed: promptVersionId={} : {}",
                    promptVersionId, e.getMessage());
            return SurfaceResult.failed(e.getMessage());
        }
    }

    private SurfaceResult adoptRule(String behaviorRuleVersionId) {
        if (isBlank(behaviorRuleVersionId)) {
            return null;
        }
        try {
            BehaviorRuleVersionEntity version = behaviorRuleVersionRepository
                    .findById(behaviorRuleVersionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "behavior_rule version not found: " + behaviorRuleVersionId));
            if (BehaviorRuleVersionEntity.STATUS_ACTIVE.equals(version.getStatus())) {
                return SurfaceResult.noop("already active");
            }
            // promote() is public + bypasses the dual-criteria gate (the human is
            // the gate). It performs the V82 retire-first → activate transition.
            behaviorRulePromotionService.promote(version);
            return SurfaceResult.ok();
        } catch (Exception e) {
            log.warn("[Adopt] behavior_rule surface failed: behaviorRuleVersionId={} : {}",
                    behaviorRuleVersionId, e.getMessage());
            return SurfaceResult.failed(e.getMessage());
        }
    }

    private SurfaceResult adoptSkill(String skillDraftId, Long userId) {
        if (isBlank(skillDraftId)) {
            return null;
        }
        try {
            // forceCreate=true: the human already reviewed the diff in the UI and
            // confirmed adoption, so bypass the high-similarity gate.
            skillDraftService.approveDraft(skillDraftId, userId, true);
            return SurfaceResult.ok();
        } catch (SkillNameConflictException e) {
            log.warn("[Adopt] skill surface name conflict: skillDraftId={} : {}",
                    skillDraftId, e.getMessage());
            return SurfaceResult.failed("Skill name conflict: " + e.getMessage());
        } catch (Exception e) {
            log.warn("[Adopt] skill surface failed: skillDraftId={} : {}",
                    skillDraftId, e.getMessage());
            return SurfaceResult.failed(e.getMessage());
        }
    }

    // ─── ownership pre-validation (mirrors BundleApplicator W7) ──────────────

    private void validateOwnership(CandidateBundle bundle, String agentId) {
        if (!isBlank(bundle.promptVersionId())) {
            PromptVersionEntity v = promptVersionRepository.findById(bundle.promptVersionId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "prompt version not found for adopt: " + bundle.promptVersionId()));
            if (!agentId.equals(v.getAgentId())) {
                throw new IllegalArgumentException(
                        "prompt version " + bundle.promptVersionId() + " belongs to agent "
                                + v.getAgentId() + " but adopt targets agent " + agentId);
            }
        }
        if (!isBlank(bundle.behaviorRuleVersionId())) {
            BehaviorRuleVersionEntity v = behaviorRuleVersionRepository
                    .findById(bundle.behaviorRuleVersionId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "behavior_rule version not found for adopt: "
                                    + bundle.behaviorRuleVersionId()));
            if (!agentId.equals(v.getAgentId())) {
                throw new IllegalArgumentException(
                        "behavior_rule version " + bundle.behaviorRuleVersionId() + " belongs to agent "
                                + v.getAgentId() + " but adopt targets agent " + agentId);
            }
        }
        if (!isBlank(bundle.skillDraftId())) {
            SkillDraftEntity draft = skillDraftRepository.findById(bundle.skillDraftId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "skill draft not found for adopt: " + bundle.skillDraftId()));
            // Evolve drafts may carry a null targetAgentId (system-driven path);
            // tolerate null (logged), block a non-null cross-agent mismatch
            // (mirrors BundleApplicator W7).
            if (draft.getTargetAgentId() != null
                    && !agentId.equals(String.valueOf(draft.getTargetAgentId()))) {
                throw new IllegalArgumentException(
                        "skill draft " + bundle.skillDraftId() + " targets agent "
                                + draft.getTargetAgentId() + " but adopt targets agent " + agentId);
            }
        }
    }

    private static boolean isFailed(SurfaceResult r) {
        return r != null && r.isFailed();
    }

    private static String statusOf(SurfaceResult r) {
        return r == null ? "—" : r.status();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
