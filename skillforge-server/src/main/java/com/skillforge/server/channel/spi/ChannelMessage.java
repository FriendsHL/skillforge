package com.skillforge.server.channel.spi;

import java.time.Instant;
import java.util.Map;

/**
 * Platform-agnostic normalized inbound message.
 */
public record ChannelMessage(
        String platform,
        /** Platform-side conversation ID (feishu open_chat_id / telegram chat.id). */
        String conversationId,
        /** Platform-side sender ID (open_id / telegram user_id). */
        String platformUserId,
        /** Idempotency key, persisted to t_channel_message_dedup. */
        String platformMessageId,
        MessageType type,
        /** Plain text (@mentions stripped for AT_BOT). null for non-text. */
        String text,
        /** Attachment URL (image/file/voice); null for TEXT. */
        String attachmentUrl,
        Instant timestamp,
        /** Platform raw fields pass-through, not part of generic routing. */
        Map<String, Object> rawFields
) {
    public enum MessageType {
        TEXT,
        AT_BOT,
        IMAGE,
        FILE,
        VOICE,
        UNSUPPORTED
    }
}
