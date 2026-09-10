package com.skillforge.core.engine;

import com.skillforge.core.model.Message;

import java.util.ArrayList;
import java.util.List;

/** Provider-only coalescing for exact USER rows drained during the current durable loop. */
final class ProviderQueuedUserMaterializer {

    private static final String MESSAGE_BOUNDARY = "\n\n---\n\n";

    private ProviderQueuedUserMaterializer() {
    }

    static List<Message> materialize(LoopContext context, List<Message> messages) {
        if (context == null || messages == null || messages.size() < 2) {
            return messages;
        }
        List<Message> out = null;
        int index = 0;
        while (index < messages.size()) {
            Message first = messages.get(index);
            if (!isEligible(context, first)
                    || index + 1 >= messages.size()
                    || !isEligible(context, messages.get(index + 1))) {
                if (out != null) out.add(first);
                index++;
                continue;
            }

            if (out == null) {
                out = new ArrayList<>(messages.size());
                out.addAll(messages.subList(0, index));
            }
            StringBuilder merged = new StringBuilder((String) first.getContent());
            int next = index + 1;
            while (next < messages.size() && isEligible(context, messages.get(next))) {
                merged.append(MESSAGE_BOUNDARY)
                        .append((String) messages.get(next).getContent());
                next++;
            }
            out.add(Message.user(merged.toString()));
            index = next;
        }
        return out != null ? List.copyOf(out) : messages;
    }

    private static boolean isEligible(LoopContext context, Message message) {
        return context.isProviderMergeEligibleUser(message)
                && message.getRole() == Message.Role.USER
                && message.getReasoningContent() == null
                && message.getContent() instanceof String;
    }
}
