package com.skillforge.server.improve;

/**
 * Per-scenario comparison result for an A/B eval run.
 * Uses nested RunResult to match frontend's expected structure:
 *   { baseline: { status, oracleScore, rationale }, candidate: { status, oracleScore, rationale } }
 */
public record AbScenarioResult(
        String scenarioId,
        String scenarioName,
        RunResult baseline,
        RunResult candidate
) {
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
