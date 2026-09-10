package com.skillforge.core.engine.durability;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Server-authoritative execution lease returned before Tool dispatch becomes visible. */
public record ExecutionClaimAck(
        long attemptId,
        UUID stepId,
        UUID claimRequestId,
        DurableToolAttemptState state,
        LoopDurabilityScope executionScope,
        long executionGeneration,
        Instant claimedAt,
        Instant executionLeaseUntil) {

    public ExecutionClaimAck {
        if (attemptId <= 0L) throw new IllegalArgumentException("attemptId must be positive");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(claimRequestId, "claimRequestId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(executionScope, "executionScope");
        if (executionGeneration <= 0L) {
            throw new IllegalArgumentException("executionGeneration must be positive");
        }
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(executionLeaseUntil, "executionLeaseUntil");
    }
}
