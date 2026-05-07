package com.skillforge.core.context;

import com.skillforge.core.llm.cache.SystemPromptParts;
import com.skillforge.core.model.AgentDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROMPT-CACHE-MVP Phase 1 — verify {@link SystemPromptBuilder#buildWithBoundary(String)}
 * splits stable / dynamic correctly and that legacy {@code build()} stays compatible.
 */
@DisplayName("SystemPromptBuilder.buildWithBoundary — stable / dynamic split (Phase 1)")
class SystemPromptBuilderBoundaryTest {

    @Test
    @DisplayName("stable section contains agent prompt + tools guidelines + behavior rules")
    void stableContainsStableSourcesOnly() {
        AgentDefinition agent = new AgentDefinition();
        agent.setSystemPrompt("Be helpful and precise.");
        agent.setSoulPrompt("Speak warmly.");

        SystemPromptParts parts = new SystemPromptBuilder(agent, List.of(), List.of())
                .buildWithBoundary("# CLAUDE.md\nGlobal rules.");

        // INV-1: stable carries CLAUDE.md / agent / soul / tool guidelines.
        assertThat(parts.stable()).contains("Global rules");
        assertThat(parts.stable()).contains("Be helpful and precise");
        assertThat(parts.stable()).contains("Speak warmly");
        assertThat(parts.stable()).contains("Tool Usage Guidelines");
        // Stable must NOT contain context provider data (which is dynamic).
        assertThat(parts.stable()).doesNotContain("## Context");
        // Stable must NOT carry the boundary marker itself.
        assertThat(parts.stable()).doesNotContain("SKILLFORGE_CACHE_BOUNDARY");
    }

    @Test
    @DisplayName("dynamic section captures Context block from providers")
    void dynamicCapturesContextBlock() {
        AgentDefinition agent = new AgentDefinition();
        agent.setSystemPrompt("Base prompt");

        ContextProvider envProvider = new ContextProvider() {
            @Override public String getName() { return "Environment"; }
            @Override public Map<String, String> getContext() {
                Map<String, String> ctx = new LinkedHashMap<>();
                ctx.put("current_date", "2026-05-07");
                return ctx;
            }
        };

        SystemPromptParts parts = new SystemPromptBuilder(agent, List.of(), List.of(envProvider))
                .buildWithBoundary(null);

        assertThat(parts.dynamic()).contains("## Context");
        assertThat(parts.dynamic()).contains("current_date: 2026-05-07");
        assertThat(parts.stable()).doesNotContain("current_date");
    }

    @Test
    @DisplayName("INV-1: stable section is byte-stable across calls (same agent, same skills)")
    void stableSectionIsBytestableAcrossCalls() {
        AgentDefinition agent = new AgentDefinition();
        agent.setSystemPrompt("Be helpful.");
        SystemPromptBuilder builder = new SystemPromptBuilder(agent, List.of(), List.of());

        String first = builder.buildWithBoundary("static md").stable();
        String second = builder.buildWithBoundary("static md").stable();
        String third = builder.buildWithBoundary("static md").stable();

        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    @Test
    @DisplayName("legacy build() == stable + \\n\\n + dynamic (no boundary marker leaks into return)")
    void legacyBuildPreservesPlainConcatenation() {
        AgentDefinition agent = new AgentDefinition();
        agent.setSystemPrompt("Base prompt");
        ContextProvider envProvider = new ContextProvider() {
            @Override public String getName() { return "Environment"; }
            @Override public Map<String, String> getContext() {
                return Map.of("current_date", "2026-05-07");
            }
        };

        SystemPromptBuilder builder = new SystemPromptBuilder(agent, List.of(), List.of(envProvider));

        SystemPromptParts parts = builder.buildWithBoundary(null);
        String legacy = builder.build();

        // Legacy must not include the cache boundary marker — it's a provider-side concern.
        assertThat(legacy).doesNotContain("SKILLFORGE_CACHE_BOUNDARY");
        assertThat(legacy).contains(parts.stable());
        assertThat(legacy).contains(parts.dynamic());
    }
}
