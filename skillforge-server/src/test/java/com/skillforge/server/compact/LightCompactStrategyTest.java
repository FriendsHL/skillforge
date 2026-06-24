package com.skillforge.server.compact;

import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.CompactResult;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LightCompactStrategyTest {

    private final LightCompactStrategy strategy = new LightCompactStrategy();

    // ---- builders ----

    private Message assistantTextAndToolUse(String text, String toolId, String name, Map<String, Object> input) {
        Message m = new Message();
        m.setRole(Message.Role.ASSISTANT);
        List<ContentBlock> blocks = new ArrayList<>();
        if (text != null && !text.isEmpty()) blocks.add(ContentBlock.text(text));
        blocks.add(ContentBlock.toolUse(toolId, name, input));
        m.setContent(blocks);
        return m;
    }

    private Message assistantToolUse(String toolId, String name, Map<String, Object> input) {
        return assistantTextAndToolUse(null, toolId, name, input);
    }

    private Message toolResult(String toolUseId, String content, boolean isError) {
        Message m = new Message();
        m.setRole(Message.Role.USER);
        m.setContent(List.of(ContentBlock.toolResult(toolUseId, content, isError)));
        return m;
    }

    private Message filler(String text) {
        return Message.user(text);
    }

    // ---- tests ----

    @Test
    void truncates_large_tool_output() {
        // build padding messages to push target beyond protection window
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        // tool_use + large tool_result pair OUTSIDE protection window
        Map<String, Object> input = Map.of("path", "/tmp/big.log");
        msgs.add(assistantToolUse("t1", "Bash", input));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("line ").append(i).append("\n");
        msgs.add(toolResult("t1", big.toString(), false));
        // Add 6 filler messages so protection window (last 5) doesn't cover the tool_result
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        assertThat(r.getStrategiesApplied()).contains("truncate-large-tool-output");
        // find the tool_result in the result
        ContentBlock tr = findFirstToolResult(r.getMessages());
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).contains("chars truncated");
        assertThat(tr.getContent().length()).isLessThan(big.length());
        assertThat(r.getTokensReclaimed()).isGreaterThan(0);
    }

    @Test
    void dedups_consecutive_identical_tools() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(filler("start"));
        // first Grep call
        Map<String, Object> input = new HashMap<>();
        input.put("pattern", "TODO");
        msgs.add(assistantToolUse("t1", "Grep", input));
        msgs.add(toolResult("t1", "no matches", false));
        // second identical Grep call
        Map<String, Object> input2 = new HashMap<>();
        input2.put("pattern", "TODO");
        msgs.add(assistantToolUse("t2", "Grep", input2));
        msgs.add(toolResult("t2", "no matches", false));
        // protection window tail (5 fillers so both pairs are outside protection)
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        assertThat(r.getStrategiesApplied()).contains("dedup-consecutive-tools");
        // The earlier pair (t1) should be gone but t2 should remain
        assertThat(containsToolUseId(r.getMessages(), "t1")).isFalse();
        assertThat(containsToolUseId(r.getMessages(), "t2")).isTrue();
        // No orphan tool_result for t1
        assertThat(containsToolResultForId(r.getMessages(), "t1")).isFalse();
    }

    @Test
    void folds_consecutive_failures() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(filler("start"));
        // Four failed attempts with DIFFERENT inputs so dedup doesn't collapse them first.
        for (int i = 1; i <= 4; i++) {
            msgs.add(assistantToolUse("e" + i, "Bash", Map.of("command", "flaky" + i)));
            msgs.add(toolResult("e" + i, "error attempt " + i, true));
        }
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        assertThat(r.getStrategiesApplied()).contains("fold-failed-retries");
        // all four tool_use still present (pairing invariant)
        for (int i = 1; i <= 4; i++) {
            assertThat(containsToolUseId(r.getMessages(), "e" + i)).isTrue();
            assertThat(containsToolResultForId(r.getMessages(), "e" + i)).isTrue();
        }
        // but early failures should now contain "[folded]" marker
        int foldedCount = 0;
        for (Message m : r.getMessages()) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb
                            && "tool_result".equals(cb.getType())
                            && cb.getContent() != null
                            && cb.getContent().startsWith("[folded]")) {
                        foldedCount++;
                    }
                }
            }
        }
        assertThat(foldedCount).isEqualTo(3); // first 3 folded, last preserved
    }

    @Test
    void preserves_tool_use_tool_result_pairing() {
        // Scenario: a naive dedup that dropped only one side would orphan something.
        // Feed two identical tool_use, assert both have their corresponding tool_result.
        List<Message> msgs = new ArrayList<>();
        msgs.add(filler("start"));
        msgs.add(assistantToolUse("t1", "Grep", Map.of("p", "x")));
        msgs.add(toolResult("t1", "r1", false));
        msgs.add(assistantToolUse("t2", "Grep", Map.of("p", "x")));
        msgs.add(toolResult("t2", "r2", false));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        // After compaction, every remaining tool_use must have its tool_result present
        // and every remaining tool_result must have a matching tool_use
        java.util.Set<String> toolUseIds = new java.util.HashSet<>();
        java.util.Set<String> toolResultIds = new java.util.HashSet<>();
        for (Message m : r.getMessages()) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb) {
                        if ("tool_use".equals(cb.getType())) toolUseIds.add(cb.getId());
                        if ("tool_result".equals(cb.getType())) toolResultIds.add(cb.getToolUseId());
                    }
                }
            }
        }
        assertThat(toolUseIds).isEqualTo(toolResultIds);
    }

    @Test
    void skips_last_n_messages() {
        // Put a large tool_result INSIDE the protection window (last 5 messages)
        // — it must not be truncated.
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Bash", Map.of("cmd", "ls")));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("line ").append(i).append("\n");
        msgs.add(toolResult("t1", big.toString(), false));
        // only 3 tail fillers — tool_result sits at index 4, total=8, protection window = last 5 → protect from 3
        msgs.add(filler("tail 1"));
        msgs.add(filler("tail 2"));
        msgs.add(filler("tail 3"));

        CompactResult r = strategy.apply(msgs, 32000);

        // The tool_result should be untouched because it is in the protection window.
        ContentBlock tr = findFirstToolResult(r.getMessages());
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).doesNotContain("chars truncated");
    }

    @Test
    void dedup_multi_hit_cascade_never_dips_into_protection_window() {
        // 5 identical Grep pairs in a row outside protection, followed by small tail.
        // After multiple removals, the loop bound MUST be recomputed each iteration;
        // otherwise it will walk into the last-5 protection window.
        List<Message> msgs = new ArrayList<>();
        msgs.add(filler("start"));
        Map<String, Object> sameInput = new HashMap<>();
        sameInput.put("pattern", "TODO");
        for (int i = 1; i <= 5; i++) {
            msgs.add(assistantToolUse("g" + i, "Grep", new HashMap<>(sameInput)));
            msgs.add(toolResult("g" + i, "no matches", false));
        }
        // 6 tail fillers so protection window covers [msgs.size()-5, end]
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        assertThat(r.getStrategiesApplied()).contains("dedup-consecutive-tools");
        // After dedup, only the most recent pair should remain; earlier 4 pairs gone.
        // But pairing invariant must hold: every tool_use has matching tool_result.
        java.util.Set<String> toolUseIds = new java.util.HashSet<>();
        java.util.Set<String> toolResultIds = new java.util.HashSet<>();
        for (Message m : r.getMessages()) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb) {
                        if ("tool_use".equals(cb.getType())) toolUseIds.add(cb.getId());
                        if ("tool_result".equals(cb.getType())) toolResultIds.add(cb.getToolUseId());
                    }
                }
            }
        }
        assertThat(toolUseIds).isEqualTo(toolResultIds);
        // The last 5 raw messages must be byte-identical to their originals (protection window)
        int protectStart = r.getMessages().size() - 5;
        int origProtectStart = msgs.size() - 5;
        // Can't compare the exact Messages by identity because working is a new ArrayList,
        // but text content of the tail fillers should match the last 5 original fillers
        for (int k = 0; k < 5; k++) {
            Message after = r.getMessages().get(protectStart + k);
            Message before = msgs.get(origProtectStart + k);
            assertThat(after).isSameAs(before);  // protection window is left untouched (same reference)
        }
    }

    // ---- whitelist tests (P9-1) ----

    @Test
    void skips_truncation_for_memory_tool() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        // tool_use named "Memory" — should NOT be truncated
        msgs.add(assistantToolUse("t1", "Memory", Map.of("action", "search")));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("memory line ").append(i).append("\n");
        String originalContent = big.toString();
        msgs.add(toolResult("t1", originalContent, false));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        ContentBlock tr = findToolResultForId(r.getMessages(), "t1");
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).isEqualTo(originalContent);
        assertThat(tr.getContent()).doesNotContain("chars truncated");
    }

    @Test
    void skips_truncation_for_subagent_tool() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "SubAgent", Map.of("task", "analyze")));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("subagent line ").append(i).append("\n");
        String originalContent = big.toString();
        msgs.add(toolResult("t1", originalContent, false));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        ContentBlock tr = findToolResultForId(r.getMessages(), "t1");
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).isEqualTo(originalContent);
    }

    @Test
    void truncates_whitelisted_tool_grep() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Grep", Map.of("pattern", ".*")));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("match ").append(i).append("\n");
        msgs.add(toolResult("t1", big.toString(), false));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        assertThat(r.getStrategiesApplied()).contains("truncate-large-tool-output");
        ContentBlock tr = findToolResultForId(r.getMessages(), "t1");
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).contains("chars truncated");
    }

    @Test
    void per_tool_bucket_bash_keeps_more_than_default() {
        // A ~20KB Bash result is BELOW Bash's 50KB bucket → NOT truncated, even though it exceeds
        // the 10KB default that an "Edit" result of the same size would hit.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20 * 1024; i++) big.append('a'); // 20480 ASCII bytes
        String content = big.toString();
        assertThat(content.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(CompactableToolRegistry.DEFAULT_TRUNCATE_THRESHOLD_BYTES)
                .isLessThan(CompactableToolRegistry.DEFAULT_TRUNCATE_THRESHOLDS.get("Bash"));

        List<Message> bashMsgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) bashMsgs.add(filler("pad " + i));
        bashMsgs.add(assistantToolUse("t1", "Bash", Map.of("command", "cat big")));
        bashMsgs.add(toolResult("t1", content, false));
        for (int i = 0; i < 6; i++) bashMsgs.add(filler("tail " + i));
        CompactResult bashResult = strategy.apply(bashMsgs, 32000);
        ContentBlock bashTr = findToolResultForId(bashResult.getMessages(), "t1");
        assertThat(bashTr).isNotNull();
        assertThat(bashTr.getContent()).as("Bash result under its 50KB bucket must not truncate")
                .isEqualTo(content);

        // The SAME content under an "Edit" tool (default 10KB bucket) IS truncated.
        List<Message> editMsgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) editMsgs.add(filler("pad " + i));
        editMsgs.add(assistantToolUse("t2", "Edit", Map.of("path", "/tmp/x")));
        editMsgs.add(toolResult("t2", content, false));
        for (int i = 0; i < 6; i++) editMsgs.add(filler("tail " + i));
        CompactResult editResult = strategy.apply(editMsgs, 32000);
        ContentBlock editTr = findToolResultForId(editResult.getMessages(), "t2");
        assertThat(editTr).isNotNull();
        assertThat(editTr.getContent()).as("Edit result over the 10KB default must truncate")
                .contains("chars truncated");
    }

    @Test
    void per_tool_bucket_override_from_agent_config() {
        // Agent config can shrink Bash's bucket so a smaller result truncates.
        CompactableToolRegistry custom = CompactableToolRegistry.fromAgentConfig(
                Map.of("compactable_tool_thresholds", Map.of("Bash", 4096)));
        assertThat(custom.truncateThresholdBytesFor("Bash")).isEqualTo(4096);

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 8 * 1024; i++) big.append('a'); // 8192 bytes > 4096 override
        String content = big.toString();

        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Bash", Map.of("command", "cat big")));
        msgs.add(toolResult("t1", content, false));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000, custom);
        ContentBlock tr = findToolResultForId(r.getMessages(), "t1");
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).as("Bash result over the overridden 4KB bucket must truncate")
                .contains("chars truncated");
    }

    @Test
    void skips_truncation_when_tool_use_id_not_found_in_index() {
        // Orphan tool_result with no matching tool_use — safe default: don't truncate
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        // No assistantToolUse for "orphan_id"
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("orphan line ").append(i).append("\n");
        String originalContent = big.toString();
        msgs.add(toolResult("orphan_id", originalContent, false));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        ContentBlock tr = findToolResultForId(r.getMessages(), "orphan_id");
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).isEqualTo(originalContent);
    }

    @Test
    void custom_registry_restricts_truncation() {
        // Registry that only includes "Bash" — Grep should NOT be truncated
        CompactableToolRegistry custom = new CompactableToolRegistry(Set.of("Bash"));
        LightCompactStrategy customStrategy = new LightCompactStrategy(custom);

        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Grep", Map.of("pattern", ".*")));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("match ").append(i).append("\n");
        String originalContent = big.toString();
        msgs.add(toolResult("t1", originalContent, false));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = customStrategy.apply(msgs, 32000);

        ContentBlock tr = findToolResultForId(r.getMessages(), "t1");
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).isEqualTo(originalContent); // not truncated
    }

    @Test
    void per_call_registry_override_works() {
        // Default strategy (has Grep in whitelist), but per-call override excludes it
        CompactableToolRegistry override = new CompactableToolRegistry(Set.of("Bash"));

        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Grep", Map.of("pattern", ".*")));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("match ").append(i).append("\n");
        String originalContent = big.toString();
        msgs.add(toolResult("t1", originalContent, false));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000, override);

        ContentBlock tr = findToolResultForId(r.getMessages(), "t1");
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).isEqualTo(originalContent); // not truncated by override
    }

    // ---- truncate-by-bytes acceptance tests (LLM-OUTPUT-BUDGET-AND-TRUNCATE Fix 2) ----

    @Test
    void truncate_preserves_first_4k_chars_verbatim() {
        // Build a content where first 4096 chars are predictable: "A" repeated 4096 times,
        // followed by enough other chars to push total > 10K bytes and > HEAD+TAIL chars.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < LightCompactStrategy.TRUNCATE_HEAD_CHARS; i++) big.append('A');
        for (int i = 0; i < 50_000; i++) big.append('B');
        for (int i = 0; i < LightCompactStrategy.TRUNCATE_TAIL_CHARS; i++) big.append('C');
        String original = big.toString();

        List<Message> msgs = buildBashToolResultMessages(original);
        CompactResult r = strategy.apply(msgs, 32000);

        ContentBlock tr = findFirstToolResult(r.getMessages());
        assertThat(tr).isNotNull();
        String result = tr.getContent();
        // first HEAD_CHARS chars must be byte-identical to original head
        assertThat(result.substring(0, LightCompactStrategy.TRUNCATE_HEAD_CHARS))
                .isEqualTo(original.substring(0, LightCompactStrategy.TRUNCATE_HEAD_CHARS));
    }

    @Test
    void truncate_preserves_last_2k_chars_verbatim() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < LightCompactStrategy.TRUNCATE_HEAD_CHARS; i++) big.append('A');
        for (int i = 0; i < 50_000; i++) big.append('B');
        for (int i = 0; i < LightCompactStrategy.TRUNCATE_TAIL_CHARS; i++) big.append('C');
        String original = big.toString();

        List<Message> msgs = buildBashToolResultMessages(original);
        CompactResult r = strategy.apply(msgs, 32000);

        ContentBlock tr = findFirstToolResult(r.getMessages());
        assertThat(tr).isNotNull();
        String result = tr.getContent();
        // last TAIL_CHARS chars of result must equal last TAIL_CHARS chars of original
        String resultTail = result.substring(result.length() - LightCompactStrategy.TRUNCATE_TAIL_CHARS);
        String origTail = original.substring(original.length() - LightCompactStrategy.TRUNCATE_TAIL_CHARS);
        assertThat(resultTail).isEqualTo(origTail);
    }

    @Test
    void truncate_marker_contains_truncated_chars_and_original_size() {
        StringBuilder big = new StringBuilder();
        int totalChars = 50_000;
        for (int i = 0; i < totalChars; i++) big.append('x');
        String original = big.toString();

        List<Message> msgs = buildBashToolResultMessages(original);
        CompactResult r = strategy.apply(msgs, 32000);

        ContentBlock tr = findFirstToolResult(r.getMessages());
        assertThat(tr).isNotNull();
        String content = tr.getContent();
        int expectedTruncated = totalChars
                - LightCompactStrategy.TRUNCATE_HEAD_CHARS
                - LightCompactStrategy.TRUNCATE_TAIL_CHARS;
        assertThat(content).contains(expectedTruncated + " chars truncated, original size: "
                + totalChars + " chars");
        assertThat(content).contains("% reduced)]");
    }

    @Test
    void truncate_handles_single_long_line() {
        // Old line-based truncate would do nothing because there's only 1 line.
        // New byte-based truncate must still cut.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50_000; i++) big.append('z');
        String original = big.toString();
        // sanity: no '\n' so one line
        assertThat(original).doesNotContain("\n");

        List<Message> msgs = buildBashToolResultMessages(original);
        CompactResult r = strategy.apply(msgs, 32000);

        assertThat(r.getStrategiesApplied()).contains("truncate-large-tool-output");
        ContentBlock tr = findFirstToolResult(r.getMessages());
        assertThat(tr).isNotNull();
        assertThat(tr.getContent()).contains("chars truncated");
        assertThat(tr.getContent().length()).isLessThan(original.length());
    }

    @Test
    void truncate_skips_when_exactly_at_threshold() {
        // Build content whose UTF-8 byte size is exactly LARGE_TOOL_OUTPUT_BYTES (10240).
        // ASCII => 1 byte/char => 10240 chars exactly.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < LightCompactStrategy.LARGE_TOOL_OUTPUT_BYTES; i++) big.append('a');
        String original = big.toString();
        assertThat(original.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isEqualTo(LightCompactStrategy.LARGE_TOOL_OUTPUT_BYTES);

        List<Message> msgs = buildBashToolResultMessages(original);
        CompactResult r = strategy.apply(msgs, 32000);

        ContentBlock tr = findFirstToolResult(r.getMessages());
        assertThat(tr).isNotNull();
        // <=  threshold => not truncated
        assertThat(tr.getContent()).isEqualTo(original);
        assertThat(r.getStrategiesApplied()).doesNotContain("truncate-large-tool-output");
    }

    @Test
    void truncate_is_idempotent_for_already_truncated_content() {
        // Run once to produce a truncated content, then re-run the strategy on the result.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50_000; i++) big.append('y');
        String original = big.toString();

        List<Message> msgsA = buildBashToolResultMessages(original);
        CompactResult r1 = strategy.apply(msgsA, 32000);
        ContentBlock tr1 = findFirstToolResult(r1.getMessages());
        assertThat(tr1).isNotNull();
        String firstPass = tr1.getContent();
        assertThat(firstPass).contains("chars truncated");

        // Build a fresh message list using firstPass as the tool_result content.
        // It still > 10K bytes (4096 + 2048 + marker ≈ 6.2K — actually under 10K, so first
        // we test a content that IS still > 10K bytes after truncation).
        // To force >10K bytes after truncation, use a much larger original so head+tail+marker > 10K.
        StringBuilder huge = new StringBuilder();
        // head 4096 + tail 2048 = 6144 chars; need > 10K bytes total post-truncation, so make
        // head/tail include chars that increase byte size (use ASCII but expand HEAD+TAIL via
        // already-truncated content shape). Simpler: just verify that even when content carries
        // marker, no second truncation happens regardless of byte size — by re-feeding the
        // exact post-truncation content but pad it large enough to exceed threshold.
        huge.append(firstPass);
        // append enough ASCII to push the post-truncation content over 10K bytes
        while (huge.length() < LightCompactStrategy.LARGE_TOOL_OUTPUT_BYTES + 100) {
            huge.append("Z");
        }
        String alreadyTruncated = huge.toString();
        // sanity: contains marker
        assertThat(alreadyTruncated).contains("chars truncated, original size:");
        assertThat(alreadyTruncated.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(LightCompactStrategy.LARGE_TOOL_OUTPUT_BYTES);

        List<Message> msgsB = buildBashToolResultMessages(alreadyTruncated);
        CompactResult r2 = strategy.apply(msgsB, 32000);
        ContentBlock tr2 = findFirstToolResult(r2.getMessages());
        assertThat(tr2).isNotNull();
        // Idempotent: second pass leaves content untouched (still equal to alreadyTruncated)
        assertThat(tr2.getContent()).isEqualTo(alreadyTruncated);
        // No truncate strategy applied (rule is a no-op for marker-bearing content)
        assertThat(r2.getStrategiesApplied()).doesNotContain("truncate-large-tool-output");
    }

    /**
     * Helper that builds a message list with a single Bash tool_use + tool_result outside the
     * protection window, surrounded by enough fillers to keep the pair compactable.
     */
    private List<Message> buildBashToolResultMessages(String content) {
        // Uses the "Edit" tool: whitelisted AND carrying the DEFAULT 10KB truncation bucket, so the
        // tests below that assert against LARGE_TOOL_OUTPUT_BYTES (the default threshold) stay valid
        // after per-tool buckets were introduced (Bash now has a larger 50KB bucket — see the
        // dedicated truncates_whitelisted_tool_grep / per-tool tests for bucket-specific coverage).
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Edit", Map.of("path", "/tmp/x")));
        msgs.add(toolResult("t1", content, false));
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));
        return msgs;
    }

    @Test
    void truncate_handles_chinese_cjk_characters() {
        // U+6D4B (测) is 3 bytes in UTF-8. 15_000 chars => ~45_000 bytes, well over 10K threshold.
        // Char count (15_000) > HEAD + TAIL (4096 + 2048 = 6144), so truncation runs.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 15_000; i++) big.append('测');
        String original = big.toString();
        assertThat(original.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(LightCompactStrategy.LARGE_TOOL_OUTPUT_BYTES);

        List<Message> msgs = buildBashToolResultMessages(original);
        CompactResult r = strategy.apply(msgs, 32000);

        assertThat(r.getStrategiesApplied()).contains("truncate-large-tool-output");
        ContentBlock tr = findFirstToolResult(r.getMessages());
        assertThat(tr).isNotNull();
        String result = tr.getContent();

        // Head: first HEAD_CHARS chars are CJK and byte-identical (no multibyte split).
        String resultHead = result.substring(0, LightCompactStrategy.TRUNCATE_HEAD_CHARS);
        String origHead = original.substring(0, LightCompactStrategy.TRUNCATE_HEAD_CHARS);
        assertThat(resultHead).isEqualTo(origHead);
        // Sanity: head is all '测' chars (no byte split would yield '?' or U+FFFD).
        for (int i = 0; i < resultHead.length(); i++) {
            assertThat(resultHead.charAt(i)).isEqualTo('测');
        }

        // Tail: last TAIL_CHARS chars are CJK and byte-identical.
        String resultTail = result.substring(result.length() - LightCompactStrategy.TRUNCATE_TAIL_CHARS);
        String origTail = original.substring(original.length() - LightCompactStrategy.TRUNCATE_TAIL_CHARS);
        assertThat(resultTail).isEqualTo(origTail);
        for (int i = 0; i < resultTail.length(); i++) {
            assertThat(resultTail.charAt(i)).isEqualTo('测');
        }

        // Marker present.
        assertThat(result).contains("chars truncated, original size:");
        // No replacement / mojibake characters introduced.
        assertThat(result).doesNotContain("�");
    }

    @Test
    void truncate_multiple_large_tool_results_in_same_pass() {
        // Two whitelisted tool_use → tool_result pairs, both with content well over the
        // 10K threshold. A single strategy.apply() must truncate BOTH.
        StringBuilder big1 = new StringBuilder();
        for (int i = 0; i < 30_000; i++) big1.append('a');
        StringBuilder big2 = new StringBuilder();
        for (int i = 0; i < 50_000; i++) big2.append('b');
        String content1 = big1.toString();
        String content2 = big2.toString();

        // Use "Edit" (default 10KB bucket) so both 30K/50K results exceed the threshold; Bash now
        // carries a larger 50KB bucket and is covered by per_tool_bucket_* tests instead.
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 3; i++) msgs.add(filler("pad " + i));
        msgs.add(assistantToolUse("t1", "Edit", Map.of("path", "/tmp/a")));
        msgs.add(toolResult("t1", content1, false));
        msgs.add(filler("between"));
        msgs.add(assistantToolUse("t2", "Edit", Map.of("path", "/tmp/b")));
        msgs.add(toolResult("t2", content2, false));
        // Padding tail so both pairs sit outside the protection window.
        for (int i = 0; i < 6; i++) msgs.add(filler("tail " + i));

        CompactResult r = strategy.apply(msgs, 32000);

        // Strategy applied (recorded once even though it touched two blocks — current impl).
        assertThat(r.getStrategiesApplied()).contains("truncate-large-tool-output");

        // Both tool_results truncated with correct head / tail / marker.
        ContentBlock tr1 = findToolResultForId(r.getMessages(), "t1");
        ContentBlock tr2 = findToolResultForId(r.getMessages(), "t2");
        assertThat(tr1).isNotNull();
        assertThat(tr2).isNotNull();
        for (ContentBlock tr : List.of(tr1, tr2)) {
            String c = tr.getContent();
            assertThat(c).contains("chars truncated, original size:");
            assertThat(c.length()).isLessThan(content2.length()); // shorter than the larger original
        }

        // Distinct content preserved (head+tail belong to the right original).
        assertThat(tr1.getContent().substring(0, LightCompactStrategy.TRUNCATE_HEAD_CHARS))
                .isEqualTo(content1.substring(0, LightCompactStrategy.TRUNCATE_HEAD_CHARS));
        assertThat(tr2.getContent().substring(0, LightCompactStrategy.TRUNCATE_HEAD_CHARS))
                .isEqualTo(content2.substring(0, LightCompactStrategy.TRUNCATE_HEAD_CHARS));
        // No cross-contamination.
        assertThat(tr1.getContent()).doesNotContain("bbbbbbbb");
        assertThat(tr2.getContent()).doesNotContain("aaaaaaaa");
    }

    // ---- helpers ----

    private ContentBlock findToolResultForId(List<Message> msgs, String toolUseId) {
        for (Message m : msgs) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb && "tool_result".equals(cb.getType())
                            && toolUseId.equals(cb.getToolUseId())) {
                        return cb;
                    }
                }
            }
        }
        return null;
    }

    private ContentBlock findFirstToolResult(List<Message> msgs) {
        for (Message m : msgs) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb && "tool_result".equals(cb.getType())) {
                        return cb;
                    }
                }
            }
        }
        return null;
    }

    private boolean containsToolUseId(List<Message> msgs, String id) {
        for (Message m : msgs) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb && "tool_use".equals(cb.getType())
                            && id.equals(cb.getId())) return true;
                }
            }
        }
        return false;
    }

    private boolean containsToolResultForId(List<Message> msgs, String id) {
        for (Message m : msgs) {
            if (m.getContent() instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb && "tool_result".equals(cb.getType())
                            && id.equals(cb.getToolUseId())) return true;
                }
            }
        }
        return false;
    }
}
