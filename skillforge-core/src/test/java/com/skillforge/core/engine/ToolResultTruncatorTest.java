package com.skillforge.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultTruncatorTest {

    private static final int MAX_CHARS = 40_000;
    private static final int HEAD_LEN_DEFAULT = 32_000; // 80%
    private static final int TAIL_LEN_DEFAULT = 8_000;  // 20%
    private static final int HEAD_LEN_IMPORTANT_TAIL = 28_000; // 70%
    private static final int TAIL_LEN_IMPORTANT_TAIL = 12_000; // 30%

    @Test
    @DisplayName("短文本不应被截断")
    void truncate_shortText_returnsOriginal() {
        String input = "hello skillforge";

        String output = ToolResultTruncator.truncate(input);

        assertEquals(input, output);
    }

    @Test
    @DisplayName("空输入返回 null")
    void truncate_null_returnsNull() {
        assertNull(ToolResultTruncator.truncate(null));
    }

    @Test
    @DisplayName("普通长文本按 80/20 头尾比例截断")
    void truncate_longTextWithoutImportantTail_usesDefaultRatio() {
        String input = "a".repeat(50_000);

        String output = ToolResultTruncator.truncate(input);

        assertTrue(output.contains("[... 10000 characters truncated ...]"));
        assertTrue(output.startsWith(input.substring(0, HEAD_LEN_DEFAULT)));
        assertTrue(output.endsWith(input.substring(input.length() - TAIL_LEN_DEFAULT)));
    }

    @Test
    @DisplayName("尾部含错误信号时按 70/30 保留更多尾部")
    void truncate_longTextWithImportantTail_keepsMoreTail() {
        String input = "b".repeat(MAX_CHARS + 5_000) + " fatal error happened";

        String output = ToolResultTruncator.truncate(input);
        int truncatedChars = input.length() - MAX_CHARS;

        assertTrue(output.contains("[... " + truncatedChars + " characters truncated ...]"));
        assertTrue(output.startsWith(input.substring(0, HEAD_LEN_IMPORTANT_TAIL)));
        assertTrue(output.endsWith(input.substring(input.length() - TAIL_LEN_IMPORTANT_TAIL)));
    }
}
