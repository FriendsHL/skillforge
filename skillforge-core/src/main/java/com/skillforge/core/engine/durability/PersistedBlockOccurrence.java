package com.skillforge.core.engine.durability;

import java.util.Objects;
import java.util.UUID;

/** Exact persisted scalar identity for one Tool result block owned by a committed result batch. */
public record PersistedBlockOccurrence(
        long messageId,
        long seqNo,
        String sessionId,
        UUID resultBatchId,
        int resultBatchOrdinal,
        int blockIndex,
        String toolUseId,
        String content,
        boolean error,
        String errorType,
        String traceId) {

    public PersistedBlockOccurrence {
        if (messageId <= 0L) throw new IllegalArgumentException("messageId must be positive");
        if (seqNo < 0L) throw new IllegalArgumentException("seqNo must be nonnegative");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(resultBatchId, "resultBatchId");
        if (resultBatchOrdinal < 0) {
            throw new IllegalArgumentException("resultBatchOrdinal must be nonnegative");
        }
        if (blockIndex < 0) throw new IllegalArgumentException("blockIndex must be nonnegative");
        if (toolUseId == null || toolUseId.isBlank()) {
            throw new IllegalArgumentException("toolUseId must not be blank");
        }
        Objects.requireNonNull(content, "content");
        if (errorType != null && errorType.isBlank()) {
            throw new IllegalArgumentException("errorType must be null or nonblank");
        }
    }
}
