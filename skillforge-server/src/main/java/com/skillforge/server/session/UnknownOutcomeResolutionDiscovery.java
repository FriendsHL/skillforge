package com.skillforge.server.session;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.skillforge.server.session.UnknownOutcomeResolutionAck.ActorAuthority;

/** Authorized, immutable display data and identity for one generation-fenced resolution. */
public record UnknownOutcomeResolutionDiscovery(
        String sessionId,
        long attemptId,
        long historyEpoch,
        long executionGeneration,
        long executionFence,
        String state,
        ActorAuthority actorAuthority,
        List<ToolCall> calls,
        List<UUID> inboxIds) {

    public UnknownOutcomeResolutionDiscovery {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (attemptId <= 0L || historyEpoch < 0L
                || executionGeneration <= 0L || executionFence < 0L) {
            throw new IllegalArgumentException("invalid resolution identity");
        }
        if (!"UNCERTAIN_PENDING_RESOLUTION".equals(state)) {
            throw new IllegalArgumentException("invalid resolution state");
        }
        Objects.requireNonNull(actorAuthority, "actorAuthority");
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
        if (calls.isEmpty() || calls.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("invalid calls");
        }
        inboxIds = List.copyOf(Objects.requireNonNull(inboxIds, "inboxIds"));
        if (inboxIds.size() > UnknownOutcomeResolutionRequest.MAX_INBOX_DISPOSITIONS
                || inboxIds.stream().anyMatch(Objects::isNull)
                || inboxIds.stream().distinct().count() != inboxIds.size()) {
            throw new IllegalArgumentException("invalid inboxIds");
        }
    }

    /** Immutable, provider-ordered display projection of one verified manifest call. */
    public record ToolCall(
            int providerOrdinal,
            String toolUseId,
            String toolName,
            String input) {

        public ToolCall {
            if (providerOrdinal < 0
                    || toolUseId == null || toolUseId.isBlank()
                    || toolName == null || toolName.isBlank()
                    || input == null || input.isBlank()) {
                throw new IllegalArgumentException("invalid Tool call display projection");
            }
        }
    }
}
