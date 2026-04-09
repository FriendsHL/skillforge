package com.skillforge.core.engine;

import com.skillforge.core.compact.ContextCompactTool;
import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.ContextCompactorCallback.CompactCallbackResult;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillRegistry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Package-private tests for AgentLoopEngine's compact anti-loop guard + B1/B2 gating.
 * <p>This test lives under com.skillforge.core.engine to access package-private
 * {@link AgentLoopEngine#handleCompactContext(ToolUseBlock, LoopContext)}.
 */
class AgentLoopEngineCompactTest {

    /**
     * Simple stub callback that records each call and always reports a performed mini compact.
     */
    private static class RecordingCallback implements ContextCompactorCallback {
        int lightCalls = 0;
        int fullCalls = 0;
        final List<String> sourceLabels = new ArrayList<>();

        @Override
        public CompactCallbackResult compactLight(String sessionId, List<Message> currentMessages,
                                                    String sourceLabel, String reason) {
            lightCalls++;
            sourceLabels.add("light:" + sourceLabel);
            return new CompactCallbackResult(currentMessages, true, 100, 500, 400,
                    "mock light");
        }

        @Override
        public CompactCallbackResult compactFull(String sessionId, List<Message> currentMessages,
                                                   String sourceLabel, String reason) {
            fullCalls++;
            sourceLabels.add("full:" + sourceLabel);
            return new CompactCallbackResult(currentMessages, true, 200, 600, 400,
                    "mock full");
        }
    }

    private AgentLoopEngine newEngine(ContextCompactorCallback cb) {
        AgentLoopEngine engine = new AgentLoopEngine(
                new LlmProviderFactory(),
                "unused",
                new SkillRegistry(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        engine.setCompactorCallback(cb);
        return engine;
    }

    private ToolUseBlock compactToolUse(String id, String level, String reason) {
        Map<String, Object> input = new HashMap<>();
        input.put("level", level);
        input.put("reason", reason);
        return new ToolUseBlock(id, ContextCompactTool.NAME, input);
    }

    private LoopContext ctx() {
        LoopContext c = new LoopContext();
        c.setSessionId("test-session");
        c.setMessages(new ArrayList<>());
        return c;
    }

    @Test
    void handleCompactContext_short_circuits_light_when_already_compacted_this_iteration() {
        RecordingCallback cb = new RecordingCallback();
        AgentLoopEngine engine = newEngine(cb);

        LoopContext lc = ctx();
        // Simulate B1 having already run this iteration
        lc.markCompactedThisIteration();

        Message result = engine.handleCompactContext(
                compactToolUse("tu1", "light", "llm says"), lc);

        // The callback should NOT have been called
        assertThat(cb.lightCalls).isEqualTo(0);
        assertThat(cb.fullCalls).isEqualTo(0);
        // The tool_result should explicitly mention that a compact already ran
        assertThat(result).isNotNull();
        assertThat(result.getRole()).isEqualTo(Message.Role.USER);
        List<?> blocks = (List<?>) result.getContent();
        assertThat(blocks).isNotEmpty();
        com.skillforge.core.model.ContentBlock cb0 = (com.skillforge.core.model.ContentBlock) blocks.get(0);
        assertThat(cb0.getContent()).contains("already ran this iteration");
    }

    @Test
    void handleCompactContext_short_circuits_full_when_already_compacted_this_iteration() {
        RecordingCallback cb = new RecordingCallback();
        AgentLoopEngine engine = newEngine(cb);

        LoopContext lc = ctx();
        lc.markCompactedThisIteration();

        Message result = engine.handleCompactContext(
                compactToolUse("tu2", "full", "llm says"), lc);

        assertThat(cb.lightCalls).isEqualTo(0);
        assertThat(cb.fullCalls).isEqualTo(0);
        assertThat(result).isNotNull();
        com.skillforge.core.model.ContentBlock cb0 =
                (com.skillforge.core.model.ContentBlock) ((List<?>) result.getContent()).get(0);
        assertThat(cb0.getContent()).contains("already ran this iteration");
    }

    @Test
    void handleCompactContext_allows_first_call_in_iteration() {
        RecordingCallback cb = new RecordingCallback();
        AgentLoopEngine engine = newEngine(cb);

        LoopContext lc = ctx();
        // Not yet compacted this iteration

        Message result = engine.handleCompactContext(
                compactToolUse("tu3", "light", "fresh iteration"), lc);

        assertThat(cb.lightCalls).isEqualTo(1);
        assertThat(cb.sourceLabels).containsExactly("light:agent-tool");
        assertThat(lc.isCompactedThisIteration()).isTrue();
        com.skillforge.core.model.ContentBlock cb0 =
                (com.skillforge.core.model.ContentBlock) ((List<?>) result.getContent()).get(0);
        assertThat(cb0.getIsError()).isFalse();
    }

    @Test
    void handleCompactContext_second_call_in_same_iteration_is_blocked_after_first() {
        RecordingCallback cb = new RecordingCallback();
        AgentLoopEngine engine = newEngine(cb);

        LoopContext lc = ctx();

        // First call actually runs
        engine.handleCompactContext(compactToolUse("tu1", "light", "first"), lc);
        assertThat(cb.lightCalls).isEqualTo(1);

        // Second call in the same iteration → blocked (both light and full)
        engine.handleCompactContext(compactToolUse("tu2", "light", "second"), lc);
        engine.handleCompactContext(compactToolUse("tu3", "full", "third"), lc);
        assertThat(cb.lightCalls).isEqualTo(1);
        assertThat(cb.fullCalls).isEqualTo(0);

        // After iteration reset, a new compact should work again
        lc.resetCompactedThisIteration();
        engine.handleCompactContext(compactToolUse("tu4", "full", "new iter"), lc);
        assertThat(cb.fullCalls).isEqualTo(1);
    }

    @Test
    void loopContext_compactedThisIteration_flag_round_trip() {
        // Sanity check for the flag plumbing itself
        LoopContext lc = new LoopContext();
        assertThat(lc.isCompactedThisIteration()).isFalse();
        lc.markCompactedThisIteration();
        assertThat(lc.isCompactedThisIteration()).isTrue();
        lc.resetCompactedThisIteration();
        assertThat(lc.isCompactedThisIteration()).isFalse();
    }

    /**
     * Use AtomicInteger just to keep the import alive for potential follow-up tests.
     */
    @SuppressWarnings("unused")
    private AtomicInteger ignored() { return new AtomicInteger(); }

    @Test
    void resolveContextWindow_honors_per_agent_override_over_engine_default() {
        AgentLoopEngine engine = newEngine(new RecordingCallback());
        // Engine default starts at 32000
        engine.setDefaultContextWindowTokens(32000);

        com.skillforge.core.model.AgentDefinition agentDef = new com.skillforge.core.model.AgentDefinition();
        // Per-agent override (e.g. Claude 200k context)
        agentDef.getConfig().put("context_window_tokens", 200000);

        int resolved = engine.resolveContextWindow(agentDef);
        assertThat(resolved).isEqualTo(200000);
    }

    @Test
    void resolveContextWindow_falls_back_to_engine_default_when_no_per_agent_override() {
        AgentLoopEngine engine = newEngine(new RecordingCallback());
        engine.setDefaultContextWindowTokens(64000);

        com.skillforge.core.model.AgentDefinition agentDef = new com.skillforge.core.model.AgentDefinition();
        // No context_window_tokens in config

        int resolved = engine.resolveContextWindow(agentDef);
        assertThat(resolved).isEqualTo(64000);
    }
}
