package com.skillforge.server.canary;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.3: domain exception for state-machine
 * violations on the canary lifecycle.
 *
 * <p>Thrown when an operation is not legal for the canary's current
 * {@code rollout_stage} — e.g. attempting to {@code stepUp} a rolled-back
 * canary, or {@code startCanary} when an active canary already exists for
 * the same (agent, surface) pair.
 *
 * <p>Maps to HTTP {@code 409 Conflict} at the controller layer (separate from
 * the standard {@link IllegalArgumentException} → {@code 400 Bad Request} for
 * invalid input).
 *
 * <p>Per {@code java.md} error handling: unchecked, semantic name, message
 * includes context (canary id + current stage) so logs and dashboard alerts
 * are immediately actionable.
 */
public class CanaryStateException extends RuntimeException {
    public CanaryStateException(String message) {
        super(message);
    }
}
