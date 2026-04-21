package com.skillforge.server.compact;

import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.CompactResult;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import org.junit.jupiter.api.Test;

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
        assertThat(tr.getContent()).contains("[truncated");
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
        assertThat(tr.getContent()).doesNotContain("[truncated");
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
        assertThat(tr.getContent()).doesNotContain("[truncated");
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
        assertThat(tr.getContent()).contains("[truncated");
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
