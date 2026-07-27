package com.skillforge.core.context;

import java.util.List;

/**
 * Content-free metadata about one logical prompt fragment.
 *
 * <p>The observation deliberately stores only a hash and estimated size. Prompt text,
 * memories, secrets, and provider output must never be copied into traces through this
 * model.
 */
public record PromptFragmentObservation(
        String id,
        PromptSourceType sourceType,
        PromptPlacement placement,
        boolean stable,
        boolean cacheable,
        int estimatedTokens,
        List<String> sourceIds,
        String contentHash) {

    public PromptFragmentObservation {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        contentHash = contentHash == null ? "" : contentHash;
    }
}
