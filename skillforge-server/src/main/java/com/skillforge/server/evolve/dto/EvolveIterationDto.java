package com.skillforge.server.evolve.dto;

import java.time.Instant;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D (FR-D1) — one iteration row for the
 * {@code GET /api/evolve/runs/{evolveRunId}} detail response.
 *
 * <p>Field names match the payload written by {@code RecordIterationTool} into
 * {@code step_output_json} (and therefore what the orchestrator sends via
 * {@code FlywheelRunService.appendEvolveIterationStep}):
 * <ul>
 *   <li>{@code iteration}      — 1-based index (int)</li>
 *   <li>{@code surface}        — "prompt" / "skill" / "behavior_rule"</li>
 *   <li>{@code changeDesc}     — short description of what changed</li>
 *   <li>{@code candidateId}    — the candidate evaluated this turn</li>
 *   <li>{@code baselineScore}  — nullable Double</li>
 *   <li>{@code candidateScore} — nullable Double</li>
 *   <li>{@code delta}          — nullable Double (candidateScore - baselineScore)</li>
 *   <li>{@code kept}           — whether the candidate was kept (not promoted)</li>
 *   <li>{@code abRunId}        — optional A/B run traceability id</li>
 *   <li>{@code createdAt}      — ISO-8601 instant of the step row</li>
 *   <li>{@code candidateBundle} — AUTOEVOLVE-CLOSE-LOOP P1: the bundle pointer
 *       tuple sidecar ({@code promptVersionId / behaviorRuleVersionId /
 *       skillDraftId}), {@code null} when the step recorded no bundle (e.g.
 *       single-surface iterations or pre-P1 ledger rows)</li>
 *   <li>{@code prediction} — BC-M2b (G3): the falsifiable prediction staked this
 *       iteration, {@code null} for pre-G3 ledger rows</li>
 *   <li>{@code reconciliation} — BC-M2b (G3): the deterministic prediction-vs-actual
 *       reconciliation, {@code null} for pre-G3 ledger rows</li>
 * </ul>
 */
public record EvolveIterationDto(
        int iteration,
        String surface,
        String changeDesc,
        String candidateId,
        Double baselineScore,
        Double candidateScore,
        Double delta,
        boolean kept,
        String abRunId,
        Instant createdAt,
        CandidateBundle candidateBundle,
        PredictionDto prediction,
        ReconciliationDto reconciliation
) {}
