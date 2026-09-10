package com.skillforge.core.engine.durability;

/** Immutable maximum durable row identity immediately before an intent. */
public record DurableFrontier(long maxMessageId, long maxSeq) {

    public static final DurableFrontier EMPTY = new DurableFrontier(-1L, -1L);

    public DurableFrontier {
        boolean empty = maxMessageId == -1L && maxSeq == -1L;
        boolean present = maxMessageId > 0L && maxSeq >= 0L;
        if (!empty && !present) {
            throw new IllegalArgumentException("frontier must be empty or contain a positive message id and nonnegative seq");
        }
    }
}
