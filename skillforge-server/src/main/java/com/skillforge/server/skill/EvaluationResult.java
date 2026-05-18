package com.skillforge.server.skill;

import java.time.Instant;
import java.util.List;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18): persisted shape of the
 * Jackson-serialised {@code t_skill_draft.evaluation_result_json} blob.
 *
 * <p>Aggregated by {@code SkillCreatorEvalCoordinator} after all 2N
 * SubAgent runs (N scenarios × {with_skill, without_skill}) land, then
 * written back into the originating {@code SkillDraftEntity} alongside a
 * status transition to {@code evaluated_passed} (if {@code delta.passRate}
 * meets the threshold) or {@code rejected} (otherwise).
 *
 * <p>Field naming mirrors the FE {@code SkillDraftEvaluationReport.tsx}
 * shape so the dashboard panel renders without an adapter.
 *
 * <p>Why a {@code record}: this type is read by both Jackson (write to DB
 * + read by FE API) and the dashboard listing code; immutability prevents
 * accidental mutation while a draft is being reviewed. {@code SkillMetrics}
 * is a nested record for the same reason — each "side" of the comparison
 * (with_skill / without_skill / delta) is a value tuple.
 */
public record EvaluationResult(
        SkillMetrics withSkill,
        SkillMetrics withoutSkill,
        SkillMetrics delta,
        String llmSummary,
        List<String> sourceSessionIds,
        int scenarioCount,
        Instant evaluatedAt,
        String evaluatorVersion
) {

    /**
     * Per-side metrics tuple. {@code compositeScore} / {@code overallScore}
     * come from {@code EvalJudgeTool.judgeMultiTurnConversation} (verified
     * Phase 1.0 — returns {@code EvalJudgeMultiTurnOutput} with these two
     * dimensions, NOT 5 dimensions). The remaining 3 (passRate / avgLatencyMs
     * / totalCostUsd) are aggregated by the coordinator from per-scenario
     * judge output + orchestrator wall-time + token counts.
     */
    public record SkillMetrics(
            /** Judge composite (0..1). Mean across the side's N scenarios. */
            double compositeScore,
            /** Judge overall (0..1). Mean across the side's N scenarios. */
            double overallScore,
            /** Fraction of scenarios where compositeScore ≥ 0.7 (cc convention). */
            double passRate,
            /** Mean wall-time per scenario (ms). */
            long avgLatencyMs,
            /** Sum of LLM token cost across the side's N scenarios (USD). */
            double totalCostUsd
    ) {}
}
