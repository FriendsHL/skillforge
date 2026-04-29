package com.skillforge.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-W5 (R3) — exhaustively covers {@link Message#getTextContent()} so the
 * tool_result branch correctly handles {@code String} content as well as
 * {@code List<ContentBlock>} / {@code List<Map>} content (multi-step / SubAgent
 * tools that return nested blocks).
 *
 * <p>Without the {@code List} branch we used to fall through to
 * {@code String.valueOf(...)} which produced "[com.skillforge.core.model.ContentBlock@...]"
 * garbage, polluting {@code trace_span.output} for affected calls.
 */
class MessageGetTextContentTest {

    // -----------------------------------------------------------------------
    // String content (top level)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("string content returns string verbatim")
    void stringContentReturnsVerbatim() {
        Message msg = Message.user("hello world");
        assertThat(msg.getTextContent()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("null content returns empty string")
    void nullContentReturnsEmpty() {
        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(null);
        assertThat(msg.getTextContent()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // text ContentBlock list
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("List<ContentBlock> with text blocks joins with newline")
    void textBlocksJoinedWithNewline() {
        Message msg = new Message();
        msg.setRole(Message.Role.ASSISTANT);
        msg.setContent(List.of(
                ContentBlock.text("first"),
                ContentBlock.text("second")));
        assertThat(msg.getTextContent()).isEqualTo("first\nsecond");
    }

    // -----------------------------------------------------------------------
    // tool_result ContentBlock with String content
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tool_result ContentBlock with String content returns the string")
    void toolResultStringContent() {
        Message msg = Message.toolResult("tool_use_1", "tool output text", false);
        assertThat(msg.getTextContent()).isEqualTo("tool output text");
    }

    @Test
    @DisplayName("tool_result ContentBlock with empty content returns empty string")
    void toolResultEmptyContent() {
        Message msg = Message.toolResult("tool_use_2", "", false);
        assertThat(msg.getTextContent()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // tool_result Map with List<Map> content (Anthropic multi-block tool results)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tool_result Map with String content returns the string")
    void toolResultMapStringContent() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", "tool_use_3");
        block.put("content", "map-shaped tool output");
        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(block));
        assertThat(msg.getTextContent()).isEqualTo("map-shaped tool output");
    }

    @Test
    @DisplayName("tool_result Map with List<Map> nested text blocks joins with newline")
    void toolResultMapListContent() {
        Map<String, Object> nested1 = Map.of("type", "text", "text", "step1");
        Map<String, Object> nested2 = Map.of("type", "text", "text", "step2");
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", "tool_use_4");
        block.put("content", List.of(nested1, nested2));
        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(block));
        // BE-W5 fix: must be the joined inner text, NOT toString of the List.
        assertThat(msg.getTextContent()).isEqualTo("step1\nstep2");
        assertThat(msg.getTextContent()).doesNotContain("ContentBlock@");
        assertThat(msg.getTextContent()).doesNotContain("[{");
    }

    @Test
    @DisplayName("tool_result Map with empty List content returns empty string")
    void toolResultMapEmptyListContent() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", "tool_use_5");
        block.put("content", List.of());
        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(block));
        assertThat(msg.getTextContent()).isEmpty();
    }

    @Test
    @DisplayName("tool_result Map with null content yields no text contribution")
    void toolResultMapNullContent() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", "tool_use_6");
        block.put("content", null);
        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(block));
        // Null content adds nothing; if there are no other blocks the result is empty.
        assertThat(msg.getTextContent()).isEmpty();
    }
}
