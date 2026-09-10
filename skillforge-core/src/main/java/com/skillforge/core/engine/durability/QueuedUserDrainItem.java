package com.skillforge.core.engine.durability;

import java.util.Objects;
import java.util.UUID;

/** One exact conversational USER occurrence committed from the durable inbox. */
public record QueuedUserDrainItem(
        UUID inboxId,
        PersistedMessageOccurrence message) {

    public QueuedUserDrainItem {
        Objects.requireNonNull(inboxId, "inboxId");
        Objects.requireNonNull(message, "message");
        if (!inboxId.toString().equals(message.writeBatchId())
                || message.writeBatchOrdinal() != 0) {
            throw new IllegalArgumentException(
                    "drained USER must retain its inbox write identity");
        }
    }
}
