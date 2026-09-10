package com.skillforge.core.engine.durability;

import java.util.List;
import java.util.Objects;

/** Immutable acknowledgement for one bounded ordered-inbox drain transaction. */
public record QueuedUserDrainAck(
        LoopDurabilityScope scope,
        DurableFrontier preDrainFrontier,
        List<QueuedUserDrainItem> items,
        DurableFrontier postDrainFrontier,
        long remainingCount) {

    public QueuedUserDrainAck {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(preDrainFrontier, "preDrainFrontier");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        Objects.requireNonNull(postDrainFrontier, "postDrainFrontier");
        if (remainingCount < 0L) {
            throw new IllegalArgumentException("remainingCount must be nonnegative");
        }
        long previousSeq = -1L;
        for (QueuedUserDrainItem item : items) {
            if (item.message().seqNo() <= previousSeq) {
                throw new IllegalArgumentException(
                        "drained USER items must retain database order");
            }
            previousSeq = item.message().seqNo();
        }
    }
}
