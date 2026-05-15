package com.skillforge.server.improve;

import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.improve.surface.SandboxContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.2 — {@link EvalService} adapter for the
 * skill surface. Thin adapter over {@link SkillAbEvalService} —
 * delegates {@link #run} back to {@code SkillAbEvalService.runEvalSetInternal}.
 *
 * <p>@Lazy back-reference: {@link SkillAbEvalService}
 * extends {@link AbstractAbEvalRunner}{@code <SkillEntity>} and takes this
 * adapter via super() → mutual dependency.
 */
@Component
public class SkillEvalService implements EvalService<SkillEntity> {

    private final SkillAbEvalService abEvalService;

    public SkillEvalService(@Lazy SkillAbEvalService abEvalService) {
        this.abEvalService = abEvalService;
    }

    @Override
    public AbstractAbEvalRunner.EvalRun run(SandboxContext ctx, SkillEntity version) {
        return abEvalService.runEvalSetInternal(ctx, version);
    }
}
