package com.skillforge.core.engine.durability;

import com.skillforge.core.model.Message;

import java.util.Objects;

/** Deep, immutable logical snapshot of a mutable core Message. */
public record MessageSnapshot(
        Message.Role role,
        FrozenJson content,
        String reasoningContent) {

    public MessageSnapshot {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static MessageSnapshot capture(Message message) {
        Objects.requireNonNull(message, "message");
        return new MessageSnapshot(
                Objects.requireNonNull(message.getRole(), "message.role"),
                FrozenJson.capture(message.getContent()),
                message.getReasoningContent());
    }

    /** Materializes a fresh Message; callers cannot mutate this snapshot through it. */
    public Message toMessage() {
        Message message = new Message();
        message.setRole(role);
        message.setContent(content.toJavaValue());
        message.setReasoningContent(reasoningContent);
        return message;
    }
}
