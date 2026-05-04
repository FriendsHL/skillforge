package com.skillforge.core.llm;

import com.skillforge.core.model.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmResponseTest {

    @Test
    @DisplayName("valid toolUseBlocks make response a tool-use turn even when stopReason is end_turn")
    void isToolUse_toolBlocksPresentDespiteEndTurn_true() {
        LlmResponse response = new LlmResponse();
        response.setStopReason("end_turn");
        response.setToolUseBlocks(List.of(new ToolUseBlock("call-1", "Edit", Map.of("file_path", "x"))));

        assertThat(response.isToolUse()).isTrue();
    }

    @Test
    @DisplayName("invalid toolUseBlocks with blank id or name are not executable tool-use turns")
    void isToolUse_blankIdOrName_false() {
        LlmResponse response = new LlmResponse();
        response.setStopReason("end_turn");
        response.setToolUseBlocks(List.of(
                new ToolUseBlock("", "Edit", Map.of()),
                new ToolUseBlock("call-2", " ", Map.of())));

        assertThat(response.isToolUse()).isFalse();
        assertThat(response.getValidToolUseBlocks()).isEmpty();
    }

    @Test
    @DisplayName("valid toolUseBlocks filter null, blank, duplicate, and null-literal ids")
    void getValidToolUseBlocks_filtersInvalidBlocks() {
        LlmResponse response = new LlmResponse();
        ToolUseBlock first = new ToolUseBlock("call-1", "ReadFile", Map.of("path", "a"));
        ToolUseBlock duplicate = new ToolUseBlock("call-1", "ReadFile", Map.of("path", "b"));
        ToolUseBlock nullLiteral = new ToolUseBlock("null", "ReadFile", Map.of());
        ToolUseBlock blankName = new ToolUseBlock("call-2", "", Map.of());
        List<ToolUseBlock> blocks = new ArrayList<>();
        blocks.add(null);
        blocks.add(first);
        blocks.add(duplicate);
        blocks.add(nullLiteral);
        blocks.add(blankName);
        response.setToolUseBlocks(blocks);

        assertThat(response.getValidToolUseBlocks()).containsExactly(first);
    }
}
