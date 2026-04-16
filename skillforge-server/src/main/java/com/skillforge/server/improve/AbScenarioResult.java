package com.skillforge.server.improve;

/**
 * Per-scenario comparison result for an A/B eval run.
 * Uses nested RunResult to match frontend's expected structure:
 *   { baseline: { status, oracleScore }, candidate: { status, oracleScore } }
 */
public record AbScenarioResult(
        String scenarioId,
        String scenarioName,
        RunResult baseline,
        RunResult candidate
) {
    public record RunResult(String status, double oracleScore) {}
}
