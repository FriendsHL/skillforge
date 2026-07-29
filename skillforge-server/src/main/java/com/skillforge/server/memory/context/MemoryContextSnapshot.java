package com.skillforge.server.memory.context;

import com.skillforge.core.engine.MemoryInjectionRef;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record MemoryContextSnapshot(
        Long userId,
        String taskContext,
        String rendered,
        Set<Long> memoryIds,
        String contextHash,
        List<MemoryInjectionRef> provenance) {

    public MemoryContextSnapshot(
            Long userId,
            String taskContext,
            String rendered,
            Set<Long> memoryIds,
            String contextHash) {
        this(userId, taskContext, rendered, memoryIds, contextHash, List.of());
    }

    public MemoryContextSnapshot {
        memoryIds = Collections.unmodifiableSet(new LinkedHashSet<>(
                memoryIds != null ? memoryIds : Set.of()));
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
    }
}
