package com.skillforge.server.service.event;

import com.skillforge.core.model.Message;

/**
 * CHANNEL-MIDTURN-PROGRESS: published by
 * {@code ChatWebSocketHandler.messageAppended} for every per-turn assistant
 * message the engine broadcasts (engine hook {@code broadcaster.messageAppended}
 * at {@code AgentLoopEngine:~1139}).
 *
 * <p>Carries the full assistant {@link Message} so a downstream listener can
 * extract the narration text and (for channel-bound sessions only) push a
 * mid-turn progress update to the channel. The WS broadcast is unchanged; this
 * event is an ADDITIVE side-channel — non-channel sessions ignore it entirely.
 *
 * <p>Only assistant-role messages are published (a cheap guard at the publish
 * site keeps non-assistant turns off the event bus). Listeners must still treat
 * the message defensively.
 *
 * <p>Thinking/reasoning is a SEPARATE broadcaster channel
 * ({@code broadcaster.reasoningDelta}) and never flows through
 * {@code messageAppended}, so it is naturally excluded from this event.
 */
public record AssistantTurnAppendedEvent(
        String sessionId,
        String traceId,
        Message message
) {
}
