package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.LoopDurabilityScope;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact live authority used to continue one resolved-unknown post action. */
public record ResolvedUnknownContinuationRun(
        SessionRunCoordinator.ClaimIdentity claim,
        LoopDurabilityScope scope,
        long attemptId,
        UUID stepId,
        long executionGeneration,
        Instant leaseUntil,
        DurableFrontier frontier) {

    public ResolvedUnknownContinuationRun {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(scope, "scope");
        if (attemptId <= 0L || attemptId != claim.attemptId()) {
            throw new IllegalArgumentException("attempt identity is invalid");
        }
        Objects.requireNonNull(stepId, "stepId");
        if (executionGeneration <= 0L) {
            throw new IllegalArgumentException("executionGeneration must be positive");
        }
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(frontier, "frontier");
        if (!claim.sessionId().equals(scope.sessionId())
                || !claim.loopId().equals(scope.loopId())
                || claim.loopFence() != scope.loopFence()) {
            throw new IllegalArgumentException("claim and live scope do not match");
        }
    }
}
