package com.skillforge.core.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.llm.cache.ToolNormalizer;
import com.skillforge.core.model.ToolSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic catalog built only from the current request's authorized Tool
 * schemas. Search cannot widen the authorization set.
 */
public final class ToolCatalog {

    public static final String TOOL_SEARCH_NAME = "ToolSearch";
    private static final Set<String> CORE_TOOL_NAMES = Set.of(
            "Bash", "Read", "Write", "Edit", "Glob", "Grep",
            "Memory", "memory_search", "memory_detail");

    private final Map<String, ToolDescriptor> byId;
    private final Map<String, ToolDescriptor> byName;
    private final List<ToolDescriptor> orderedDescriptors;

    private ToolCatalog(List<ToolDescriptor> descriptors) {
        Map<String, ToolDescriptor> ids = new LinkedHashMap<>();
        Map<String, ToolDescriptor> names = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            ids.put(descriptor.id(), descriptor);
            names.put(descriptor.name(), descriptor);
        }
        this.byId = Map.copyOf(ids);
        this.byName = Map.copyOf(names);
        this.orderedDescriptors = List.copyOf(descriptors);
    }

    public static ToolCatalog fromAuthorizedSchemas(
            Collection<ToolSchema> schemas, ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
        List<ToolDescriptor> descriptors = new ArrayList<>();
        if (schemas != null) {
            for (ToolSchema schema : schemas) {
                if (schema == null || schema.getName() == null || schema.getName().isBlank()) {
                    continue;
                }
                descriptors.add(descriptor(schema, mapper));
            }
        }
        descriptors.sort(Comparator.comparing(ToolDescriptor::name));
        return new ToolCatalog(descriptors);
    }

    public List<ToolDescriptor> descriptors() {
        return orderedDescriptors;
    }

    public ToolDescriptor findByName(String name) {
        return name == null ? null : byName.get(name);
    }

    public List<ToolDescriptor> search(String query, int limit) {
        Set<String> terms = tokenize(query);
        if (terms.isEmpty()) return List.of();
        int boundedLimit = Math.max(1, Math.min(20, limit));
        return orderedDescriptors.stream()
                .filter(ToolDescriptor::searchable)
                .map(descriptor -> new Scored(descriptor, score(descriptor, terms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(scored -> scored.descriptor().name()))
                .limit(boundedLimit)
                .map(Scored::descriptor)
                .toList();
    }

    public List<ToolSchema> exposedSchemas(
            ToolDiscoveryState state, boolean deferredEnabled) {
        if (!deferredEnabled) {
            return orderedDescriptors.stream().map(ToolDescriptor::schema).toList();
        }
        return orderedDescriptors.stream()
                .filter(descriptor -> descriptor.alwaysLoaded()
                        || (state != null && state.isDiscovered(descriptor)))
                .map(ToolDescriptor::schema)
                .toList();
    }

    private static ToolDescriptor descriptor(ToolSchema schema, ObjectMapper mapper) {
        String name = schema.getName();
        ToolKind kind = kindFor(name);
        boolean alwaysLoaded = kind != ToolKind.MCP && kind != ToolKind.MEDIA;
        String schemaHash = ToolNormalizer.hashTool(schema, mapper);
        Set<String> tags = new LinkedHashSet<>(tokenize(name + " " + schema.getDescription()));
        tags.add(kind.name().toLowerCase(Locale.ROOT));
        return new ToolDescriptor(
                "tool:" + name,
                name,
                schema.getDescription(),
                kind,
                sourceFor(kind),
                schema,
                schemaHash,
                alwaysLoaded,
                !TOOL_SEARCH_NAME.equals(name),
                sideEffectFor(name),
                ApprovalPolicy.EXISTING_EXECUTION_POLICY,
                TokenEstimator.estimateString(
                        ToolNormalizer.canonicalJson(List.of(schema), mapper)),
                tags);
    }

    private static ToolKind kindFor(String name) {
        if (TOOL_SEARCH_NAME.equals(name)) return ToolKind.ENGINE;
        if ("Skill".equals(name)) return ToolKind.SKILL_LOADER;
        if ("ask_user".equals(name) || "compact_context".equals(name)) return ToolKind.ENGINE;
        if (name.startsWith("mcp_")) return ToolKind.MCP;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("image") || lower.contains("video") || lower.contains("audio")
                || lower.contains("speech")) {
            return ToolKind.MEDIA;
        }
        if (CORE_TOOL_NAMES.contains(name)) return ToolKind.CORE;
        return ToolKind.JAVA;
    }

    private static CapabilitySource sourceFor(ToolKind kind) {
        return switch (kind) {
            case MCP -> CapabilitySource.MCP_REGISTRY;
            case MEDIA -> CapabilitySource.MEDIA_PROVIDER;
            case SKILL_LOADER -> CapabilitySource.SKILL_REGISTRY;
            case ENGINE -> CapabilitySource.ENGINE_RUNTIME;
            case CORE -> CapabilitySource.CORE_RUNTIME;
            case JAVA -> CapabilitySource.JAVA_REGISTRY;
        };
    }

    private static SideEffectLevel sideEffectFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("read") || lower.contains("search") || lower.contains("grep")
                || lower.contains("glob") || lower.contains("get") || lower.contains("list")) {
            return SideEffectLevel.READ_ONLY;
        }
        if (lower.contains("delete") || lower.contains("send") || lower.contains("publish")
                || lower.contains("install")) {
            return SideEffectLevel.EXTERNAL_WRITE;
        }
        if (lower.contains("write") || lower.contains("edit") || lower.contains("create")
                || lower.contains("update")) {
            return SideEffectLevel.REVERSIBLE_WRITE;
        }
        return SideEffectLevel.UNKNOWN;
    }

    private static int score(ToolDescriptor descriptor, Set<String> terms) {
        String name = descriptor.name().toLowerCase(Locale.ROOT);
        String description = descriptor.description().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (name.equals(term)) score += 20;
            else if (name.contains(term)) score += 10;
            if (descriptor.tags().contains(term)) score += 5;
            if (description.contains(term)) score += 2;
        }
        return score;
    }

    private static Set<String> tokenize(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (!token.isBlank()) out.add(token);
        }
        return out;
    }

    private record Scored(ToolDescriptor descriptor, int score) {}
}
