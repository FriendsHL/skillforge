package com.skillforge.server.session;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Closed command body for acknowledging an externally uncertain Tool outcome. */
public record UnknownOutcomeResolutionRequest(
        UUID resolutionRequestId,
        long expectedHistoryEpoch,
        long expectedExecutionGeneration,
        long expectedExecutionFence,
        PostAction action,
        String reason,
        List<InboxDisposition> inboxDispositions) {

    static final int MAX_REASON_CODE_POINTS = 2_000;
    static final int MAX_INBOX_DISPOSITIONS = 512;

    public UnknownOutcomeResolutionRequest {
        Objects.requireNonNull(resolutionRequestId, "resolutionRequestId");
        if (expectedHistoryEpoch < 0L) {
            throw new IllegalArgumentException("expectedHistoryEpoch must be nonnegative");
        }
        if (expectedExecutionGeneration <= 0L) {
            throw new IllegalArgumentException("expectedExecutionGeneration must be positive");
        }
        if (expectedExecutionFence < 0L) {
            throw new IllegalArgumentException("expectedExecutionFence must be nonnegative");
        }
        Objects.requireNonNull(action, "action");
        if (reason == null || reason.isBlank()
                || reason.codePointCount(0, reason.length()) > MAX_REASON_CODE_POINTS) {
            throw new IllegalArgumentException("reason must contain 1..2000 code points");
        }
        inboxDispositions = List.copyOf(Objects.requireNonNull(
                inboxDispositions, "inboxDispositions"));
        if (inboxDispositions.size() > MAX_INBOX_DISPOSITIONS) {
            throw new IllegalArgumentException("too many inboxDispositions");
        }
        Set<UUID> distinctIds = new HashSet<>();
        for (InboxDisposition disposition : inboxDispositions) {
            Objects.requireNonNull(disposition, "inboxDispositions entry");
            if (!distinctIds.add(disposition.inboxId())) {
                throw new IllegalArgumentException("duplicate inbox disposition");
            }
        }
    }

    public enum PostAction {
        CONTINUE_CURRENT_TIMELINE,
        PREPARE_RESTORE
    }

    public enum InboxDispositionKind {
        KEEP_FOR_CONTINUE,
        KEEP_FOR_RESTORE,
        DISCARD_FOR_RESTORE
    }

    public record InboxDisposition(UUID inboxId, InboxDispositionKind disposition) {
        public InboxDisposition {
            Objects.requireNonNull(inboxId, "inboxId");
            Objects.requireNonNull(disposition, "disposition");
        }

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignoredValue) {
            throw new IllegalArgumentException("Unknown inbox disposition field: " + field);
        }
    }

    /** Keeps this DTO closed even when Spring's global ObjectMapper ignores unknown fields. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException("Unknown resolution field: " + field);
    }
}
