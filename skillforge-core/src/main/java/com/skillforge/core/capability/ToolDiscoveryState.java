package com.skillforge.core.capability;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-loop discovery state. A schema is exposed only after the descriptor was
 * discovered from the same authorized catalog and its current hash is recorded.
 */
public final class ToolDiscoveryState {

    private final Set<String> discoveredToolIds = new LinkedHashSet<>();
    private final Map<String, String> resolvedSchemaHashes = new LinkedHashMap<>();

    public synchronized void discover(ToolDescriptor descriptor) {
        if (descriptor == null || descriptor.id().isBlank()) return;
        discoveredToolIds.add(descriptor.id());
        resolvedSchemaHashes.put(descriptor.id(), descriptor.schemaHash());
    }

    public synchronized boolean isDiscovered(ToolDescriptor descriptor) {
        if (descriptor == null) return false;
        return discoveredToolIds.contains(descriptor.id())
                && descriptor.schemaHash().equals(resolvedSchemaHashes.get(descriptor.id()));
    }

    public synchronized Set<String> discoveredToolIds() {
        return Set.copyOf(discoveredToolIds);
    }

    public synchronized Map<String, String> resolvedSchemaHashes() {
        return Map.copyOf(resolvedSchemaHashes);
    }

    /**
     * Restore content-free discovery references from a trusted runtime
     * checkpoint. Authorization and current-schema validation still happen in
     * {@link #isDiscovered(ToolDescriptor)}.
     */
    public synchronized void restore(Map<String, String> schemaHashesByToolId) {
        discoveredToolIds.clear();
        resolvedSchemaHashes.clear();
        if (schemaHashesByToolId == null) return;
        schemaHashesByToolId.forEach((id, hash) -> {
            if (id == null || id.isBlank() || hash == null || hash.isBlank()) return;
            discoveredToolIds.add(id);
            resolvedSchemaHashes.put(id, hash);
        });
    }
}
