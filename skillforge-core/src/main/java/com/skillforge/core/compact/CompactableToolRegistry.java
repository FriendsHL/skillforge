package com.skillforge.core.compact;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Controls which tools' results are eligible for compaction / truncation.
 *
 * <p>Only tool_results from tools in the whitelist will be truncated by
 * {@link LightCompactStrategy} Rule 1. Tools NOT in the whitelist
 * (e.g. Memory, SubAgent) are left untouched to preserve semantically
 * dense content.
 *
 * <p>Immutable after construction — thread-safe.
 *
 * <p>Used by P9-1 (whitelist), P9-3 (time-based cold cleanup).
 */
public final class CompactableToolRegistry {

    /**
     * Default whitelist: file-system / shell / network tools whose output
     * is typically large and expendable after initial processing.
     */
    public static final Set<String> DEFAULT_COMPACTABLE_TOOLS = Set.of(
            "Bash", "Read", "Write", "Edit",
            "Grep", "Glob", "WebFetch", "WebSearch",
            "Browser", "CodeSandbox", "CodeReview"
    );

    /**
     * Fallback truncation threshold (bytes) for any compactable tool without a per-tool bucket.
     * Mirrors {@code LightCompactStrategy.LARGE_TOOL_OUTPUT_BYTES}'s historical 10KB cut-over.
     */
    public static final int DEFAULT_TRUNCATE_THRESHOLD_BYTES = 10 * 1024;

    /**
     * Per-tool truncation byte thresholds (tool-name → bytes). A tool_result is only truncated once
     * its UTF-8 byte size exceeds the matching bucket. Tools absent from this map fall back to
     * {@link #DEFAULT_TRUNCATE_THRESHOLD_BYTES}.
     *
     * <p>Buckets reflect how reusable each tool's output is after first read:
     * Bash output is large and the most expendable (50KB); file reads keep more (30KB); web search
     * results are denser and worth keeping shorter (15KB); Grep/Glob are usually short listings whose
     * tail matters, so they get the smaller default. Only the larger-than-default buckets are listed.
     */
    public static final Map<String, Integer> DEFAULT_TRUNCATE_THRESHOLDS = Map.of(
            "Bash", 50 * 1024,
            "Read", 30 * 1024,
            "WebFetch", 15 * 1024,
            "WebSearch", 15 * 1024
    );

    private final Set<String> compactableTools;
    private final Map<String, Integer> truncateThresholds;

    /** Create with default whitelist. */
    public CompactableToolRegistry() {
        this.compactableTools = new LinkedHashSet<>(DEFAULT_COMPACTABLE_TOOLS);
        this.truncateThresholds = Map.copyOf(DEFAULT_TRUNCATE_THRESHOLDS);
    }

    /** Create with a custom whitelist (for per-agent override). */
    public CompactableToolRegistry(Set<String> customTools) {
        this.compactableTools = (customTools != null && !customTools.isEmpty())
                ? new LinkedHashSet<>(customTools)
                : new LinkedHashSet<>(DEFAULT_COMPACTABLE_TOOLS);
        this.truncateThresholds = Map.copyOf(DEFAULT_TRUNCATE_THRESHOLDS);
    }

    /** Create with a custom whitelist AND custom per-tool truncation thresholds (per-agent override). */
    public CompactableToolRegistry(Set<String> customTools, Map<String, Integer> customThresholds) {
        this.compactableTools = (customTools != null && !customTools.isEmpty())
                ? new LinkedHashSet<>(customTools)
                : new LinkedHashSet<>(DEFAULT_COMPACTABLE_TOOLS);
        this.truncateThresholds = (customThresholds != null && !customThresholds.isEmpty())
                ? Map.copyOf(customThresholds)
                : Map.copyOf(DEFAULT_TRUNCATE_THRESHOLDS);
    }

    /** Returns {@code true} if the tool's result may be compacted / truncated. */
    public boolean isCompactable(String toolName) {
        return toolName != null && compactableTools.contains(toolName);
    }

    /**
     * The truncation byte threshold for {@code toolName}'s tool_result. Returns the per-tool bucket
     * if defined, otherwise {@link #DEFAULT_TRUNCATE_THRESHOLD_BYTES}. A null tool name (unknown
     * provenance) also gets the default.
     */
    public int truncateThresholdBytesFor(String toolName) {
        if (toolName == null) {
            return DEFAULT_TRUNCATE_THRESHOLD_BYTES;
        }
        return truncateThresholds.getOrDefault(toolName, DEFAULT_TRUNCATE_THRESHOLD_BYTES);
    }

    public Set<String> getCompactableTools() {
        return Collections.unmodifiableSet(compactableTools);
    }

    /**
     * Build a registry from agent config JSON map.
     *
     * <p>Reads the {@code "compactable_tools"} key. If present and non-empty,
     * uses that list as the whitelist; otherwise falls back to defaults.
     *
     * <p>Example agent config: {@code {"compactable_tools": ["Bash","Read","Grep"]}}
     */
    public static CompactableToolRegistry fromAgentConfig(Map<String, Object> agentConfig) {
        if (agentConfig == null) {
            return new CompactableToolRegistry();
        }
        Set<String> custom = new LinkedHashSet<>();
        Object override = agentConfig.get("compactable_tools");
        if (override instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    custom.add(s);
                }
            }
        }
        // Optional per-tool truncation byte buckets: {"compactable_tool_thresholds": {"Bash": 65536}}.
        Map<String, Integer> thresholds = null;
        Object thr = agentConfig.get("compactable_tool_thresholds");
        if (thr instanceof Map<?, ?> map) {
            Map<String, Integer> parsed = new HashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof Number n
                        && n.longValue() > 0) {
                    parsed.put(k, Math.toIntExact(n.longValue()));
                }
            }
            if (!parsed.isEmpty()) {
                thresholds = parsed;
            }
        }
        if (custom.isEmpty() && thresholds == null) {
            return new CompactableToolRegistry();
        }
        return new CompactableToolRegistry(
                custom.isEmpty() ? null : custom,
                thresholds);
    }
}
