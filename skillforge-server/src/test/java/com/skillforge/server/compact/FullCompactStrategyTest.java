package com.skillforge.server.compact;

import com.skillforge.core.compact.CompactResult;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FullCompactStrategyTest {

    private final FullCompactStrategy strategy = new FullCompactStrategy();

    private final LlmProvider mockProvider = new LlmProvider() {
        @Override public String getName() { return "mock"; }
        @Override public LlmResponse chat(LlmRequest request) {
            LlmResponse r = new LlmResponse();
            r.setContent("SUMMARY");
            return r;
        }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { }
    };

    private Message assistantToolUse(String toolId, String name) {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(List.of(ContentBlock.toolUse(toolId, name, Map.of("k", "v"))));
        return m;
    }

    private Message toolResult(String id) {
        Message m = new Message();
        m.setRole(Message.Role.USER);
        m.setContent(List.of(ContentBlock.toolResult(id, "ok", false)));
        return m;
    }

    private Message assistantText(String text) {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        m.setContent(text);
        return m;
    }

    /**
     * Build a scenario where the initial right-edge (size-YOUNG_GEN_KEEP) lands squarely
     * between a tool_use (in window) and its tool_result (in young-gen).
     * Verify both that the pair is NOT split AND that some boundary-shift actually occurred.
     */
    @Test
    void window_boundary_shifts_when_initial_position_splits_pair() {
        // size needs to land the initial edge INSIDE a pair.
        // Let YOUNG_GEN_KEEP = 20. Layout:
        //   idx 0 .. 23   : 24 filler user messages
        //   idx 24        : assistant tool_use (t1)   <-- in window [0, 25) initially
        //   idx 25        : user tool_result (t1)    <-- in young-gen [25, end)
        //   idx 26 .. 44  : 19 more filler messages
        // size = 45, initial rightEdge = 45-20 = 25 → SPLITS the pair.
        // After shift, rightEdge should move to 24 or earlier (leaving both tool_use
        // and tool_result in young-gen) OR grow young-gen.
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 24; i++) msgs.add(Message.user("pad " + i));
        msgs.add(assistantToolUse("t1", "Grep"));   // index 24
        msgs.add(toolResult("t1"));                  // index 25
        for (int i = 0; i < 19; i++) msgs.add(Message.user("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000, mockProvider, null);

        // Pair must be kept together — both in window OR both in young-gen, never split.
        int toolUseIdx = -1;
        int toolResultIdx = -1;
        for (int i = 0; i < r.getMessages().size(); i++) {
            Message m = r.getMessages().get(i);
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb) {
                        if ("tool_use".equals(cb.getType()) && "t1".equals(cb.getId())) toolUseIdx = i;
                        if ("tool_result".equals(cb.getType()) && "t1".equals(cb.getToolUseId())) toolResultIdx = i;
                    }
                }
            }
        }
        // Both must be present in the result (they ended up in young-gen after the shift)
        assertThat(toolUseIdx).isGreaterThanOrEqualTo(0);
        assertThat(toolResultIdx).isGreaterThanOrEqualTo(0);
        // And they must be adjacent (pair preserved in order)
        assertThat(toolResultIdx - toolUseIdx).isEqualTo(1);
        // The boundary must have shifted — if it hadn't, the initial edge at 25 would have
        // left tool_use in the summary and tool_result in young-gen.
        // The summary should be present as the first message.
        assertThat(r.getStrategiesApplied()).containsExactly("llm-summary");
        // Result size must be strictly less than original: proof that some summarization happened.
        assertThat(r.getMessages().size()).isLessThan(msgs.size());
    }

    @Test
    void tautological_sanity_pair_preserved_when_pair_near_edge() {
        // Retained from the original test: same shape but tighter assertion.
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 24; i++) msgs.add(Message.user("pad " + i));
        msgs.add(assistantToolUse("t1", "Grep"));
        msgs.add(toolResult("t1"));
        for (int i = 0; i < 19; i++) msgs.add(Message.user("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000, mockProvider, null);

        // Pair must stay together on the same side of the boundary.
        boolean hasToolUse = false;
        boolean hasToolResult = false;
        for (Message m : r.getMessages()) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb) {
                        if ("tool_use".equals(cb.getType()) && "t1".equals(cb.getId())) hasToolUse = true;
                        if ("tool_result".equals(cb.getType()) && "t1".equals(cb.getToolUseId())) hasToolResult = true;
                    }
                }
            }
        }
        assertThat(hasToolUse).isEqualTo(hasToolResult);
    }

    @Test
    void merges_summary_into_first_user_when_young_gen_starts_with_user() {
        // All filler messages are role=user, so young-gen's first message is user.
        // With fix #7, we must NOT prepend a separate user message (that would be two
        // consecutive user messages). We should merge the summary into the first user.
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 30; i++) msgs.add(Message.user("msg " + i));

        CompactResult r = strategy.apply(msgs, 32000, mockProvider, null);

        // Size must be 20 (not 21): young-gen of 20, first merged with summary.
        assertThat(r.getMessages()).hasSize(20);
        Message first = r.getMessages().get(0);
        assertThat(first.getRole()).isEqualTo(Message.Role.USER);
        assertThat(first.getContent()).isInstanceOf(String.class);
        String content = (String) first.getContent();
        assertThat(content).startsWith("[Context summary from ");
        assertThat(content).contains("SUMMARY");
        // Contains the original first young-gen message content too
        assertThat(content).contains("msg 10");
        // Result size = 20 (merged, not 21)
        assertThat(r.getStrategiesApplied()).containsExactly("llm-summary");
    }

    @Test
    void emits_standalone_user_summary_when_young_gen_starts_with_assistant() {
        // First half filler users; at split, young-gen first message is assistant text.
        // Layout: 10 users + 20 assistant texts. With YOUNG_GEN_KEEP=20, rightEdge=10 (user).
        // Young-gen = [10, 30) which starts with assistant.
        // So the summary should be INSERTED as a standalone user message, not merged.
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) msgs.add(Message.user("u " + i));
        for (int i = 0; i < 20; i++) msgs.add(assistantText("a " + i));

        CompactResult r = strategy.apply(msgs, 32000, mockProvider, null);

        // Size: 1 summary + 20 young-gen = 21
        assertThat(r.getMessages()).hasSize(21);
        Message first = r.getMessages().get(0);
        assertThat(first.getRole()).isEqualTo(Message.Role.USER);
        assertThat((String) first.getContent()).startsWith("[Context summary from ");
        // Second message is the first of young-gen, unchanged
        assertThat(r.getMessages().get(1).getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(((String) r.getMessages().get(1).getContent())).isEqualTo("a 0");
    }

    /**
     * #6 test: the boundary check must also work when blocks are raw Map<String,Object>
     * (the form Jackson produces when reading messagesJson back from the DB).
     */
    @Test
    void boundary_check_handles_map_shaped_blocks() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 24; i++) msgs.add(Message.user("pad " + i));
        // tool_use + tool_result as RAW MAPS (not ContentBlock)
        Message assistantWithMapToolUse = new Message();
        assistantWithMapToolUse.setRole(Message.Role.ASSISTANT);
        Map<String, Object> mapToolUse = new LinkedHashMap<>();
        mapToolUse.put("type", "tool_use");
        mapToolUse.put("id", "tm1");
        mapToolUse.put("name", "Grep");
        mapToolUse.put("input", new HashMap<>());
        assistantWithMapToolUse.setContent(List.of(mapToolUse));
        msgs.add(assistantWithMapToolUse);  // index 24

        Message userWithMapToolResult = new Message();
        userWithMapToolResult.setRole(Message.Role.USER);
        Map<String, Object> mapToolResult = new LinkedHashMap<>();
        mapToolResult.put("type", "tool_result");
        mapToolResult.put("tool_use_id", "tm1");
        mapToolResult.put("content", "map ok");
        userWithMapToolResult.setContent(List.of(mapToolResult));
        msgs.add(userWithMapToolResult);  // index 25

        for (int i = 0; i < 19; i++) msgs.add(Message.user("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000, mockProvider, null);

        // Map-shaped pair must also be preserved — if isBoundarySafe silently treated
        // the map pair as "no open pairs" the boundary would have split them.
        int toolUseIdx = -1;
        int toolResultIdx = -1;
        for (int i = 0; i < r.getMessages().size(); i++) {
            Message m = r.getMessages().get(i);
            if (m.getContent() instanceof List<?> blocks && !blocks.isEmpty()) {
                Object first = blocks.get(0);
                if (first instanceof Map<?, ?> mm) {
                    if ("tool_use".equals(mm.get("type")) && "tm1".equals(mm.get("id"))) toolUseIdx = i;
                    if ("tool_result".equals(mm.get("type")) && "tm1".equals(mm.get("tool_use_id"))) toolResultIdx = i;
                }
            }
        }
        // Both must exist and be adjacent
        assertThat(toolUseIdx).isGreaterThanOrEqualTo(0);
        assertThat(toolResultIdx).isGreaterThanOrEqualTo(0);
        assertThat(toolResultIdx - toolUseIdx).isEqualTo(1);
    }
}
