package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed, protocol-level view of an ACP {@code session/update} notification's
 * {@code update} object (the value of {@code params.update}).
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1. This is a CLEAN PROTOCOL LAYER — it intentionally
 * does NOT convert to SkillForge {@code Message}/{@code ContentBlock} (that is
 * P1a-2). The discriminator is {@code update.sessionUpdate}.
 *
 * <p>Only {@link TextChunk} (from {@code agent_message_chunk}) and
 * {@link AvailableCommands} (from {@code available_commands_update}) were
 * observed live in the 2026-06-19 spike. The remaining kinds are modelled from
 * the ACP spec but are NOT byte-verified; any unrecognized or malformed kind is
 * routed to {@link Unknown} rather than throwing, so a protocol evolution never
 * crashes the client.
 *
 * <p>Sealed interface (Java 17). Consumers should dispatch with
 * {@code instanceof} pattern matching (exhaustive {@code switch} over sealed
 * types is not a standard feature on Java 17).
 */
public sealed interface AcpUpdate
        permits AcpUpdate.TextChunk,
                AcpUpdate.ThoughtChunk,
                AcpUpdate.ToolCall,
                AcpUpdate.ToolCallUpdate,
                AcpUpdate.Plan,
                AcpUpdate.AvailableCommands,
                AcpUpdate.ModeUpdate,
                AcpUpdate.Unknown {

    /** The raw {@code sessionUpdate} discriminator string (e.g. {@code "agent_message_chunk"}). */
    String rawKind();

    /**
     * {@code agent_message_chunk} — a streamed assistant text delta. VERIFIED:
     * {@code update.content.type == "text"}, text in {@code update.content.text}.
     */
    record TextChunk(String text) implements AcpUpdate {
        @Override
        public String rawKind() {
            return "agent_message_chunk";
        }
    }

    /**
     * {@code agent_thought_chunk} — a streamed reasoning/thought delta (spec;
     * not verified). Text extracted from {@code update.content.text} when present.
     */
    record ThoughtChunk(String text) implements AcpUpdate {
        @Override
        public String rawKind() {
            return "agent_thought_chunk";
        }
    }

    /**
     * {@code tool_call} — a tool invocation started by the agent (spec; not
     * verified). {@code raw} carries the full {@code update} object so P1a-2 can
     * build the tool_use block without re-parsing here.
     */
    record ToolCall(String toolCallId, String title, String kind, String status, JsonNode raw)
            implements AcpUpdate {
        @Override
        public String rawKind() {
            return "tool_call";
        }
    }

    /**
     * {@code tool_call_update} — progress/result for an in-flight tool call
     * (spec; not verified). {@code raw} carries the full {@code update} object.
     */
    record ToolCallUpdate(String toolCallId, String status, JsonNode raw) implements AcpUpdate {
        @Override
        public String rawKind() {
            return "tool_call_update";
        }
    }

    /**
     * {@code plan} — the agent's current plan/todo list (spec; not verified).
     * {@code raw} carries the full {@code update} object (entries[] etc.).
     */
    record Plan(JsonNode raw) implements AcpUpdate {
        @Override
        public String rawKind() {
            return "plan";
        }
    }

    /**
     * {@code available_commands_update} — the agent's slash commands. VERIFIED
     * live (shape captured). {@code raw} carries the full {@code update} object
     * (the {@code availableCommands[]} array).
     */
    record AvailableCommands(JsonNode raw) implements AcpUpdate {
        @Override
        public String rawKind() {
            return "available_commands_update";
        }
    }

    /**
     * {@code current_mode_update} — the agent's active mode changed (spec; not
     * verified). {@code currentModeId} from {@code update.currentModeId}.
     */
    record ModeUpdate(String currentModeId) implements AcpUpdate {
        @Override
        public String rawKind() {
            return "current_mode_update";
        }
    }

    /**
     * Catch-all for any {@code sessionUpdate} kind not modelled above (or a
     * modelled kind whose payload could not be parsed). Carries the original
     * discriminator and the full raw {@code update} node so nothing is lost.
     */
    record Unknown(String rawKind, JsonNode raw) implements AcpUpdate {
    }
}
