package com.skillforge.core.engine.durability;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable complete Tool-call vector for one assistant response. */
public record ToolCallManifest(
        List<ToolCallIntent> calls,
        ReplaySafety replaySafety) {

    public ToolCallManifest {
        calls = calls == null ? List.of() : List.copyOf(calls);
        if (calls.isEmpty()) throw new IllegalArgumentException("manifest calls must not be empty");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCallIntent call = Objects.requireNonNull(calls.get(i), "manifest call");
            if (call.providerOrdinal() != i) {
                throw new IllegalArgumentException("manifest ordinals must be contiguous from zero");
            }
            if (!ids.add(call.toolUseId())) {
                throw new IllegalArgumentException("manifest toolUseId must be unique");
            }
        }
        ReplaySafety aggregate = ReplaySafety.aggregate(
                calls.stream().map(ToolCallIntent::replaySafety).toList());
        if (replaySafety != aggregate) {
            throw new IllegalArgumentException("manifest replaySafety must equal its aggregate");
        }
    }
}
