package com.skillforge.core.context;

import com.skillforge.core.model.AgentDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the built-in global system prompt (SKILLFORGE-SYSTEM-PROMPT) loads from the
 * classpath resource and is placed as the first stable segment by the system prompt builder.
 */
class GlobalSystemPromptProviderTest {

    @Test
    @DisplayName("loads non-blank prompt with the platform identity feature line")
    void loads_nonBlank_withFeatureLine() {
        String prompt = new GlobalSystemPromptProvider().get();

        assertThat(prompt).isNotBlank();
        // Feature lines from the audited v1 content — guards against an empty/wrong resource.
        assertThat(prompt).contains("运行在 SkillForge 平台上的 AI agent");
        assertThat(prompt).contains("先说,再做");
    }

    @Test
    @DisplayName("SystemPromptBuilder places the global prompt as the very first segment")
    void systemPromptBuilder_placesGlobalPromptFirst() {
        String globalPrompt = new GlobalSystemPromptProvider().get();

        AgentDefinition agent = new AgentDefinition();
        agent.setSystemPrompt("You are the test agent.");

        String built = new SystemPromptBuilder(agent, List.of(), List.of()).build(globalPrompt);

        assertThat(built).startsWith(globalPrompt.strip());
        assertThat(built.indexOf("运行在 SkillForge 平台上的 AI agent"))
                .as("global prompt appears before the agent's own prompt")
                .isLessThan(built.indexOf("You are the test agent."));
    }

    @Test
    @DisplayName("fails fast when the resource is missing on the classpath")
    void failsFast_whenResourceMissing() {
        assertThatThrownBy(() ->
                new GlobalSystemPromptProvider("prompts/does-not-exist.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("fails fast when the resource is blank")
    void failsFast_whenResourceBlank() {
        assertThatThrownBy(() ->
                new GlobalSystemPromptProvider("prompts/global-system-prompt-blank-fixture.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }
}
