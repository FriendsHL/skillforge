package com.skillforge.core.context;

import com.skillforge.core.llm.cache.SystemPromptParts;

import java.util.List;

/**
 * Existing rendered prompt parts plus read-only observation metadata.
 */
public record ObservedSystemPromptParts(
        SystemPromptParts parts,
        List<PromptFragmentObservation> fragments,
        String stableHash,
        String assemblyHash) {

    public ObservedSystemPromptParts {
        fragments = fragments == null ? List.of() : List.copyOf(fragments);
        stableHash = stableHash == null ? "" : stableHash;
        assemblyHash = assemblyHash == null ? "" : assemblyHash;
    }
}
