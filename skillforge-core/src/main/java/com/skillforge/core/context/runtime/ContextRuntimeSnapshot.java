package com.skillforge.core.context.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned, content-free runtime checkpoint for context that is loaded on
 * demand and therefore cannot be reconstructed from a compact summary alone.
 */
public record ContextRuntimeSnapshot(
        int version,
        Map<String, String> discoveredToolSchemaHashes,
        List<SkillInvocationRef> invokedSkills) {

    public static final int CURRENT_VERSION = 1;

    public ContextRuntimeSnapshot {
        version = version <= 0 ? CURRENT_VERSION : version;
        discoveredToolSchemaHashes = discoveredToolSchemaHashes == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(
                        new LinkedHashMap<>(discoveredToolSchemaHashes));
        invokedSkills = invokedSkills == null ? List.of() : List.copyOf(invokedSkills);
    }

    public static ContextRuntimeSnapshot empty() {
        return new ContextRuntimeSnapshot(CURRENT_VERSION, Map.of(), List.of());
    }
}
