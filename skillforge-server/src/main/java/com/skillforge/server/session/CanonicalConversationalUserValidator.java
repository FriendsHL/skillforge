package com.skillforge.server.session;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import java.util.List;
import java.util.Map;

/** Shared shape guard for canonical conversational USER messages. */
final class CanonicalConversationalUserValidator {

    private CanonicalConversationalUserValidator() {
    }

    static void requireValid(Message message) {
        if (message == null
                || message.getRole() != Message.Role.USER
                || message.getContent() == null
                || message.getReasoningContent() != null) {
            throw new IllegalStateException();
        }
        if (message.getContent() instanceof List<?> blocks) {
            for (Object value : blocks) {
                String type = null;
                if (value instanceof ContentBlock block) {
                    type = block.getType();
                } else if (value instanceof Map<?, ?> block && block.get("type") != null) {
                    type = block.get("type").toString();
                }
                if ("tool_use".equals(type) || "tool_result".equals(type)) {
                    throw new IllegalStateException();
                }
            }
        }
    }
}
