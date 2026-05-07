package com.skillforge.core.llm.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.SystemPromptBuilder;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROMPT-CACHE-MVP — INV-1 stability suite.
 *
 * <p>Asserts that the cache-eligible prefix (stable system section + sorted tools array)
 * produces an identical SHA-256 across repeated builds for the same agent definition.
 * Any drift in this hash means the Anthropic cache breakpoint will miss every time.
 */
@DisplayName("Prompt cache stability — SHA-256(stable + tools) byte-equal across calls")
class PromptCacheStabilityTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("INV-1: same agent + same tools → same SHA over 3 builds (stable section)")
    void stableSectionIsByteStable() {
        AgentDefinition agent = newAgent();
        SystemPromptBuilder builder = new SystemPromptBuilder(agent, List.of(), List.of());

        String h1 = ToolNormalizer.sha256(builder.buildWithBoundary("CLAUDE.md content").stable());
        String h2 = ToolNormalizer.sha256(builder.buildWithBoundary("CLAUDE.md content").stable());
        String h3 = ToolNormalizer.sha256(builder.buildWithBoundary("CLAUDE.md content").stable());

        assertThat(h1).isEqualTo(h2).isEqualTo(h3).isNotEmpty();
    }

    @Test
    @DisplayName("INV-2: same tools (different order) → same SHA after ToolNormalizer.sortByName")
    void toolsSerializationByteStable() {
        ToolSchema bash = new ToolSchema("Bash", "Run shell.", Map.of());
        ToolSchema read = new ToolSchema("Read", "Read a file.", Map.of());
        ToolSchema write = new ToolSchema("Write", "Write a file.", Map.of());

        String h1 = ToolNormalizer.hashTools(List.of(bash, read, write), mapper);
        String h2 = ToolNormalizer.hashTools(List.of(read, write, bash), mapper);
        String h3 = ToolNormalizer.hashTools(List.of(write, bash, read), mapper);

        assertThat(h1).isEqualTo(h2).isEqualTo(h3).isNotEmpty();
    }

    @Test
    @DisplayName("trailing whitespace is normalized away — same logical text → same hash")
    void trailingWhitespaceNormalizedAway() {
        ToolSchema bashV1 = new ToolSchema("Bash", "Run shell.", Map.of());
        ToolSchema bashV2 = new ToolSchema("Bash", "Run shell.   ", Map.of()); // trailing spaces
        ToolSchema bashV3 = new ToolSchema("Bash", "Run shell.\r\n", Map.of()); // CRLF only

        String h1 = ToolNormalizer.hashTool(bashV1, mapper);
        String h2 = ToolNormalizer.hashTool(bashV2, mapper);
        String h3 = ToolNormalizer.hashTool(bashV3, mapper);

        assertThat(h1).isEqualTo(h2).isEqualTo(h3);
    }

    @Test
    @DisplayName("drift: real semantic description edit → tools SHA differs (cache will miss)")
    void semanticDescriptionEditBreaksHash() {
        ToolSchema bashV1 = new ToolSchema("Bash", "Run shell.", Map.of());
        ToolSchema bashV2 = new ToolSchema("Bash", "Run shell commands.", Map.of());

        assertThat(ToolNormalizer.hashTool(bashV1, mapper))
                .isNotEqualTo(ToolNormalizer.hashTool(bashV2, mapper));
    }

    private static AgentDefinition newAgent() {
        AgentDefinition a = new AgentDefinition();
        a.setSystemPrompt("You are SkillForge agent.");
        a.setSoulPrompt("Speak warmly.");
        return a;
    }
}
