package com.skillforge.core.engine;

import java.util.Collections;
import java.util.Set;

/**
 * Memory injection result for AgentLoopEngine system prompt assembly.
 * <p>
 * {@code text}: rendered memory markdown to append; null/blank → no injection.
 * <p>
 * {@code injectedIds}: memory ids that were actually rendered. Non-null but possibly
 * empty. Forwarded into {@link LoopContext#setInjectedMemoryIds(Set)} so downstream
 * tools (e.g. memory_search) can avoid double-injecting the same items.
 */
public record MemoryInjection(String text, Set<Long> injectedIds) {

    public static MemoryInjection empty() {
        return new MemoryInjection("", Collections.emptySet());
    }
}
