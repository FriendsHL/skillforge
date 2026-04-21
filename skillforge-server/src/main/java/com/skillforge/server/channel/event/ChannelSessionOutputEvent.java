package com.skillforge.server.channel.event;

/**
 * Fired when an AgentLoop completes a turn for a channel-backed session.
 * Carries the per-turn inbound message id (not sessionId) so
 * t_channel_delivery.inbound_message_id stays unique per turn.
 */
public record ChannelSessionOutputEvent(
        String sessionId,
        String platformMessageId,
        String ackReactionId,   // nullable; reaction to remove before delivering reply
        String replyText
) {}
