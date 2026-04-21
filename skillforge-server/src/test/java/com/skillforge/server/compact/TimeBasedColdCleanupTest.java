package com.skillforge.server.compact;

import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.TimeBasedColdCleanup;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TimeBasedColdCleanupTest {

    private final CompactableToolRegistry registry = new CompactableToolRegistry();

    // ---- builders ----

    private Message assistantToolUse(String toolId, String name) {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(List.of(ContentBlock.toolUse(toolId, name, Map.of())));
        return m;
    }

    private Message toolResult(String toolUseId, String content) {
        Message m = new Message();
        m.setRole(Message.Role.USER);
        m.setContent(new ArrayList<>(List.of(ContentBlock.toolResult(toolUseId, content, false))));
        return m;
    }

    private Message filler(String text) {
        return Message.user(text);
    }

    // ---- tests ----

    @Test
    void noop_when_not_idle_enough() {
        List<Message> msgs = buildConversationWithToolResults(10);
        int cleared = TimeBasedColdCleanup.apply(msgs, 100, 300, 5, registry);
        assertThat(cleared).isZero();
        // All content should be unchanged
        assertNoClearedPlaceholders(msgs);
    }

    @Test
    void noop_when_idle_seconds_unknown() {
        List<Message> msgs = buildConversationWithToolResults(10);
        int cleared = TimeBasedColdCleanup.apply(msgs, -1, 300, 5, registry);
        assertThat(cleared).isZero();
    }

    @Test
    void noop_when_fewer_results_than_keep_recent() {
        List<Message> msgs = buildConversationWithToolResults(3);
        // keepRecent=5 but only 3 compactable results → nothing to clear
        int cleared = TimeBasedColdCleanup.apply(msgs, 600, 300, 5, registry);
        assertThat(cleared).isZero();
    }

    @Test
    void clears_old_results_beyond_keep_recent() {
        // 8 tool results, keepRecent=3 → should clear 5 oldest
        List<Message> msgs = buildConversationWithToolResults(8);
        int cleared = TimeBasedColdCleanup.apply(msgs, 600, 300, 3, registry);
        assertThat(cleared).isEqualTo(5);

        // Count placeholders
        int placeholderCount = countPlaceholders(msgs);
        assertThat(placeholderCount).isEqualTo(5);

        // Most recent 3 should still have original content
        List<String> remainingContents = collectToolResultContents(msgs);
        long originalCount = remainingContents.stream()
                .filter(c -> !TimeBasedColdCleanup.CLEARED_PLACEHOLDER.equals(c))
                .count();
        assertThat(originalCount).isEqualTo(3);
    }

    @Test
    void non_compactable_tools_are_untouched() {
        List<Message> msgs = new ArrayList<>();
        // 6 Memory tool results (non-compactable) + 2 Bash tool results (compactable)
        for (int i = 0; i < 6; i++) {
            msgs.add(assistantToolUse("m" + i, "Memory"));
            msgs.add(toolResult("m" + i, "memory content " + i));
        }
        for (int i = 0; i < 2; i++) {
            msgs.add(assistantToolUse("b" + i, "Bash"));
            msgs.add(toolResult("b" + i, "bash output " + i));
        }
        // Add tail fillers to push everything out of protection window
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        int cleared = TimeBasedColdCleanup.apply(msgs, 600, 300, 0, registry);

        // Only the 2 Bash results should be cleared, Memory untouched
        assertThat(cleared).isEqualTo(2);
        // Verify all Memory results still have original content
        for (int i = 0; i < 6; i++) {
            ContentBlock memResult = findToolResultForId(msgs, "m" + i);
            assertThat(memResult).isNotNull();
            assertThat(memResult.getContent()).isEqualTo("memory content " + i);
        }
    }

    @Test
    void protection_window_is_respected() {
        // Put compactable tool results inside the last 5 messages — they should not be cleared
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        // These 2 tool pairs will end up in the last 5 messages (protection window)
        msgs.add(assistantToolUse("t1", "Bash"));
        msgs.add(toolResult("t1", "bash output 1"));
        msgs.add(assistantToolUse("t2", "Bash"));
        msgs.add(toolResult("t2", "bash output 2"));
        // Only 1 filler after → total = 8, protection = last 5 = indices 3-7
        msgs.add(filler("tail"));

        int cleared = TimeBasedColdCleanup.apply(msgs, 600, 300, 0, registry);
        assertThat(cleared).isZero();
    }

    @Test
    void already_cleared_results_are_skipped() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Bash"));
        msgs.add(toolResult("t1", TimeBasedColdCleanup.CLEARED_PLACEHOLDER)); // already cleared
        msgs.add(assistantToolUse("t2", "Bash"));
        msgs.add(toolResult("t2", "bash output"));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        int cleared = TimeBasedColdCleanup.apply(msgs, 600, 300, 0, registry);
        // Only t2 should be cleared; t1 is already a placeholder
        assertThat(cleared).isEqualTo(1);
    }

    @Test
    void null_and_empty_content_are_skipped() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Bash"));
        msgs.add(toolResult("t1", null));
        msgs.add(assistantToolUse("t2", "Bash"));
        msgs.add(toolResult("t2", ""));
        msgs.add(assistantToolUse("t3", "Bash"));
        msgs.add(toolResult("t3", "real output"));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        int cleared = TimeBasedColdCleanup.apply(msgs, 600, 300, 0, registry);
        assertThat(cleared).isEqualTo(1); // only t3
    }

    @Test
    void keep_recent_zero_clears_all_eligible() {
        List<Message> msgs = buildConversationWithToolResults(5);
        int cleared = TimeBasedColdCleanup.apply(msgs, 600, 300, 0, registry);
        assertThat(cleared).isEqualTo(5);
    }

    @Test
    void custom_registry_is_respected() {
        // Registry with only "Grep" — Bash results should not be cleared
        CompactableToolRegistry custom = new CompactableToolRegistry(Set.of("Grep"));
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Bash"));
        msgs.add(toolResult("t1", "bash output"));
        msgs.add(assistantToolUse("t2", "Grep"));
        msgs.add(toolResult("t2", "grep output"));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        int cleared = TimeBasedColdCleanup.apply(msgs, 600, 300, 0, custom);
        assertThat(cleared).isEqualTo(1); // only Grep result
        assertThat(findToolResultForId(msgs, "t1").getContent()).isEqualTo("bash output");
        assertThat(findToolResultForId(msgs, "t2").getContent())
                .isEqualTo(TimeBasedColdCleanup.CLEARED_PLACEHOLDER);
    }

    @Test
    void null_messages_is_safe() {
        assertThat(TimeBasedColdCleanup.apply(null, 600, 300, 5, registry)).isZero();
    }

    @Test
    void empty_messages_is_safe() {
        assertThat(TimeBasedColdCleanup.apply(new ArrayList<>(), 600, 300, 5, registry)).isZero();
    }

    // ---- helpers ----

    /**
     * Build a conversation with N compactable tool call pairs (Bash),
     * with enough tail fillers to push all pairs outside the protection window.
     */
    private List<Message> buildConversationWithToolResults(int toolCallCount) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(filler("start"));
        for (int i = 0; i < toolCallCount; i++) {
            msgs.add(assistantToolUse("t" + i, "Bash"));
            msgs.add(toolResult("t" + i, "output line " + i));
        }
        // 6 fillers to ensure protection window doesn't cover tool results
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));
        return msgs;
    }

    private void assertNoClearedPlaceholders(List<Message> msgs) {
        assertThat(countPlaceholders(msgs)).isZero();
    }

    private int countPlaceholders(List<Message> msgs) {
        int count = 0;
        for (Message m : msgs) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object o : blocks) {
                if (o instanceof ContentBlock cb && "tool_result".equals(cb.getType())
                        && TimeBasedColdCleanup.CLEARED_PLACEHOLDER.equals(cb.getContent())) {
                    count++;
                }
            }
        }
        return count;
    }

    private List<String> collectToolResultContents(List<Message> msgs) {
        List<String> contents = new ArrayList<>();
        for (Message m : msgs) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object o : blocks) {
                if (o instanceof ContentBlock cb && "tool_result".equals(cb.getType())) {
                    contents.add(cb.getContent());
                }
            }
        }
        return contents;
    }

    private ContentBlock findToolResultForId(List<Message> msgs, String toolUseId) {
        for (Message m : msgs) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object o : blocks) {
                if (o instanceof ContentBlock cb && "tool_result".equals(cb.getType())
                        && toolUseId.equals(cb.getToolUseId())) {
                    return cb;
                }
            }
        }
        return null;
    }
}
