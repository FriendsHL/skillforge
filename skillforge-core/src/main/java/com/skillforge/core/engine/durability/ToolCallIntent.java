package com.skillforge.core.engine.durability;

import java.util.Objects;

/** One immutable provider-ordered Tool call in an assistant intent. */
public record ToolCallIntent(
        int providerOrdinal,
        String toolUseId,
        String toolName,
        FrozenJson input,
        ReplaySafety replaySafety) {

    public ToolCallIntent {
        if (providerOrdinal < 0) throw new IllegalArgumentException("providerOrdinal must be nonnegative");
        requireText(toolUseId, "toolUseId");
        requireText(toolName, "toolName");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(replaySafety, "replaySafety");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
