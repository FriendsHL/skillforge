package com.skillforge.server.dto;

import com.skillforge.server.entity.SimulatorTrialEntity;

import java.time.Instant;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.3: FE-facing DTO for trial list / detail
 * endpoints. 10 fields 1:1 mirroring {@link SimulatorTrialEntity} (per
 * java.md known footgun #6 contract — FE TS interface must match field
 * names + types exactly).
 *
 * <p>Field type mapping (Java → TS):
 * <ul>
 *   <li>{@code String} → {@code string}</li>
 *   <li>{@code String} (nullable) → {@code string | null}</li>
 *   <li>{@code Integer} → {@code number}</li>
 *   <li>{@code Instant} → {@code string} (ISO-8601, ObjectMapper module serializes)</li>
 * </ul>
 */
public record SimulatorTrialResponse(
        String trialId,
        String scenarioId,
        String candidateAgentVersionId,
        String candidateSurfaceType,
        String persona,
        String sessionId,
        Integer turnsUsed,
        String terminationReason,
        String observedFailureSignals,
        Instant createdAt
) {

    public static SimulatorTrialResponse from(SimulatorTrialEntity entity) {
        return new SimulatorTrialResponse(
                entity.getTrialId(),
                entity.getScenarioId(),
                entity.getCandidateAgentVersionId(),
                entity.getCandidateSurfaceType(),
                entity.getPersona(),
                entity.getSessionId(),
                entity.getTurnsUsed(),
                entity.getTerminationReason(),
                entity.getObservedFailureSignals(),
                entity.getCreatedAt()
        );
    }
}
