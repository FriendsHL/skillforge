package com.skillforge.server.improve;

import java.util.List;
import java.util.Objects;

/**
 * EVAL-DATASET-LAYER V1 (★ r4 D3 fix ★): parameter object for
 * {@link PromptImproverService#runAbTestAgainst(AbEvalRunRequest)}.
 *
 * <p>The legacy 5-arg method takes too many positional Strings — easy to
 * swap baseline and candidate, easy to forget the scenarios list is
 * orthogonal to datasetVersionId. The record locks in:
 * <ul>
 *   <li>required-vs-optional via {@link Objects#requireNonNull}</li>
 *   <li>{@link #evalScenarioIds} ⊥ {@link #datasetVersionId} — supplying
 *       both is ambiguous, fail fast</li>
 *   <li>both empty/null → caller wants the ephemeral fallback path
 *       (held_out scenarios for the agent, or attribution-pattern-derived
 *       ephemerals)</li>
 * </ul>
 *
 * <p>Migration: the existing 5-arg method
 * {@code runAbTestAgainst(String, String, String, List)} is marked
 * {@link Deprecated} and delegates to this. Callers should migrate to the
 * record API before V2 removes the legacy form.
 */
public record AbEvalRunRequest(
        String agentId,
        String baselineVersionId,
        String candidateVersionId,
        List<String> evalScenarioIds,
        String datasetVersionId,
        Double cachedBaselineRate) {

    public AbEvalRunRequest {
        Objects.requireNonNull(agentId, "agentId required");
        if (agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        Objects.requireNonNull(candidateVersionId, "candidateVersionId required");
        if (candidateVersionId.isBlank()) {
            throw new IllegalArgumentException("candidateVersionId must not be blank");
        }
        boolean hasScenarios = evalScenarioIds != null && !evalScenarioIds.isEmpty();
        boolean hasDatasetVersion = datasetVersionId != null && !datasetVersionId.isBlank();
        if (hasScenarios && hasDatasetVersion) {
            throw new IllegalArgumentException(
                    "evalScenarioIds and datasetVersionId are mutually exclusive — supply "
                            + "either an explicit scenario list or a dataset_version reference, "
                            + "not both (the dataset version implicitly provides scenarios).");
        }
        // BUG-1: cachedBaselineRate is a pass-rate percentage and feeds directly into
        // delta = candidatePassRate - cachedBaselineRate. An out-of-range value (e.g.
        // an LLM-hallucinated 150) would silently make every candidate "lose", quietly
        // stalling the hill-climb — fail fast on direct construction instead.
        if (cachedBaselineRate != null
                && (cachedBaselineRate < 0.0 || cachedBaselineRate > 100.0)) {
            throw new IllegalArgumentException(
                    "cachedBaselineRate must be in [0, 100] when non-null, got: " + cachedBaselineRate);
        }
    }

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL BUG-1 (winner-carry-forward): backward-compatible
     * constructor — {@code cachedBaselineRate=null} means "measure the baseline
     * fresh" (legacy behaviour). Supply a non-null rate (0..100) via the 6-arg
     * form to make the A/B run CANDIDATE-ONLY and reuse that score as the baseline
     * pass-rate — used when the evolve orchestrator already knows the current-best's
     * score and must not re-measure it (avoids re-eval noise + halves the work).
     */
    public AbEvalRunRequest(String agentId,
                            String baselineVersionId,
                            String candidateVersionId,
                            List<String> evalScenarioIds,
                            String datasetVersionId) {
        this(agentId, baselineVersionId, candidateVersionId, evalScenarioIds, datasetVersionId, null);
    }
}
