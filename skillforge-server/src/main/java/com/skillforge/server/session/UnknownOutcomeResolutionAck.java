package com.skillforge.server.session;

import com.skillforge.server.session.UnknownOutcomeResolutionRequest.InboxDisposition;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.PostAction;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Stable acknowledgement reconstructed from the append-only resolution audit on retry. */
public record UnknownOutcomeResolutionAck(
        UUID resolutionRequestId,
        String sessionId,
        long attemptId,
        UUID stepId,
        long historyEpoch,
        long executionGeneration,
        long executionFence,
        ActorAuthority actorAuthority,
        PostAction action,
        UUID resultBatchId,
        String outcomeState,
        List<InboxDisposition> inboxDispositions,
        String postActionState,
        boolean restorePreparing,
        long auditId,
        Instant resolvedAt) {

    public UnknownOutcomeResolutionAck {
        Objects.requireNonNull(resolutionRequestId, "resolutionRequestId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (attemptId <= 0L) throw new IllegalArgumentException("attemptId must be positive");
        Objects.requireNonNull(stepId, "stepId");
        if (historyEpoch < 0L || executionGeneration <= 0L || executionFence < 0L) {
            throw new IllegalArgumentException("invalid resolution identity");
        }
        Objects.requireNonNull(actorAuthority, "actorAuthority");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resultBatchId, "resultBatchId");
        if (!"RESOLVED_UNKNOWN".equals(outcomeState)) {
            throw new IllegalArgumentException("outcomeState must be RESOLVED_UNKNOWN");
        }
        inboxDispositions = List.copyOf(Objects.requireNonNull(
                inboxDispositions, "inboxDispositions"));
        String expectedPostActionState = action == PostAction.CONTINUE_CURRENT_TIMELINE
                ? "PENDING" : "NONE";
        if (!expectedPostActionState.equals(postActionState)
                || restorePreparing != (action == PostAction.PREPARE_RESTORE)) {
            throw new IllegalArgumentException("invalid post-resolution action state");
        }
        if (auditId <= 0L) throw new IllegalArgumentException("auditId must be positive");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
    }

    public enum ActorAuthority {
        OWNER,
        ADMIN
    }
}
