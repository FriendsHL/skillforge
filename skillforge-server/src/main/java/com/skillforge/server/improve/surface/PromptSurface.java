package com.skillforge.server.improve.surface;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.springframework.stereotype.Component;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — Prompt surface adapter.
 *
 * <p>Pure adapter over V3 {@link PromptImproverService} /
 * {@link PromptVersionRepository}. Same Phase 1.1 contract as
 * {@link SkillSurface}: validates the interface shape, lives in the
 * {@link SurfaceRegistry}, defers full sandbox / canary wiring to Phase 1.2 /
 * 1.3.
 *
 * <p>Methods without a 1:1 V3 service entry point throw
 * {@link UnsupportedOperationException} — see per-method javadoc.
 */
@Component
public class PromptSurface implements OptimizableSurface<PromptVersionEntity> {

    public static final String SURFACE_TYPE = "prompt";

    private final PromptVersionRepository versionRepository;
    private final AgentRepository agentRepository;
    private final PromptImproverService improverService;

    public PromptSurface(PromptVersionRepository versionRepository,
                         AgentRepository agentRepository,
                         PromptImproverService improverService) {
        this.versionRepository = versionRepository;
        this.agentRepository = agentRepository;
        this.improverService = improverService;
    }

    @Override
    public String surfaceType() {
        return SURFACE_TYPE;
    }

    @Override
    public PromptVersionEntity loadActive(Long agentId) {
        if (agentId == null) return null;
        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null || agent.getActivePromptVersionId() == null) return null;
        return versionRepository.findById(agent.getActivePromptVersionId()).orElse(null);
    }

    @Override
    public PromptVersionEntity loadVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) return null;
        return versionRepository.findById(versionId).orElse(null);
    }

    @Override
    public PromptVersionEntity createCandidate(PromptVersionEntity baseline,
                                                String improvementContext) {
        // V3 PromptImproverService.startImprovementFromAttribution is the
        // existing entry point for attribution-driven candidate creation. The
        // signatures don't line up cleanly with OptimizableSurface.createCandidate
        // (V3 needs eventId / agentId / attributedDescription / ownerId; we
        // only have a baseline + free-form improvementContext here). Mapping
        // this method to startImprovementFromAttribution would require
        // synthesizing eventId / ownerId values, which is wrong.
        //
        // Phase 1.2 will adjust either the V3 service signature or the V4
        // interface to bridge cleanly. Today: throw with a clear pointer.
        throw new UnsupportedOperationException(
                "PromptSurface.createCandidate: V3 PromptImproverService.startImprovementFromAttribution "
                        + "requires eventId + ownerId audit fields beyond the generic "
                        + "OptimizableSurface.createCandidate signature. Call PromptImproverService directly "
                        + "(or wait for Phase 1.2's AbstractAbEvalRunner integration).");
    }

    @Override
    public void injectForSandbox(SandboxContext ctx, PromptVersionEntity version) {
        // V3 sandbox injection happens inside AbEvalPipeline.run by building
        // an AgentDefinition with the candidate prompt content. Phase 1.2
        // will lift that into this hook.
        throw new UnsupportedOperationException(
                "PromptSurface.injectForSandbox: Phase 1.2 (AbstractAbEvalRunner.prompt subclass) "
                        + "will pull sandbox plumbing from AbEvalPipeline.run into this hook.");
    }

    @Override
    public void promote(PromptVersionEntity candidate) {
        // V3 PromptPromotionService.evaluateAndPromote takes (abRunId, agentId),
        // not a bare candidate — it owns gate logic (delta threshold, 24h
        // cooldown, decline tracking) that's tightly coupled to having an
        // ab_run row. Adapting that to "promote a bare candidate" would
        // either bypass the gates (wrong) or require recovering the abRunId
        // from the candidate (extra repo lookup with unclear semantics).
        //
        // Phase 1.2 will route AbstractAbEvalRunner.promoteIfNeeded through
        // the existing evaluateAndPromote path with the abRunId in hand.
        throw new UnsupportedOperationException(
                "PromptSurface.promote: V3 PromptPromotionService.evaluateAndPromote needs an "
                        + "abRunId to enforce gates (delta / cooldown / decline). Call "
                        + "PromptPromotionService.evaluateAndPromote(abRunId, agentId) directly.");
    }

    @Override
    public void rollback(PromptVersionEntity candidate) {
        // V3 has PromptPromotionService.rollbackToVersion(agent, target) —
        // takes a different shape. Phase 1.2 / 1.3 will bridge.
        throw new UnsupportedOperationException(
                "PromptSurface.rollback: V3 PromptPromotionService.rollbackToVersion takes "
                        + "(agent, target) — different shape. Phase 1.2 will bridge.");
    }
}
