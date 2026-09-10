package com.skillforge.core.engine.durability;

import java.util.Collection;
import java.util.Objects;

/** Server-authoritative closed replay classification for a durable Tool attempt. */
public enum ReplaySafety {
    READ_ONLY_REPLAYABLE(0),
    IDEMPOTENT_KEYED(1),
    MUTATING(2),
    UNKNOWN(3);

    private final int riskRank;

    ReplaySafety(int riskRank) {
        this.riskRank = riskRank;
    }

    public static ReplaySafety aggregate(Collection<ReplaySafety> values) {
        Objects.requireNonNull(values, "values");
        ReplaySafety result = READ_ONLY_REPLAYABLE;
        for (ReplaySafety value : values) {
            Objects.requireNonNull(value, "replay safety");
            if (value.riskRank > result.riskRank) result = value;
        }
        return result;
    }
}
