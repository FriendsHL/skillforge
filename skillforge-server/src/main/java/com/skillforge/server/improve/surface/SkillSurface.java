package com.skillforge.server.improve.surface;

import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.repository.SkillRepository;
import org.springframework.stereotype.Component;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — Skill surface adapter.
 *
 * <p>Pure adapter over V2 {@link SkillAbEvalService} / {@link SkillRepository}
 * — zero behavior drift. Phase 1.2's {@code AbstractAbEvalRunner} will route
 * through this adapter once it's wired, but in Phase 1.1 the existing V2 A/B
 * pipeline keeps calling {@code SkillAbEvalService} directly. The adapter
 * exists to:
 * <ol>
 *   <li>Validate the 6-method {@link OptimizableSurface} shape against a real
 *       service (proves the interface is usable).</li>
 *   <li>Provide a {@code surfaceType()="skill"} entry into the
 *       {@link SurfaceRegistry} so Phase 1.3's surface-aware dispatch can
 *       find it.</li>
 * </ol>
 *
 * <p>Methods that don't have a 1:1 entry point on the existing services
 * (sandbox injection, candidate creation without an attribution context,
 * rollback) throw {@link UnsupportedOperationException} with a Phase 1.2
 * pointer rather than inventing new behavior in Phase 1.1.
 */
@Component
public class SkillSurface implements OptimizableSurface<SkillEntity> {

    public static final String SURFACE_TYPE = "skill";

    private final SkillRepository skillRepository;
    private final SkillAbEvalService abEvalService;

    public SkillSurface(SkillRepository skillRepository,
                        SkillAbEvalService abEvalService) {
        this.skillRepository = skillRepository;
        this.abEvalService = abEvalService;
    }

    @Override
    public String surfaceType() {
        return SURFACE_TYPE;
    }

    @Override
    public SkillEntity loadActive(Long agentId) {
        // Skill "active" is a fuzzy notion for the skill surface — an agent
        // can have N enabled skills, not a single "active" version. This
        // adapter intentionally does NOT try to resolve "the" active skill
        // (that would require parsing agent.skillIds JSON + filtering by
        // enabled=true + picking one); Phase 1.2 callers that need a
        // specific skill will use loadVersion(skillId) instead.
        //
        // Returning null is the correct "no single active" signal. Phase 1.2
        // AbstractAbEvalRunner skill subclass overrides this with the
        // candidate's parent-skill resolution that V2 already does inside
        // SkillAbEvalService.runAbTestAsync.
        return null;
    }

    @Override
    public SkillEntity loadVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) return null;
        try {
            return skillRepository.findById(Long.parseLong(versionId)).orElse(null);
        } catch (NumberFormatException e) {
            // Skill ids are BIGINT (numeric); a non-numeric versionId is a
            // client mistake. Return null rather than throw — keeps the
            // adapter side-effect-free even on bad input.
            return null;
        }
    }

    @Override
    public SkillEntity createCandidate(SkillEntity baseline, String improvementContext) {
        // Skill candidates today come from the SkillDraftService.approveDraft
        // path (drafts created from attribution OR from extraction). Routing
        // through this method would require recreating that 7-step state
        // machine for a fundamentally different shape (drafts → skills, not
        // skill → version-of-skill). Phase 1.2's runner will instead read the
        // candidate skill row directly via loadVersion(candidateSkillId).
        throw new UnsupportedOperationException(
                "SkillSurface.createCandidate: skills use the SkillDraftService.approveDraft "
                        + "flow; OptimizableSurface.createCandidate is not the right entry point. "
                        + "Phase 1.2 AbstractAbEvalRunner.skill resolves candidates via loadVersion.");
    }

    @Override
    public void injectForSandbox(SandboxContext ctx, SkillEntity version) {
        // V2 sandbox injection happens inside SkillAbEvalService.runSingleScenario
        // via SandboxSkillRegistryFactory.buildSandboxRegistryWithSkills. That
        // path needs the full SkillDefinition (loaded from SkillPackageLoader),
        // not just the SkillEntity — recreating it here would duplicate the
        // V2 path. Phase 1.2 refactor will lift the sandbox build into the
        // AbstractAbEvalRunner.skill subclass.
        throw new UnsupportedOperationException(
                "SkillSurface.injectForSandbox: Phase 1.2 (AbstractAbEvalRunner.skill subclass) "
                        + "will pull sandbox plumbing from SkillAbEvalService.runSingleScenario "
                        + "into this hook. Until then call SkillAbEvalService directly.");
    }

    @Override
    public void promote(SkillEntity candidate) {
        // V2 SkillAbEvalService.promoteCandidate owns the V64-safe ordering
        // (disable parent + flush before enabling candidate, then re-register
        // in SkillRegistry). Delegating preserves zero behavior drift.
        abEvalService.promoteCandidate(candidate);
    }

    @Override
    public void rollback(SkillEntity candidate) {
        // V2 has no single-method rollback entry point for the skill surface
        // (rollback today is a manual operator action via the dashboard's
        // "Disable" flow on the SkillEntity). Phase 1.2 / 1.3 will add a
        // canary-driven auto-rollback path once CanaryAllocator is generic.
        throw new UnsupportedOperationException(
                "SkillSurface.rollback: V2 skill rollback is a manual operator action today. "
                        + "Phase 1.3 canary auto-rollback will wire this hook.");
    }
}
