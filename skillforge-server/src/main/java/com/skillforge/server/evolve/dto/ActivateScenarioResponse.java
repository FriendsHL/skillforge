package com.skillforge.server.evolve.dto;

import java.time.Instant;

/**
 * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b — bare response of the activate endpoint
 * ({@code POST /api/evolve/scenarios/{scenarioId}/activate}). NOT enveloped
 * (mirrors the adopt / run-detail responses).
 *
 * <p>FE-BE contract (java.md footgun #6): field names/types map one-for-one to
 * the FE {@code ActivateScenarioResponse} interface. {@code agentId} is a String
 * because {@link com.skillforge.server.entity.EvalScenarioEntity#getAgentId()} is.
 *
 * @param datasetVersionId      the newly published dataset version that now
 *                              contains the activated scenario
 * @param datasetVersionNumber  that version's monotonically increasing number
 * @param datasetScenarioCount  number of scenarios in the new version (benchmark
 *                              members ∪ the activated case)
 */
public record ActivateScenarioResponse(
        String scenarioId,
        String status,
        String agentId,
        String datasetVersionId,
        int datasetVersionNumber,
        int datasetScenarioCount,
        Instant reviewedAt) {
}
