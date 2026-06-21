package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link CcAcpUpdateTranslator} using the real captured
 * {@code session/update} shapes from the 2026-06-19 spike, plus spec-modelled
 * and unknown kinds. No subprocess, no network.
 */
class CcAcpUpdateTranslatorTest {

    // Test-only plain ObjectMapper: these payloads have NO time-typed fields, so the
    // JavaTimeModule footgun (#1) does not apply here. Do NOT copy this into production
    // code — inject the Spring-managed ObjectMapper there.
    private final ObjectMapper mapper = new ObjectMapper();
    private final AcpUpdateTranslator translator = new CcAcpUpdateTranslator();

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("agent_message_chunk → TextChunk with content.text (VERIFIED shape)")
    void agentMessageChunk_translatesToTextChunk() {
        JsonNode update = parse("""
                {"sessionUpdate":"agent_message_chunk",
                 "content":{"type":"text","text":"pong"}}""");

        AcpUpdate result = translator.translate(update);

        assertThat(result).isInstanceOf(AcpUpdate.TextChunk.class);
        assertThat(((AcpUpdate.TextChunk) result).text()).isEqualTo("pong");
        assertThat(result.rawKind()).isEqualTo("agent_message_chunk");
    }

    @Test
    @DisplayName("available_commands_update → AvailableCommands carrying raw (VERIFIED kind)")
    void availableCommandsUpdate_translatesToAvailableCommands() {
        JsonNode update = parse("""
                {"sessionUpdate":"available_commands_update",
                 "availableCommands":[{"name":"compact","description":"Compact"}]}""");

        AcpUpdate result = translator.translate(update);

        assertThat(result).isInstanceOf(AcpUpdate.AvailableCommands.class);
        AcpUpdate.AvailableCommands ac = (AcpUpdate.AvailableCommands) result;
        assertThat(ac.raw().get("availableCommands").get(0).get("name").asText())
                .isEqualTo("compact");
    }

    @Test
    @DisplayName("agent_thought_chunk → ThoughtChunk (spec-modelled)")
    void thoughtChunk_translates() {
        JsonNode update = parse("""
                {"sessionUpdate":"agent_thought_chunk",
                 "content":{"type":"text","text":"thinking..."}}""");

        AcpUpdate result = translator.translate(update);

        assertThat(result).isInstanceOf(AcpUpdate.ThoughtChunk.class);
        assertThat(((AcpUpdate.ThoughtChunk) result).text()).isEqualTo("thinking...");
    }

    @Test
    @DisplayName("tool_call → ToolCall with extracted fields + raw")
    void toolCall_translates() {
        JsonNode update = parse("""
                {"sessionUpdate":"tool_call","toolCallId":"tc-1",
                 "title":"Task","kind":"other","status":"pending"}""");

        AcpUpdate result = translator.translate(update);

        assertThat(result).isInstanceOf(AcpUpdate.ToolCall.class);
        AcpUpdate.ToolCall tc = (AcpUpdate.ToolCall) result;
        assertThat(tc.toolCallId()).isEqualTo("tc-1");
        assertThat(tc.title()).isEqualTo("Task");
        assertThat(tc.status()).isEqualTo("pending");
        assertThat(tc.raw()).isSameAs(update);
    }

    @Test
    @DisplayName("tool_call_update → ToolCallUpdate")
    void toolCallUpdate_translates() {
        JsonNode update = parse("""
                {"sessionUpdate":"tool_call_update","toolCallId":"tc-1","status":"completed"}""");

        AcpUpdate result = translator.translate(update);

        assertThat(result).isInstanceOf(AcpUpdate.ToolCallUpdate.class);
        assertThat(((AcpUpdate.ToolCallUpdate) result).status()).isEqualTo("completed");
    }

    @Test
    @DisplayName("current_mode_update → ModeUpdate")
    void modeUpdate_translates() {
        JsonNode update = parse("""
                {"sessionUpdate":"current_mode_update","currentModeId":"ask"}""");

        AcpUpdate result = translator.translate(update);

        assertThat(result).isInstanceOf(AcpUpdate.ModeUpdate.class);
        assertThat(((AcpUpdate.ModeUpdate) result).currentModeId()).isEqualTo("ask");
    }

    @Test
    @DisplayName("unknown sessionUpdate kind → Unknown (no throw), raw preserved")
    void unknownKind_routesToUnknown() {
        JsonNode update = parse("""
                {"sessionUpdate":"some_future_kind","foo":"bar"}""");

        AcpUpdate result = translator.translate(update);

        assertThat(result).isInstanceOf(AcpUpdate.Unknown.class);
        AcpUpdate.Unknown u = (AcpUpdate.Unknown) result;
        assertThat(u.rawKind()).isEqualTo("some_future_kind");
        assertThat(u.raw().get("foo").asText()).isEqualTo("bar");
    }

    @Test
    @DisplayName("missing sessionUpdate / null / non-object → Unknown, never throws")
    void malformed_neverThrows() {
        assertThatCode(() -> {
            assertThat(translator.translate(parse("{\"noKind\":1}")))
                    .isInstanceOf(AcpUpdate.Unknown.class);
            assertThat(translator.translate(null)).isInstanceOf(AcpUpdate.Unknown.class);
            assertThat(translator.translate(parse("\"a-string\"")))
                    .isInstanceOf(AcpUpdate.Unknown.class);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("agent_message_chunk with non-text content → TextChunk(\"\") (no throw)")
    void agentMessageChunk_nonTextContent_emptyText() {
        JsonNode update = parse("""
                {"sessionUpdate":"agent_message_chunk",
                 "content":{"type":"image","data":"..."}}""");

        AcpUpdate result = translator.translate(update);

        assertThat(result).isInstanceOf(AcpUpdate.TextChunk.class);
        assertThat(((AcpUpdate.TextChunk) result).text()).isEmpty();
    }
}
