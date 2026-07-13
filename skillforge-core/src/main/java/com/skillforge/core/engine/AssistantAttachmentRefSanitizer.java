package com.skillforge.core.engine;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure provider-bound conversion of assistant attachment refs to text placeholders. */
public final class AssistantAttachmentRefSanitizer {

    private AssistantAttachmentRefSanitizer() {
    }

    public static List<Message> sanitize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        List<Message> copy = null;
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            Message message = messages.get(messageIndex);
            if (message == null || message.getRole() != Message.Role.ASSISTANT
                    || !(message.getContent() instanceof List<?> blocks)) {
                continue;
            }
            List<Object> sanitizedBlocks = null;
            for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                ContentBlock placeholder = placeholderFor(blocks.get(blockIndex));
                if (placeholder == null) continue;
                if (sanitizedBlocks == null) sanitizedBlocks = new ArrayList<>(blocks);
                sanitizedBlocks.set(blockIndex, placeholder);
            }
            if (sanitizedBlocks == null) continue;
            Message sanitizedMessage = new Message();
            sanitizedMessage.setRole(message.getRole());
            sanitizedMessage.setReasoningContent(message.getReasoningContent());
            sanitizedMessage.setContent(sanitizedBlocks);
            if (copy == null) copy = new ArrayList<>(messages);
            copy.set(messageIndex, sanitizedMessage);
        }
        return copy != null ? copy : messages;
    }

    private static ContentBlock placeholderFor(Object value) {
        String text = placeholderText(value);
        return text != null ? ContentBlock.text(text) : null;
    }

    /** Returns an assistant-history placeholder for one ref block, or null for non-ref values. */
    public static String placeholderText(Object value) {
        if (value instanceof ContentBlock block) {
            String label = labelFor(block.getType());
            return label != null ? placeholder(label, block.getFilename(), block.getAttachmentId()) : null;
        }
        if (value instanceof Map<?, ?> map) {
            String type = map.get("type") != null ? map.get("type").toString() : null;
            String label = labelFor(type);
            if (label == null) return null;
            Object id = map.get("attachment_id") != null ? map.get("attachment_id") : map.get("attachmentId");
            return placeholder(label, map.get("filename"), id);
        }
        return null;
    }

    private static String labelFor(String type) {
        if ("image_ref".equals(type)) return "image";
        if ("pdf_ref".equals(type)) return "PDF";
        if ("word_ref".equals(type)) return "Word document";
        if ("excel_ref".equals(type)) return "Excel workbook";
        if ("csv_ref".equals(type)) return "CSV";
        return null;
    }

    private static String placeholder(String label, Object filename, Object attachmentId) {
        Object display = filename != null && !filename.toString().isBlank() ? filename : attachmentId;
        return display == null || display.toString().isBlank()
                ? "[Previously delivered " + label + "]"
                : "[Previously delivered " + label + ": " + display + "]";
    }
}
