package com.skillforge.server.evolve.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b (G3) — a falsifiable, per-iteration
 * prediction the orchestrator stakes BEFORE running an A/B for an issue.
 *
 * <p>It names the PROBLEM and references real scenario ids — it never describes a
 * remedy (blind-test safe): {@code targetProblem} is a one-line description of the
 * issue being attacked, {@code flipToPass}/{@code riskToFail} are scenario ids the
 * candidate is predicted to flip to PASS / risk regressing to FAIL.
 *
 * <p>FE-BE contract (java.md footgun #6): field names/types map one-for-one to the
 * FE {@code prediction} interface. Nullable {@code issueId}/{@code rationale}.
 * {@code @JsonIgnoreProperties(ignoreUnknown=true)} keeps forward-compat when the
 * orchestrator stores extra keys.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PredictionDto(
        String issueId,
        String targetProblem,
        List<String> flipToPass,
        List<String> riskToFail,
        String rationale) {
}
