package com.skillforge.core.engine.durability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Immutable ACK carrier for one exact t_session_message occurrence. */
public record PersistedMessageOccurrence(
        long messageId,
        long seqNo,
        String writeBatchId,
        int writeBatchOrdinal,
        MessageSnapshot message,
        String msgType,
        String messageType,
        String controlId,
        Instant answeredAt,
        Map<String, Object> metadata,
        String traceId) {

    public PersistedMessageOccurrence {
        if (messageId <= 0) throw new IllegalArgumentException("messageId must be positive");
        if (seqNo < 0) throw new IllegalArgumentException("seqNo must be nonnegative");
        if (writeBatchId == null || writeBatchId.isBlank()) {
            throw new IllegalArgumentException("writeBatchId must not be blank");
        }
        if (writeBatchOrdinal < 0) {
            throw new IllegalArgumentException("writeBatchOrdinal must be nonnegative");
        }
        Objects.requireNonNull(message, "message");
        if (msgType == null || msgType.isBlank()) throw new IllegalArgumentException("msgType must not be blank");
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("messageType must not be blank");
        }
        metadata = FrozenJson.immutableObject(metadata);
    }
}
