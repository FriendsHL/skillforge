package com.skillforge.core.engine.durability;

/** Immutable server-authoritative identity for one Session loop owner. */
public record LoopDurabilityScope(
        String sessionId,
        long userId,
        long historyEpoch,
        String loopId,
        long loopFence,
        String ownerInstanceId) {

    public LoopDurabilityScope {
        requireText(sessionId, "sessionId");
        if (userId < 0) throw new IllegalArgumentException("userId must be nonnegative");
        if (historyEpoch < 0) throw new IllegalArgumentException("historyEpoch must be nonnegative");
        requireText(loopId, "loopId");
        if (loopFence < 0) throw new IllegalArgumentException("loopFence must be nonnegative");
        requireText(ownerInstanceId, "ownerInstanceId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
