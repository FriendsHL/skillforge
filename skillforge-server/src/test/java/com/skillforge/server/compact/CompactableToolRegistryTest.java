package com.skillforge.server.compact;

import com.skillforge.core.compact.CompactableToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompactableToolRegistryTest {

    @Test
    void defaults_include_expected_tools() {
        var registry = new CompactableToolRegistry();
        for (String name : List.of("Bash", "Read", "Write", "Edit",
                "Grep", "Glob", "WebFetch", "WebSearch",
                "Browser", "CodeSandbox", "CodeReview")) {
            assertThat(registry.isCompactable(name))
                    .as("Expected %s to be compactable", name)
                    .isTrue();
        }
    }

    @Test
    void defaults_exclude_memory_and_agent_tools() {
        var registry = new CompactableToolRegistry();
        for (String name : List.of("Memory", "MemorySearch", "MemoryDetail",
                "SubAgent", "TeamCreate", "TeamKill", "TeamSend", "TeamList",
                "TodoWrite", "ClawHub")) {
            assertThat(registry.isCompactable(name))
                    .as("Expected %s to NOT be compactable", name)
                    .isFalse();
        }
    }

    @Test
    void isCompactable_null_returns_false() {
        assertThat(new CompactableToolRegistry().isCompactable(null)).isFalse();
    }

    @Test
    void isCompactable_unknown_tool_returns_false() {
        assertThat(new CompactableToolRegistry().isCompactable("SomeNewTool")).isFalse();
    }

    @Test
    void custom_whitelist_overrides_defaults() {
        var registry = new CompactableToolRegistry(Set.of("Bash", "Memory"));
        assertThat(registry.isCompactable("Bash")).isTrue();
        assertThat(registry.isCompactable("Memory")).isTrue();
        assertThat(registry.isCompactable("Grep")).isFalse(); // not in custom list
    }

    @Test
    void empty_custom_set_falls_back_to_defaults() {
        var registry = new CompactableToolRegistry(Set.of());
        assertThat(registry.isCompactable("Bash")).isTrue();
    }

    @Test
    void null_custom_set_falls_back_to_defaults() {
        var registry = new CompactableToolRegistry(null);
        assertThat(registry.isCompactable("Bash")).isTrue();
    }

    @Test
    void getCompactableTools_returns_unmodifiable() {
        var registry = new CompactableToolRegistry();
        Set<String> tools = registry.getCompactableTools();
        assertThat(tools).isNotEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> tools.add("Hack"));
    }

    // ---- fromAgentConfig ----

    @Test
    void fromAgentConfig_null_returns_defaults() {
        var registry = CompactableToolRegistry.fromAgentConfig(null);
        assertThat(registry.isCompactable("Bash")).isTrue();
    }

    @Test
    void fromAgentConfig_empty_map_returns_defaults() {
        var registry = CompactableToolRegistry.fromAgentConfig(Map.of());
        assertThat(registry.isCompactable("Bash")).isTrue();
    }

    @Test
    void fromAgentConfig_with_override_uses_custom_list() {
        Map<String, Object> config = Map.of("compactable_tools", List.of("Bash", "Read"));
        var registry = CompactableToolRegistry.fromAgentConfig(config);
        assertThat(registry.isCompactable("Bash")).isTrue();
        assertThat(registry.isCompactable("Read")).isTrue();
        assertThat(registry.isCompactable("Grep")).isFalse();
    }

    @Test
    void fromAgentConfig_with_empty_list_uses_defaults() {
        Map<String, Object> config = Map.of("compactable_tools", List.of());
        var registry = CompactableToolRegistry.fromAgentConfig(config);
        assertThat(registry.isCompactable("Bash")).isTrue();
    }

    @Test
    void fromAgentConfig_ignores_non_list_value() {
        Map<String, Object> config = Map.of("compactable_tools", "not-a-list");
        var registry = CompactableToolRegistry.fromAgentConfig(config);
        assertThat(registry.isCompactable("Bash")).isTrue();
    }
}
