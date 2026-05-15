package com.skillforge.server.improve;

import com.skillforge.server.improve.surface.SandboxContext;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.2 — surface-specific eval-set runner.
 *
 * <p>Injected into {@link AbstractAbEvalRunner} so its public {@code run()}
 * template can invoke {@code evalService.run(ctx, version)} for both
 * baseline and candidate sides <b>without making {@code runEvalSet} an
 * abstract hook</b> (which would violate ratify #3's locked 4-hook count of
 * {@code injectForSandbox / judgeAndCompare / shouldPromote / promoteIfNeeded}).
 *
 * <p>Concrete impls discriminate baseline vs candidate via subclass-specific
 * state (typically a {@code ThreadLocal} set by the orchestrator before
 * calling {@link AbstractAbEvalRunner#run}) — see {@code SkillEvalService}
 * and {@code PromptEvalService} for the two concrete adapters that wrap the
 * existing V2 per-scenario sandbox + V3 monolithic {@code AbEvalPipeline.run}
 * paths respectively.
 *
 * @param <V> surface-specific version entity type
 */
@FunctionalInterface
public interface EvalService<V> {

    /**
     * Run the eval set for one side of an A/B (baseline OR candidate). Return
     * the resulting {@link AbstractAbEvalRunner.EvalRun} with pass-rate +
     * scenario count.
     */
    AbstractAbEvalRunner.EvalRun run(SandboxContext ctx, V version);
}
