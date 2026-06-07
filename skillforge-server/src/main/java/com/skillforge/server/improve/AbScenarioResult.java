package com.skillforge.server.improve;

/**
 * Per-scenario comparison result for an A/B eval run.
 * Uses nested RunResult to match frontend's expected structure:
 *   { baseline: { status, oracleScore, rationale }, candidate: { status, oracleScore, rationale } }
 *
 * <p><b>{@code subset}</b> (workflow-fix F3, 2026-06-07): the agent-surface A/B
 * tags each persisted scenario with the subset it was scored under —
 * {@link #SUBSET_TARGET} (harvested bad-case / role-target subset) or
 * {@link #SUBSET_GENERAL} (benchmark / regression subset) — so read paths
 * ({@code GetAbResultTool}'s {@code targetMeasuredN}/{@code generalMeasuredN}) can
 * report per-subset measured counts without re-deriving the split (the explicit
 * target ids are never persisted on the run row). Nullable and additive:
 * prompt/skill/behavior_rule writers and all legacy rows leave it null; consumers
 * must treat null as "subset unknown".
 */
public record AbScenarioResult(
        String scenarioId,
        String scenarioName,
        RunResult baseline,
        RunResult candidate,
        String subset
) {
    /** Subset tag: scenario was part of the explicit/role TARGET subset. */
    public static final String SUBSET_TARGET = "target";
    /** Subset tag: scenario was part of the GENERAL (benchmark/regression) subset. */
    public static final String SUBSET_GENERAL = "general";

    /** Backward-compatible 4-arg form — subset unknown / not tagged. */
    public AbScenarioResult(String scenarioId, String scenarioName,
                            RunResult baseline, RunResult candidate) {
        this(scenarioId, scenarioName, baseline, candidate, null);
    }

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL reflection: {@code rationale} carries the judge's
     * per-scenario reasoning (why this side passed/failed) so the evolve-editor can
     * see WHY each case improved/regressed when deciding the next change — not just
     * the score. Nullable: the CACHED-baseline sentinel and legacy rows leave it null.
     */
    public record RunResult(String status, double oracleScore, String rationale) {
        /** Backward-compatible 2-arg form — rationale unknown / not captured. */
        public RunResult(String status, double oracleScore) {
            this(status, oracleScore, null);
        }
    }
}
