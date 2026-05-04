package com.skillforge.server.eval;

import com.skillforge.core.engine.ToolCallRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRunResultTest {

    @Test
    @DisplayName("applyToolCallSignals detects empty memory search")
    void applyToolCallSignals_emptyMemorySearch_setsMemoryMissingSignals() {
        ScenarioRunResult result = new ScenarioRunResult();

        result.applyToolCallSignals(List.of(toolCall("memory_search", true, "No memories found for: auth")));

        assertThat(result.isMemorySkillCalled()).isTrue();
        assertThat(result.isMemoryResultEmpty()).isTrue();
        assertThat(result.isSkillExecutionFailed()).isFalse();
    }

    @Test
    @DisplayName("applyToolCallSignals detects failed tool calls")
    void applyToolCallSignals_failedTool_setsSkillExecutionFailed() {
        ScenarioRunResult result = new ScenarioRunResult();

        result.applyToolCallSignals(List.of(toolCall("Read", false, "file not found")));

        assertThat(result.isSkillExecutionFailed()).isTrue();
        assertThat(result.isMemorySkillCalled()).isFalse();
    }

    private static ToolCallRecord toolCall(String name, boolean success, String output) {
        return new ToolCallRecord(name, Map.of(), output, success, 1L, 1L);
    }
}
