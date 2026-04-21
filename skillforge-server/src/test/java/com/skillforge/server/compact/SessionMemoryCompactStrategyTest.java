package com.skillforge.server.compact;

import com.skillforge.core.compact.CompactResult;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.SessionMemoryCompactStrategy;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionMemoryCompactStrategyTest {

    private final SessionMemoryCompactStrategy strategy = new SessionMemoryCompactStrategy();
    private final FullCompactStrategy fullStrategy = new FullCompactStrategy();

    // ---- helpers ----

    private Message filler(String text) {
        return Message.user(text);
    }

    private Message assistantText(String text) {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(text);
        return m;
    }

    private Message assistantToolUse(String toolId, String name) {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(List.of(ContentBlock.toolUse(toolId, name, Map.of())));
        return m;
    }

    private Message toolResult(String toolUseId, String content) {
        Message m = new Message();
        m.setRole(Message.Role.USER);
        m.setContent(List.of(ContentBlock.toolResult(toolUseId, content, false)));
        return m;
    }

    /**
     * Build a conversation large enough to have a non-trivial prep
     * (> YOUNG_GEN_KEEP messages) with proper tool_use/tool_result pairing.
     */
    private List<Message> buildLargeConversation(int totalPairs) {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < totalPairs; i++) {
            msgs.add(filler("user question " + i));
            msgs.add(assistantToolUse("t" + i, "Bash"));
            msgs.add(toolResult("t" + i, "result " + i));
            msgs.add(assistantText("analysis " + i));
        }
        return msgs;
    }

    private FullCompactStrategy.PreparedCompact prepareFrom(List<Message> messages) {
        return fullStrategy.prepareCompact(messages, 128000);
    }

    // ---- tests ----

    @Test
    void tryCompact_withValidMemory_returnsCompactResult() {
        List<Message> msgs = buildLargeConversation(15); // 60 messages
        FullCompactStrategy.PreparedCompact prep = prepareFrom(msgs);
        assertThat(prep).isNotNull();

        String memory = "User prefers concise answers. Working on a REST API project.";

        CompactResult result = strategy.tryCompact(prep, memory, 0, 0);

        assertThat(result).isNotNull();
        assertThat(result.getStrategiesApplied()).containsExactly("session-memory");
        assertThat(result.getAfterTokens()).isLessThan(result.getBeforeTokens());
        assertThat(result.getMessages().size()).isLessThan(msgs.size());
        // Verify the summary is in the result
        boolean hasSummary = false;
        for (Message m : result.getMessages()) {
            String text = extractText(m);
            if (text != null && text.contains("Session memory summary")) {
                hasSummary = true;
                assertThat(text).contains("User prefers concise answers");
                break;
            }
        }
        assertThat(hasSummary).isTrue();
    }

    @Test
    void tryCompact_withNullMemory_returnsNull() {
        List<Message> msgs = buildLargeConversation(15);
        FullCompactStrategy.PreparedCompact prep = prepareFrom(msgs);

        assertThat(strategy.tryCompact(prep, null, 0, 0)).isNull();
        assertThat(strategy.tryCompact(prep, "", 0, 0)).isNull();
        assertThat(strategy.tryCompact(prep, "   ", 0, 0)).isNull();
    }

    @Test
    void tryCompact_withNullPrep_returnsNull() {
        assertThat(strategy.tryCompact(null, "some memory", 0, 0)).isNull();
    }

    @Test
    void tryCompact_exceedsMaxTokens_returnsNull() {
        List<Message> msgs = buildLargeConversation(15);
        FullCompactStrategy.PreparedCompact prep = prepareFrom(msgs);

        // Set absurdly low max tokens — should exceed
        CompactResult result = strategy.tryCompact(prep, "some memory", 10, 0);
        assertThat(result).isNull();
    }

    @Test
    void tryCompact_insufficientYoungGen_returnsNull() {
        // Build a conversation that's just barely above YOUNG_GEN_KEEP
        // so young-gen is small
        List<Message> msgs = buildLargeConversation(7); // 28 messages, young-gen = 8
        FullCompactStrategy.PreparedCompact prep = prepareFrom(msgs);
        if (prep == null) return; // too short for prep

        // Require 100 min messages — more than young-gen has
        CompactResult result = strategy.tryCompact(prep, "some memory", 0, 100);
        assertThat(result).isNull();
    }

    @Test
    void tryCompact_mergesSummaryIntoFirstUserMessage() {
        List<Message> msgs = buildLargeConversation(15);
        FullCompactStrategy.PreparedCompact prep = prepareFrom(msgs);
        assertThat(prep).isNotNull();
        // The young-gen likely starts with a user message
        if (prep.youngGen().get(0).getRole() == Message.Role.USER) {
            CompactResult result = strategy.tryCompact(prep, "Memory content here", 0, 0);
            assertThat(result).isNotNull();
            // First message should contain both the summary and the original user content
            String firstText = extractText(result.getMessages().get(0));
            assertThat(firstText).contains("Session memory summary");
            assertThat(firstText).contains("Memory content here");
        }
    }

    @Test
    void tryCompact_strategiesApplied_containsSessionMemory() {
        List<Message> msgs = buildLargeConversation(15);
        FullCompactStrategy.PreparedCompact prep = prepareFrom(msgs);

        CompactResult result = strategy.tryCompact(prep, "memory text", 0, 0);
        assertThat(result).isNotNull();
        assertThat(result.getStrategiesApplied()).containsExactly("session-memory");
    }

    @Test
    void tryCompact_tokenCounts_are_correct() {
        List<Message> msgs = buildLargeConversation(15);
        FullCompactStrategy.PreparedCompact prep = prepareFrom(msgs);

        CompactResult result = strategy.tryCompact(prep, "memory text", 0, 0);
        assertThat(result).isNotNull();
        assertThat(result.getBeforeTokens()).isEqualTo(prep.beforeTokens());
        assertThat(result.getAfterTokens()).isGreaterThan(0);
        assertThat(result.getTokensReclaimed()).isGreaterThan(0);
    }

    // ---- text extraction helper ----

    private String extractText(Message m) {
        Object content = m.getContent();
        if (content instanceof String s) return s;
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object o : blocks) {
                if (o instanceof ContentBlock cb && "text".equals(cb.getType()) && cb.getText() != null) {
                    sb.append(cb.getText());
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }
}
