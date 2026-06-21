package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AcpUpdateTranslator} for the cc adapter's {@code session/update} dialect
 * ({@code @agentclientprotocol/claude-agent-acp}).
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1. Stateless and side-effect free (apart from DEBUG
 * logging). NEVER throws on a malformed/unknown payload — anything it cannot
 * confidently type is returned as {@link AcpUpdate.Unknown} so the client loop
 * is crash-proof against protocol drift / adapter differences.
 *
 * <p>Intentionally does NOT depend on SkillForge {@code Message}/{@code
 * ContentBlock} — that conversion is P1a-2.
 *
 * <p>P3 will add a sibling {@code CodexAcpUpdateTranslator} for the codex dialect;
 * the discriminator field / kind names may differ there.
 */
public class CcAcpUpdateTranslator implements AcpUpdateTranslator {

    private static final Logger log = LoggerFactory.getLogger(CcAcpUpdateTranslator.class);

    @Override
    public AcpUpdate translate(JsonNode update) {
        if (update == null || !update.isObject()) {
            return new AcpUpdate.Unknown("<no-update-object>", update);
        }
        String kind = text(update, "sessionUpdate");
        if (kind == null || kind.isBlank()) {
            return new AcpUpdate.Unknown("<missing-sessionUpdate>", update);
        }

        try {
            switch (kind) {
                case "agent_message_chunk":
                    return new AcpUpdate.TextChunk(contentText(update));
                case "agent_thought_chunk":
                    return new AcpUpdate.ThoughtChunk(contentText(update));
                case "tool_call":
                    return new AcpUpdate.ToolCall(
                            text(update, "toolCallId"),
                            text(update, "title"),
                            text(update, "kind"),
                            text(update, "status"),
                            update);
                case "tool_call_update":
                    return new AcpUpdate.ToolCallUpdate(
                            text(update, "toolCallId"),
                            text(update, "status"),
                            update);
                case "plan":
                    return new AcpUpdate.Plan(update);
                case "available_commands_update":
                    return new AcpUpdate.AvailableCommands(update);
                case "current_mode_update":
                    return new AcpUpdate.ModeUpdate(text(update, "currentModeId"));
                default:
                    log.debug("ACP session/update unknown kind '{}' routed to Unknown", kind);
                    return new AcpUpdate.Unknown(kind, update);
            }
        } catch (RuntimeException e) {
            // Defensive: a modelled kind with an unexpected payload must not crash the loop.
            log.debug("ACP session/update kind '{}' failed to parse, routing to Unknown", kind, e);
            return new AcpUpdate.Unknown(kind, update);
        }
    }

    /**
     * Extract {@code update.content.text} when {@code content.type == "text"}.
     * Returns {@code ""} (never {@code null}) when absent so chunk consumers can
     * append safely.
     */
    private static String contentText(JsonNode update) {
        JsonNode content = update.get("content");
        if (content == null || !content.isObject()) {
            return "";
        }
        String type = text(content, "type");
        if (type != null && !"text".equals(type)) {
            // Non-text content block (e.g. image) — no text to surface yet.
            return "";
        }
        String t = text(content, "text");
        return t != null ? t : "";
    }

    /** Null-safe string field accessor (returns {@code null} for missing/null/non-textual). */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isTextual()) ? v.asText() : null;
    }
}
