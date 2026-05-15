package com.skillforge.server.improve;

import com.skillforge.server.improve.surface.OptimizableSurface;
import com.skillforge.server.improve.surface.SandboxContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MULTI-SURFACE-FLYWHEEL V4 — Template Method for A/B eval runners across the
 * three surfaces (skill / prompt / behavior_rule).
 *
 * <p><b>Phase 1.2</b> (this revision) fills the previously-stubbed
 * {@link #run(String, Object, Object, SandboxContext)} body with the real
 * 5-step orchestration per tech-design.md §3.1 (ratify #3 — hook ORDER is
 * locked, dev cannot reorder):
 * <ol>
 *   <li>{@code surface.injectForSandbox(ctx, baseline)} +
 *       {@link #runEvalSet runEvalSet(ctx, baseline)} — produces baseline {@link EvalRun}</li>
 *   <li>{@code surface.injectForSandbox(ctx, candidate)} +
 *       {@link #runEvalSet runEvalSet(ctx, candidate)} — produces candidate {@link EvalRun}</li>
 *   <li>{@link #judgeAndCompare} — surface-specific {@link Comparison} (skill: pass-rate delta,
 *       prompt: pass-rate delta, behavior_rule TBD: violation reduction)</li>
 *   <li>{@link #shouldPromote} — surface-specific gate (skill V2: delta ≥ 15pp AND candidate ≥ 40pp;
 *       prompt V3: always true, gate lives inside {@code PromptPromotionService}; behavior_rule TBD)</li>
 *   <li>If gate passes → {@link #promoteIfNeeded} — must promote candidate
 *       (typically via {@code surface.promote()} or a service-owned promote
 *       method that preserves invariants like the V64 partial-UNIQUE on
 *       enabled skills)</li>
 * </ol>
 *
 * <p>Persistence + broadcast of the surrounding {@code SkillAbRunEntity} /
 * {@code PromptAbRunEntity} happens in the subclass's <i>orchestrator</i>
 * method ({@code runAbTestAsync} / {@code runImprovementAsync}) — see brief §4
 * "写 abRun.status 公共". The template itself returns an {@link AbRunResult}
 * and is intentionally side-effect-free at the AB-run row level so subclasses
 * can decide how to map the result onto their own entity shape.
 *
 * <p>The placeholder transport records ({@link EvalRun}, {@link Comparison},
 * {@link AbRunResult}) intentionally stay minimal — Phase 1.3+ may pull richer
 * types from {@code AbEvalPipeline} once we have lifecycle_hook surface and
 * can see common fields across 4 implementations.
 *
 * @param <V> surface-specific version entity type (SkillEntity / PromptVersionEntity /
 *            BehaviorRuleVersionEntity)
 */
public abstract class AbstractAbEvalRunner<V> {

    protected static final Logger log = LoggerFactory.getLogger(AbstractAbEvalRunner.class);

    protected final OptimizableSurface<V> surface;
    protected final EvalService<V> evalService;

    protected AbstractAbEvalRunner(OptimizableSurface<V> surface, EvalService<V> evalService) {
        if (surface == null) {
            throw new IllegalArgumentException("surface must not be null");
        }
        if (evalService == null) {
            throw new IllegalArgumentException("evalService must not be null");
        }
        this.surface = surface;
        this.evalService = evalService;
    }

    /**
     * Template method — final, subclasses customize via the 5 hooks below.
     *
     * <p>Execution order is LOCKED by tech-design ratify #3 (2026-05-15):
     * {@code injectForSandbox(baseline) → runEvalSet(baseline) →
     * injectForSandbox(candidate) → runEvalSet(candidate) → judgeAndCompare →
     * shouldPromote → promoteIfNeeded}. Subclasses CANNOT reorder these.
     *
     * <p>The two {@code injectForSandbox} calls are sequential rather than
     * parallel because skill/prompt/behavior_rule surfaces today share a
     * single agent slot per session (5 ratify #4 "1 active canary per
     * agent" invariant). Once V5+ user simulator allows true multi-arm
     * trials, this template will be revisited.
     *
     * <p>This template is intentionally side-effect-free for the AB-run row —
     * subclass {@code promoteIfNeeded} may call {@code surface.promote()} or
     * an internal promote helper, but flag-setting (abRun.setPromoted /
     * abRun.setCompletedAt etc.) is the subclass orchestrator's job. See
     * subclass for details.
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

        // Step 1: inject + eval baseline.
        surface.injectForSandbox(ctx, baseline);
        EvalRun baselineRun = runEvalSet(ctx, baseline);

        // Step 2: inject + eval candidate.
        surface.injectForSandbox(ctx, candidate);
        EvalRun candidateRun = runEvalSet(ctx, candidate);

        // Step 3: compare.
        Comparison comparison = judgeAndCompare(baselineRun, candidateRun);

        // Step 4: gate.
        boolean shouldPromote = shouldPromote(comparison);

        // Step 5: promote (only when gate passes).
        if (shouldPromote) {
            promoteIfNeeded(candidate, comparison);
        }

        return new AbRunResult(abRunId, baselineRun, candidateRun, comparison, shouldPromote);
    }

    /**
     * Protected non-abstract helper — delegates to the injected
     * {@link EvalService}. Subclasses MAY override (e.g. for tests) but are
     * NOT required to: providing a surface-specific {@code EvalService<V>}
     * bean is the normal extension point.
     *
     * <p><b>Not an abstract hook</b> — Phase 1.2 reviewer-r1 fix: ratify #3
     * locks the 4-hook count (injectForSandbox via {@link OptimizableSurface}
     * + judgeAndCompare + shouldPromote + promoteIfNeeded). Earlier draft made
     * this method abstract and was flagged for violating the lock; the fix
     * uses {@link EvalService} dependency injection to accomplish the same
     * surface-specific eval customization without growing the abstract method
     * count.
     */
    protected EvalRun runEvalSet(SandboxContext ctx, V version) {
        return evalService.run(ctx, version);
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
     * delta ≥ 15pp AND candidate ≥ 40pp; prompt V3 returns {@code true}
     * unconditionally because the real gate logic lives inside
     * {@code PromptPromotionService.evaluateAndPromote} (delta + 24h cooldown +
     * decline tracking + paused flag); behavior_rule TBD).
     */
    protected abstract boolean shouldPromote(Comparison comparison);

    /**
     * Hook 4 — when {@link #shouldPromote} returned true, actually promote
     * the candidate. Implementations either call {@link OptimizableSurface#promote}
     * on the injected surface (the "pure" route) or invoke a service-owned
     * promote method that owns invariant-preserving ordering (V2
     * {@code SkillAbEvalService.promoteCandidate} owns the V64 partial-UNIQUE
     * disable-parent-then-flush sequence; V3
     * {@code PromptPromotionService.evaluateAndPromote} owns the agent-scoped
     * pessimistic write lock + decline tracking).
     */
    protected abstract void promoteIfNeeded(V candidate, Comparison comparison);

    // ─────── Phase 1.1 placeholder transport records ───────
    //
    // Kept intentionally minimal — Phase 1.3+ may replace with richer types
    // lifted from AbEvalPipeline / SkillAbEvalService.AbScenarioResult once a
    // 4th surface (lifecycle_hook in V5+) shows which fields are common.
    // Public so subclasses (and their tests) can reference them; the package
    // boundary keeps them server-internal.

    public record EvalRun(String evalRunId, double passRate, int scenariosRun) {}

    public record Comparison(double baselinePassRate, double candidatePassRate, double delta) {}

    public record AbRunResult(String abRunId, EvalRun baselineRun, EvalRun candidateRun,
                              Comparison comparison, boolean promoted) {}
}
