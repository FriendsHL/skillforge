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
    void emits_standalone_summary_when_young_gen_starts_with_user_text() {
        // BUG-F-1: summary is ALWAYS a standalone user message, never merged into the
        // first young-gen entry. Layout matches Claude Code / OpenClaw — modern
        // providers accept consecutive user messages.
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 30; i++) msgs.add(Message.user("msg " + i));

        CompactResult r = strategy.apply(msgs, 32000, mockProvider, null);

        // Size: 1 summary + 20 young-gen = 21 (NOT 20 — no merge)
        assertThat(r.getMessages()).hasSize(21);

        Message first = r.getMessages().get(0);
        assertThat(first.getRole()).isEqualTo(Message.Role.USER);
        assertThat(first.getContent()).isInstanceOf(String.class);
        String content = (String) first.getContent();
        assertThat(content).startsWith("[Context summary from ");
        assertThat(content).contains("SUMMARY");
        // Summary string must NOT contain the first young-gen content (no merge)
        assertThat(content).doesNotContain("msg 10");
        // No legacy `\n\n---\n\n` separator anywhere — that was the merge marker
        assertThat(content).doesNotContain("\n\n---\n\n");

        // Second message is the first of young-gen, unchanged
        Message second = r.getMessages().get(1);
        assertThat(second.getRole()).isEqualTo(Message.Role.USER);
        assertThat(second.getContent()).isEqualTo("msg 10");

        assertThat(r.getStrategiesApplied()).containsExactly("llm-summary");
    }

    @Test
    void emits_standalone_user_summary_when_young_gen_starts_with_assistant() {
        // First half filler users; at split, young-gen first message is assistant text.
        // Layout: 10 users + 20 assistant texts. With YOUNG_GEN_KEEP=20, rightEdge=10 (user).
        // Young-gen = [10, 30) which starts with assistant.
        // BUG-F-1: summary is always a standalone user message (matches both code paths now).
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
     * BUG-F-1: when young-gen first entry is a tool_result-form user message, it MUST
     * NOT have the summary text-block prepended into its blocks list. The compacted
     * layout must be [Message.user(summary), tool_result-form-user, ...] with the
     * tool_result message preserved byte-for-byte.
     *
     * <p>Constructs a young-gen-leading tool_result by hand-rolling the message list
     * and calling {@code applyPrepared} directly (bypasses boundary check, simulating
     * legacy session shapes).
     */
    @Test
    void preserves_tool_result_form_first_youngGen_unmodified() {
        List<Message> youngGen = new ArrayList<>();
        Message toolResultUser = new Message();
        toolResultUser.setRole(Message.Role.USER);
        toolResultUser.setContent(List.of(ContentBlock.toolResult("tx", "result-payload", false)));
        youngGen.add(toolResultUser);
        for (int i = 0; i < 19; i++) youngGen.add(assistantText("yg " + i));

        // window: any non-empty list
        List<Message> window = new ArrayList<>();
        for (int i = 0; i < 10; i++) window.add(Message.user("win " + i));

        FullCompactStrategy.PreparedCompact prep = new FullCompactStrategy.PreparedCompact(
                /*rightEdge=*/10, window, youngGen,
                /*beforeTokens=*/100, /*beforeCount=*/30, /*contextWindowTokens=*/32000);

        CompactResult r = strategy.applyPrepared(prep, mockProvider, null);
        assertThat(r).isNotNull();
        // 1 summary + 20 young-gen (1 tool_result + 19 assistantText)
        assertThat(r.getMessages()).hasSize(21);

        // [0] = summary user (String content)
        Message first = r.getMessages().get(0);
        assertThat(first.getRole()).isEqualTo(Message.Role.USER);
        assertThat(first.getContent()).isInstanceOf(String.class);
        assertThat((String) first.getContent()).startsWith("[Context summary from ");

        // [1] = original tool_result-form user, content list unchanged in length and shape
        Message second = r.getMessages().get(1);
        assertThat(second.getRole()).isEqualTo(Message.Role.USER);
        assertThat(second.getContent()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) second.getContent();
        assertThat(blocks).hasSize(1);  // no text block prepended
        assertThat(blocks.get(0)).isInstanceOf(ContentBlock.class);
        ContentBlock cb = (ContentBlock) blocks.get(0);
        assertThat(cb.getType()).isEqualTo("tool_result");
        assertThat(cb.getToolUseId()).isEqualTo("tx");
        assertThat(cb.getContent()).isEqualTo("result-payload");
    }

    // ===================================================================================
    // COMPACT-IDEMPOTENCY-FIX ③: summary input window guard / map-reduce chunking.
    // ===================================================================================

    /**
     * A counting provider records how many chat() calls it received and the size of each
     * input so we can assert single-shot vs map-reduce behaviour.
     */
    private static final class CountingProvider implements LlmProvider {
        int calls = 0;
        int maxInputChars = 0;
        @Override public String getName() { return "counting"; }
        @Override public LlmResponse chat(LlmRequest request) {
            calls++;
            String text = request.getMessages().get(0).getContent().toString();
            maxInputChars = Math.max(maxInputChars, text.length());
            LlmResponse r = new LlmResponse();
            r.setContent("PARTIAL-" + calls);
            return r;
        }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { }
    }

    @Test
    void summary_window_within_budget_is_single_shot() {
        CountingProvider p = new CountingProvider();
        // Small window text, very large context window → single call.
        String summary = strategy.summarizeWithWindowGuard(p, null, "a short window text", 128_000);
        assertThat(summary).isEqualTo("PARTIAL-1");
        assertThat(p.calls).isEqualTo(1);
    }

    @Test
    void summary_window_over_budget_is_chunked_map_reduce() {
        CountingProvider p = new CountingProvider();
        // Build a multi-line window large enough to exceed a tiny context window budget so the
        // map-reduce path fires. Each line is its own "message" line (serializeWindow shape).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            sb.append("[user] line ").append(i)
              .append(" with some filler content to grow the token count\n");
        }
        // Small context window → per-call budget (6000 − 4000 reserve = 2000) forces chunking.
        String summary = strategy.summarizeWithWindowGuard(p, null, sb.toString(), 6_000);

        // map-reduce: >= 2 map calls (#7 — if exactly one non-blank partial survives, the reduce
        // is skipped, so total calls can be 2 rather than 3).
        assertThat(p.calls).isGreaterThanOrEqualTo(2);
        assertThat(summary).isNotBlank();
        // No single call ever exceeded a sane bound — chunking actually split the input.
        assertThat(p.maxInputChars).isLessThan(sb.length());
    }

    @Test
    void summary_chunking_never_drops_to_empty_when_reduce_yields_text() {
        CountingProvider p = new CountingProvider();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("[assistant] reasoning step ").append(i).append("\n");
        String summary = strategy.summarizeWithWindowGuard(p, null, sb.toString(), 5_000);
        assertThat(summary).isNotBlank();
    }

    /**
     * #2: reduce recursion is depth-bounded. A pathological provider that echoes its input verbatim
     * means the concatenated chunk summaries never shrink below budget — without the depth cap this
     * would recurse forever / stack-overflow. With MAX_SUMMARY_REDUCE_DEPTH it must terminate and
     * still return a (best-effort over-budget) summary.
     */
    @Test
    void summary_reduce_recursion_terminates_when_partials_never_shrink() {
        // Provider returns a large verbatim-ish block so `combined` stays over budget every round.
        LlmProvider echo = new LlmProvider() {
            int n = 0;
            @Override public String getName() { return "echo"; }
            @Override public LlmResponse chat(LlmRequest request) {
                n++;
                String in = (String) request.getMessages().get(0).getContent();
                LlmResponse r = new LlmResponse();
                // Echo back a large chunk of the input so summaries never compress.
                r.setContent(in.length() > 4000 ? in.substring(0, 4000) : in);
                return r;
            }
            @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { }
        };
        StringBuilder sb = new StringBuilder();
        String[] words = {"alpha ", "bravo ", "charlie ", "delta ", "echo ", "foxtrot "};
        for (int i = 0; i < 6000; i++) sb.append(words[i % words.length]);

        // Must return (terminate) — assertTimeout guards against a non-terminating recursion.
        String summary = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10),
                () -> strategy.summarizeWithWindowGuard(echo, null, sb.toString(), 6_000));
        assertThat(summary).isNotBlank();
    }

    /**
     * Provider that captures every chunk passed to it and asserts UTF-16 surrogate integrity on
     * each: no chunk may END with a lone high surrogate or START with a lone low surrogate. Such a
     * split is what later makes the real provider's UTF-8 JSON generator throw
     * MismatchedSurrogateException (#1 INV-8). Asserting directly on the chunk boundaries is robust
     * regardless of the test-side Jackson version's lenience on in-memory String serialization.
     */
    private static final class SurrogateCheckingProvider implements LlmProvider {
        int calls = 0;
        boolean sawSplitSurrogate = false;
        @Override public String getName() { return "surrogate-check"; }
        @Override public LlmResponse chat(LlmRequest request) {
            calls++;
            String content = (String) request.getMessages().get(0).getContent();
            if (!content.isEmpty()) {
                char last = content.charAt(content.length() - 1);
                char first = content.charAt(0);
                if (Character.isHighSurrogate(last) || Character.isLowSurrogate(first)) {
                    sawSplitSurrogate = true;
                }
            }
            LlmResponse r = new LlmResponse();
            r.setContent("OK-" + calls);
            return r;
        }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { }
    }

    @Test
    void hardSplit_does_not_cut_mid_surrogate_on_oversized_emoji_line() {
        // #1 INV-8: an oversized SINGLE line (no '\n') with a non-BMP emoji landing exactly on the
        // hard-split boundary. Pre-fix the naive cut split the surrogate pair → a chunk ended in a
        // lone high surrogate (and the next began with a lone low surrogate), which later makes the
        // real provider's UTF-8 JSON generator throw MismatchedSurrogateException. Post-fix the cut
        // steps back one char so each chunk is surrogate-complete.
        //
        // budget = 6000 − 4000 reserve = 2000 → approxCharsPerChunk = max(1000, 2000*4) = 8000. The
        // first cut ends at index 8000 (last included char = 7999); placing the emoji's HIGH
        // surrogate at index 7999 makes the naive cut split the pair. Filler is VARIED ASCII words
        // so the token estimate genuinely exceeds the budget and hardSplit is actually exercised (a
        // run of one repeated char BPE-compresses below budget and would skip hardSplit).
        String emoji = "😀"; // U+1F600 — a UTF-16 surrogate pair
        StringBuilder line = new StringBuilder();
        String[] words = {"alpha ", "bravo ", "charlie ", "delta ", "echo ", "foxtrot ", "golf "};
        int w = 0;
        while (line.length() < 7999) {
            line.append(words[w++ % words.length]);
        }
        line.setLength(7999);                    // exact: high surrogate will land at index 7999
        line.append(emoji);                      // chars 7999 (high) + 8000 (low)
        while (line.length() < 30000) {          // push well past one chunk so a real split happens
            line.append(words[w++ % words.length]);
        }
        // Sanity: the high surrogate is exactly at the first naive cut boundary.
        assertThat(Character.isHighSurrogate(line.charAt(7999))).isTrue();

        SurrogateCheckingProvider p = new SurrogateCheckingProvider();
        // Single line, no newline → forces the hardSplit path. Small window → small budget.
        String summary = strategy.summarizeWithWindowGuard(p, null, line.toString(), 6_000);

        // No chunk split the surrogate pair (pre-fix this was true → exception in the real path).
        assertThat(p.sawSplitSurrogate)
                .as("hardSplit must not cut between a high and low surrogate")
                .isFalse();
        assertThat(summary).isNotBlank();
        assertThat(p.calls).isGreaterThanOrEqualTo(2); // line was split into >= 2 chunks
    }

    /** Regression: mergeSummaryIntoUser was deleted (BUG-F-1). */
    @Test
    void mergeSummaryIntoUser_method_is_removed() {
        boolean found = false;
        for (java.lang.reflect.Method m : FullCompactStrategy.class.getDeclaredMethods()) {
            if ("mergeSummaryIntoUser".equals(m.getName())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("mergeSummaryIntoUser must be removed (BUG-F-1)").isFalse();
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
