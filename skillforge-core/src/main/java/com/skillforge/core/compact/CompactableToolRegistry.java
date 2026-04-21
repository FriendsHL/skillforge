package com.skillforge.core.compact;

import java.util.Collections;
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
            "Bash", "FileRead", "FileWrite", "FileEdit",
            "Grep", "Glob", "WebFetch", "WebSearch",
            "Browser", "CodeSandbox", "CodeReview"
    );

    private final Set<String> compactableTools;

    /** Create with default whitelist. */
    public CompactableToolRegistry() {
        this.compactableTools = new LinkedHashSet<>(DEFAULT_COMPACTABLE_TOOLS);
    }

    /** Create with a custom whitelist (for per-agent override). */
    public CompactableToolRegistry(Set<String> customTools) {
        this.compactableTools = (customTools != null && !customTools.isEmpty())
                ? new LinkedHashSet<>(customTools)
                : new LinkedHashSet<>(DEFAULT_COMPACTABLE_TOOLS);
    }

    /** Returns {@code true} if the tool's result may be compacted / truncated. */
    public boolean isCompactable(String toolName) {
        return toolName != null && compactableTools.contains(toolName);
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
     * <p>Example agent config: {@code {"compactable_tools": ["Bash","FileRead","Grep"]}}
     */
    public static CompactableToolRegistry fromAgentConfig(Map<String, Object> agentConfig) {
        if (agentConfig == null) {
            return new CompactableToolRegistry();
        }
        Object override = agentConfig.get("compactable_tools");
        if (override instanceof List<?> list) {
            Set<String> custom = new LinkedHashSet<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    custom.add(s);
                }
            }
            if (!custom.isEmpty()) {
                return new CompactableToolRegistry(custom);
            }
        }
        return new CompactableToolRegistry();
    }
}
