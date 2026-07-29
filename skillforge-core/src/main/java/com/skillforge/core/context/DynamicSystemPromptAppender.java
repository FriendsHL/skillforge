package com.skillforge.core.context;

/**
 * Byte-stable append operations for runtime system-prompt fragments.
 *
 * <p>Both the agent loop and read-only context observation use these methods so
 * separators, headings, and value sanitization cannot drift.
 */
public final class DynamicSystemPromptAppender {

    private DynamicSystemPromptAppender() {
    }

    public static String appendSessionContext(
            StringBuilder target, Long userId, String sessionId) {
        if (userId == null && sessionId == null) {
            return "";
        }
        int start = target.length();
        if (target.length() > 0) target.append("\n\n");
        target.append("## Session Context\n");
        if (userId != null) {
            target.append("- userId: ")
                    .append(sanitizePromptValue(String.valueOf(userId))).append("\n");
        }
        if (sessionId != null) {
            target.append("- sessionId: ")
                    .append(sanitizePromptValue(sessionId)).append("\n");
        }
        return target.substring(start);
    }

    public static String appendUserMemories(StringBuilder target, String memories) {
        if (memories == null || memories.isBlank()) {
            return "";
        }
        int start = target.length();
        if (target.length() > 0) target.append("\n\n");
        target.append("## User Memories\n\n").append(memories);
        return target.substring(start);
    }

    public static String appendRuntimeContext(
            StringBuilder target, String heading, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int start = target.length();
        if (target.length() > 0) target.append("\n\n");
        target.append("## ")
                .append(sanitizePromptValue(heading != null ? heading : "Runtime Context"))
                .append("\n\n")
                .append(content.strip());
        return target.substring(start);
    }

    private static String sanitizePromptValue(String value) {
        return value == null ? null : value.replaceAll("[\r\n\t]", " ").trim();
    }
}
