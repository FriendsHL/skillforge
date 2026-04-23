package com.skillforge.core.engine;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AgentLoopEngine#findMissingRequiredFields(Tool, Map)}: ensures
 * the engine pre-validates required arguments against the tool's JSON schema before
 * dispatching to {@link Tool#execute(Map, SkillContext)}.
 *
 * <p>Backstop for ENG-2 (session 9347f84c follow-up): even if a skill forgets to
 * validate its own required fields, the engine returns a structured retry hint
 * tagged VALIDATION so detectWaste does not amplify the LLM's missing-arg loop
 * into a compaction (covered separately by AgentLoopEngineWasteDetectTest).
 */
class AgentLoopEnginePreValidationTest {

    /**
     * Minimal Tool stub with configurable schema and execution recorder.
     */
    private static class StubTool implements Tool {
        private final String name;
        private final ToolSchema schema;
        boolean executed = false;

        StubTool(String name, ToolSchema schema) {
            this.name = name;
            this.schema = schema;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return ""; }
        @Override public ToolSchema getToolSchema() { return schema; }
        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
            executed = true;
            return SkillResult.success("ok");
        }
    }

    private ToolSchema schemaWithRequired(List<String> required) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("required", required);
        return new ToolSchema("Stub", "stub", input);
    }

    @Test
    @DisplayName("input contains all required fields → no missing")
    void allRequiredPresent_returnsEmpty() {
        Tool tool = new StubTool("FileWrite", schemaWithRequired(List.of("file_path", "content")));
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", "/tmp/x");
        input.put("content", "hi");

        assertThat(AgentLoopEngine.findMissingRequiredFields(tool, input)).isEmpty();
    }

    @Test
    @DisplayName("9347f84c shape: empty input on schema with required fields → all listed missing")
    void emptyInputOnRequiredSchema_listsAllMissing() {
        Tool tool = new StubTool("FileWrite",
                schemaWithRequired(List.of("file_path", "content")));

        List<String> missing = AgentLoopEngine.findMissingRequiredFields(tool, Map.of());

        assertThat(missing)
                .as("Empty input must surface every required field at once for retry hint")
                .containsExactly("file_path", "content");
    }

    @Test
    @DisplayName("required key present but value is null → counted as missing")
    void requiredKeyWithNullValue_countsAsMissing() {
        Tool tool = new StubTool("FileEdit",
                schemaWithRequired(List.of("file_path", "old_string", "new_string")));
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", "/tmp/x");
        input.put("old_string", null);
        input.put("new_string", "y");

        assertThat(AgentLoopEngine.findMissingRequiredFields(tool, input))
                .containsExactly("old_string");
    }

    @Test
    @DisplayName("required field with empty string value is NOT pre-rejected (skill decides)")
    void emptyStringValue_passesPreValidation() {
        Tool tool = new StubTool("FileWrite",
                schemaWithRequired(List.of("file_path", "content")));
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", "/tmp/x");
        input.put("content", "");   // legitimate for FileWrite (write empty file)

        assertThat(AgentLoopEngine.findMissingRequiredFields(tool, input))
                .as("Engine pre-validation only checks key presence + non-null;"
                        + " emptiness/blank semantics belong to the skill")
                .isEmpty();
    }

    @Test
    @DisplayName("schema with no required field → never reports missing (backwards compat)")
    void noRequiredInSchema_returnsEmpty() {
        Map<String, Object> rawSchema = new LinkedHashMap<>();
        rawSchema.put("type", "object");
        Tool tool = new StubTool("Free", new ToolSchema("Free", "no required", rawSchema));

        assertThat(AgentLoopEngine.findMissingRequiredFields(tool, Map.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("null tool / null schema / null input → returns empty (defensive)")
    void nullDefensive_returnsEmpty() {
        assertThat(AgentLoopEngine.findMissingRequiredFields(null, Map.of())).isEmpty();

        Tool noSchema = new StubTool("X", null);
        assertThat(AgentLoopEngine.findMissingRequiredFields(noSchema, Map.of())).isEmpty();

        Tool tool = new StubTool("FileWrite",
                schemaWithRequired(List.of("file_path", "content")));
        assertThat(AgentLoopEngine.findMissingRequiredFields(tool, null))
                .containsExactly("file_path", "content");
    }
}
