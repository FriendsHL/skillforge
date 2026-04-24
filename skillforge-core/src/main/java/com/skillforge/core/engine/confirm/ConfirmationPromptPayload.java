package com.skillforge.core.engine.confirm;

import java.time.Instant;
import java.util.List;

/**
 * Payload broadcast to a chat session (WebSocket / Feishu card) when engine needs user
 * approval before executing an install command.
 *
 * <p>Field {@code installTarget} may be the sentinel {@code "*"} when the command was
 * unparseable or contains multiple install statements; frontend renders a warning in
 * that case and the cache layer refuses to memoize the decision.
 */
public record ConfirmationPromptPayload(
        String confirmationId,
        String sessionId,
        String installTool,
        String installTarget,
        String commandPreview,
        String title,
        String description,
        List<ConfirmationChoice> choices,
        Instant expiresAt
) {

    public record ConfirmationChoice(String value, String label, String style) {}
}
