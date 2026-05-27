package com.skillforge.server.memory.context;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record MemoryContextSnapshot(
        Long userId,
        String taskContext,
        String rendered,
        Set<Long> memoryIds,
        String contextHash) {

    public MemoryContextSnapshot {
        memoryIds = Collections.unmodifiableSet(new LinkedHashSet<>(
                memoryIds != null ? memoryIds : Set.of()));
    }
}
