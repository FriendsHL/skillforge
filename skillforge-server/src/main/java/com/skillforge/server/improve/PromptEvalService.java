package com.skillforge.server.improve;

import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.improve.surface.SandboxContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.2 — {@link EvalService} adapter for the
 * prompt surface. Thin adapter over {@link PromptImproverService} —
 * delegates {@link #run} back to {@code PromptImproverService.runEvalSetInternal}.
 *
 * <p>Mirrors {@code SkillEvalService} — same @Lazy back-reference rationale.
 */
@Component
public class PromptEvalService implements EvalService<PromptVersionEntity> {

    private final PromptImproverService improverService;

    public PromptEvalService(@Lazy PromptImproverService improverService) {
        this.improverService = improverService;
    }

    @Override
    public AbstractAbEvalRunner.EvalRun run(SandboxContext ctx, PromptVersionEntity version) {
        return improverService.runEvalSetInternal(ctx, version);
    }
}
