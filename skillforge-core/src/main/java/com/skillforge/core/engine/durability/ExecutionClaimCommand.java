package com.skillforge.core.engine.durability;

import java.util.Objects;
import java.util.UUID;

/** Stable request to claim one already-persisted Tool-attempt vector before any side effect. */
public record ExecutionClaimCommand(
        LoopDurabilityScope claimant,
        long attemptId,
        UUID stepId,
        UUID claimRequestId,
        DurableToolAttemptState expectedState,
        long expectedGeneration) {

    public ExecutionClaimCommand {
        Objects.requireNonNull(claimant, "claimant");
        if (attemptId <= 0L) throw new IllegalArgumentException("attemptId must be positive");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(claimRequestId, "claimRequestId");
        Objects.requireNonNull(expectedState, "expectedState");
        if (expectedGeneration < 0L) {
            throw new IllegalArgumentException("expectedGeneration must be nonnegative");
        }
    }
}
