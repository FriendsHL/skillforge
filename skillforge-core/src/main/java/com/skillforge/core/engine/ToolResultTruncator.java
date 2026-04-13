package com.skillforge.core.engine;

/**
 * Truncates tool result output to prevent excessive context consumption.
 * Uses smart head/tail split, preserving more tail when it contains
 * error indicators or summary content.
 */
public class ToolResultTruncator {

    private static final int MAX_CHARS = 40000;

    public static String truncate(String output) {
        if (output == null || output.length() <= MAX_CHARS) return output;

        boolean importantTail = hasImportantTail(output);
        int headRatio = importantTail ? 70 : 80;
        int headLen = MAX_CHARS * headRatio / 100;
        int tailLen = MAX_CHARS - headLen;
        int truncated = output.length() - MAX_CHARS;

        return output.substring(0, headLen)
                + "\n\n[... " + truncated + " characters truncated ...]\n\n"
                + output.substring(output.length() - tailLen);
    }

    private static boolean hasImportantTail(String text) {
        String tail = text.substring(Math.max(0, text.length() - 2000)).toLowerCase();
        return tail.matches("(?s).*\\b(error|exception|failed|fatal|traceback|panic)\\b.*")
                || tail.matches("(?s).*\\}\\s*$")
                || tail.matches("(?s).*\\b(total|summary|result|conclusion)\\b.*");
    }
}
