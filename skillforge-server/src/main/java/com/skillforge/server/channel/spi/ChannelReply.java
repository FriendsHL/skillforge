package com.skillforge.server.channel.spi;

public record ChannelReply(
        String inboundMessageId,
        String platform,
        String conversationId,
        String markdownText,
        boolean useRichFormat,
        /** null = normal message; non-null = reply-to target id. */
        String replyToMessageId
) {}
