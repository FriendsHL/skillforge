package com.skillforge.core.capability;

import com.skillforge.core.model.ToolSchema;

import java.util.Set;

/**
 * Searchable metadata around an existing authoritative ToolSchema. Catalog
 * descriptors never grant permission; callers must build the catalog from the
 * already-authorized schema set.
 */
public record ToolDescriptor(
        String id,
        String name,
        String description,
        ToolKind kind,
        CapabilitySource source,
        ToolSchema schema,
        String schemaHash,
        boolean alwaysLoaded,
        boolean searchable,
        SideEffectLevel sideEffect,
        ApprovalPolicy approvalPolicy,
        int schemaTokenCost,
        Set<String> tags) {

    public ToolDescriptor {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        schemaHash = schemaHash == null ? "" : schemaHash;
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
