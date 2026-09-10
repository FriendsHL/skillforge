package com.skillforge.server.session;

import java.util.Objects;
import java.util.UUID;

/**
 * Frozen single-coordinator contract for a durable post-resolution continuation.
 *
 * <p>The persistence implementation arrives in the durability batch. It must claim while
 * holding the Session/attempt locks, then release those locks before inbox, Tool, Provider,
 * or other external work. A repeated HTTP request receives the same durable {@link ClaimAck};
 * the disposition says whether that invocation created the claim, replayed the winner, or lost.
 * Local claim idempotency deliberately does not promise physical Provider HTTP exactly-once.
 */
public interface SessionRunCoordinator {

    ClaimResult claim(ClaimCommand command);

    HandoffResult acceptInboxDrain(ClaimIdentity claim);

    /** Accepts the one logical Provider-response/assistant-intent transcript continuation. */
    HandoffResult acceptTranscriptContinuation(ClaimIdentity claim);

    RecoveryResult authorizeCrashRecovery(ClaimIdentity claim);

    enum ContinuationAction {
        CONTINUE_CURRENT_TIMELINE
    }

    enum ClaimDisposition {
        ACCEPTED,
        REPLAYED,
        REJECTED
    }

    enum HandoffDisposition {
        HANDOFF_ACCEPTED,
        HANDOFF_REPLAYED,
        HANDOFF_REJECTED
    }

    enum RecoveryDisposition {
        RECOVERY_AUTHORIZED,
        RECOVERY_REJECTED
    }

    /**
     * Stable command created before the resolution caller can lose its ACK.
     * The tuple binds one resolution/result/action to one Session run claim.
     */
    record ClaimCommand(
            String sessionId,
            long attemptId,
            UUID resolutionRequestId,
            UUID resultBatchId,
            ContinuationAction action,
            UUID claimRequestId,
            String loopId,
            long loopFence) {

        public ClaimCommand {
            requireText(sessionId, "sessionId");
            if (attemptId <= 0) throw new IllegalArgumentException("attemptId must be positive");
            Objects.requireNonNull(resolutionRequestId, "resolutionRequestId");
            Objects.requireNonNull(resultBatchId, "resultBatchId");
            if (action != ContinuationAction.CONTINUE_CURRENT_TIMELINE) {
                throw new IllegalArgumentException("Only CONTINUE_CURRENT_TIMELINE may claim a run");
            }
            Objects.requireNonNull(claimRequestId, "claimRequestId");
            requireText(loopId, "loopId");
            if (loopFence < 0) throw new IllegalArgumentException("loopFence must be nonnegative");
        }
    }

    /** Immutable identity that every drain, recovery, and transcript handoff must present. */
    record ClaimIdentity(
            String sessionId,
            long attemptId,
            UUID resolutionRequestId,
            UUID resultBatchId,
            ContinuationAction action,
            UUID claimRequestId,
            String loopId,
            long loopFence) {

        public ClaimIdentity {
            // Reuse the command's closed validation so both sides cannot drift.
            new ClaimCommand(sessionId, attemptId, resolutionRequestId, resultBatchId,
                    action, claimRequestId, loopId, loopFence);
        }

        public static ClaimIdentity from(ClaimCommand command) {
            Objects.requireNonNull(command, "command");
            return new ClaimIdentity(
                    command.sessionId(), command.attemptId(), command.resolutionRequestId(),
                    command.resultBatchId(), command.action(), command.claimRequestId(),
                    command.loopId(), command.loopFence());
        }
    }

    /** Durable winner payload; equal for the accepting call and every ACK-loss replay. */
    record ClaimAck(ClaimIdentity winner) {
        public ClaimAck {
            Objects.requireNonNull(winner, "winner");
        }
    }

    record ClaimResult(ClaimDisposition disposition, ClaimAck ack) {
        public ClaimResult {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(ack, "ack");
        }
    }

    record HandoffResult(HandoffDisposition disposition) {
        public HandoffResult {
            Objects.requireNonNull(disposition, "disposition");
        }
    }

    record RecoveryResult(RecoveryDisposition disposition) {
        public RecoveryResult {
            Objects.requireNonNull(disposition, "disposition");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
