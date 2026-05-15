package com.skillforge.server.improve;

import com.skillforge.server.improve.surface.OptimizableSurface;
import com.skillforge.server.improve.surface.SandboxContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — Template Method <b>skeleton</b> for A/B
 * eval runners across the three surfaces (skill / prompt / behavior_rule).
 *
 * <p><b>Phase 1.1 deliberately does NOT integrate this skeleton with the
 * existing {@code SkillAbEvalService} / {@code PromptImproverService}</b>
 * pipelines — that integration is Phase 1.2's job and lifts the public
 * {@link #run(String, Object, Object, SandboxContext)} contract from the
 * current duplicated bodies. The Phase 1.1 deliverable is the contract +
 * 4-hook shape (§3 of tech-design) so reviewers can validate the abstraction
 * before the bigger refactor lands.
 *
 * <p>Per tech-design.md §3.1 the public template orchestrates:
 * <ol>
 *   <li>{@code surface.injectForSandbox(ctx, baseline)} + {@code runEvalSet} baseline</li>
 *   <li>{@code surface.injectForSandbox(ctx, candidate)} + {@code runEvalSet} candidate</li>
 *   <li>{@link #judgeAndCompare} — surface-specific comparison shape</li>
 *   <li>{@link #shouldPromote} — surface-specific gate</li>
 *   <li>{@link #promoteIfNeeded} — when gate passes, do
 *       {@code surface.promote(candidate)}</li>
 * </ol>
 *
 * <p>The placeholder result/transport records below ({@link EvalRun},
 * {@link Comparison}, {@link AbRunResult}) are deliberately minimal — Phase
 * 1.2 will pull richer types from {@code AbEvalPipeline} +
 * {@code SkillAbEvalService.AbScenarioResult}. Keeping them inline here in
 * Phase 1.1 avoids prematurely promoting one of those internal types to a
 * shared abstraction before we know which fields belong in the common path.
 */
public abstract class AbstractAbEvalRunner<V> {

    protected static final Logger log = LoggerFactory.getLogger(AbstractAbEvalRunner.class);

    protected final OptimizableSurface<V> surface;

    protected AbstractAbEvalRunner(OptimizableSurface<V> surface) {
        if (surface == null) {
            throw new IllegalArgumentException("surface must not be null");
        }
        this.surface = surface;
    }

    /**
     * Template method — final, subclasses customize via the four hooks below.
     *
     * <p><b>Phase 1.1 stub</b>: throws {@link UnsupportedOperationException}
     * until Phase 1.2 wires the real pipeline. Subclasses can be unit-tested
     * for hook behavior without invoking this entry point.
     */
    public final AbRunResult run(String abRunId, V baseline, V candidate, SandboxContext ctx) {
        if (abRunId == null || abRunId.isBlank()) {
            throw new IllegalArgumentException("abRunId required");
        }
        if (baseline == null || candidate == null) {
            throw new IllegalArgumentException("baseline and candidate required");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("SandboxContext required");
        }
        throw new UnsupportedOperationException(
                "AbstractAbEvalRunner.run skeleton — Phase 1.2 will implement the 5-step "
                        + "Template Method. Today subclasses test their hooks directly via "
                        + "judgeAndCompare / shouldPromote / promoteIfNeeded.");
    }

    /**
     * Hook 2 — compare two completed eval runs and produce a surface-specific
     * comparison result. Skill weighs composite + pass_rate, prompt weighs
     * pass_rate delta, behavior_rule may weigh violation-count reduction.
     */
    protected abstract Comparison judgeAndCompare(EvalRun baseline, EvalRun candidate);

    /**
     * Hook 3 — decide whether the candidate meets this surface's promote
     * threshold. Each surface owns its own threshold constants (skill V2 uses
     * delta ≥ 15pp AND candidate ≥ 40pp; prompt V3 uses delta ≥ 15pp;
     * behavior_rule TBD).
     */
    protected abstract boolean shouldPromote(Comparison comparison);

    /**
     * Hook 4 — when {@link #shouldPromote} returned true, actually promote
     * the candidate. Subclass MUST call {@link OptimizableSurface#promote}
     * on its injected surface; additional surface-specific side effects
     * (publishing a WS toast, stamping abRun.promoted=true) are also done
     * here.
     */
    protected abstract void promoteIfNeeded(V candidate, Comparison comparison);

    /**
     * Helper — run a single eval set for one side of the A/B (baseline or
     * candidate). Phase 1.2 will fill this with the common
     * {@code AbEvalPipeline.run}-style loop. Phase 1.1 leaves it as a stub
     * so subclasses can be exercised in isolation.
     */
    protected EvalRun runEvalSet(SandboxContext ctx, V version) {
        throw new UnsupportedOperationException(
                "AbstractAbEvalRunner.runEvalSet skeleton — Phase 1.2 will implement.");
    }

    // ─────── Phase 1.1 placeholder transport records ───────
    //
    // Kept intentionally minimal — Phase 1.2 will replace with the real
    // types lifted from AbEvalPipeline / SkillAbEvalService.AbScenarioResult.
    // Public so subclasses (and their tests) can reference them; the package
    // boundary keeps them server-internal.

    public record EvalRun(String evalRunId, double passRate, int scenariosRun) {}

    public record Comparison(double baselinePassRate, double candidatePassRate, double delta) {}

    public record AbRunResult(String abRunId, boolean promoted, Comparison comparison) {}
}
