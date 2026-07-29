package com.skillforge.core.engine;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
public record MemoryInjection(
        String text,
        Set<Long> injectedIds,
        List<MemoryInjectionRef> provenance) {

    public MemoryInjection(String text, Set<Long> injectedIds) {
        this(text, injectedIds, List.of());
    }

    public MemoryInjection {
        injectedIds = injectedIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(injectedIds));
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
    }

    public static MemoryInjection empty() {
        return new MemoryInjection("", Collections.emptySet(), List.of());
    }
}
