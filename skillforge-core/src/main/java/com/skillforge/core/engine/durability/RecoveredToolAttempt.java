package com.skillforge.core.engine.durability;

import java.util.Objects;

/** Persisted intent plus the new winning execution claim used for one restart replay. */
public record RecoveredToolAttempt(
        IntentCommitAck intent,
        ExecutionClaimAck execution) {

    public RecoveredToolAttempt {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(execution, "execution");
        if (intent.attemptId() != execution.attemptId()
                || !intent.stepId().equals(execution.stepId())
                || execution.state() != DurableToolAttemptState.EXECUTING) {
            throw new IllegalArgumentException("recovered Tool attempt identity is inconsistent");
        }
    }
}
