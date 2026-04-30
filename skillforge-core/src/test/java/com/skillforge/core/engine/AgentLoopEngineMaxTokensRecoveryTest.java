package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9-2 — verifies the new max_tokens continuation semantics + 1-time guard.
 *
 * <ul>
 *   <li>First response: stopReason=max_tokens with partial text. Engine 触发续写（不重发原 request）。</li>
 *   <li>续写 response: stopReason=end_turn with remaining text. Engine 把 partial+continuation 合并，
 *       当作正常 assistant response 处理 → loop 继续 → text response 退出。</li>
 *   <li>If continuation also returns max_tokens → status=max_tokens_exhausted, no further retry.</li>
 *   <li>默认配置下 totalInputTokens > 500K 不再触发 token_budget_exceeded（max_input_tokens opt-in）。</li>
 * </ul>
 */
class AgentLoopEngineMaxTokensRecoveryTest {

    @Test
    @DisplayName("max_tokens 触发续写：partial + continuation 合并为最终 assistant 文本")
    void maxTokensContinuation_succeeds() {
        LlmProviderFactory factory = new LlmProviderFactory();
        QueueProvider provider = new QueueProvider(List.of(
                truncated("partial-"),                 // first call → max_tokens
                completed("continued-text", "end_turn") // continuation → success
        ));
        factory.registerProvider("fake", provider);

        AgentDefinition agent = new AgentDefinition();
        agent.setName("test-agent");
        agent.setModelId("fake:m");
        agent.setSystemPrompt("sys");
        agent.setConfig(Map.of("max_loops", 3, "max_tokens", 4096));

        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", new SkillRegistry(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        LoopResult result = engine.run(agent, "hello", new ArrayList<>(), "sid", 1L);

        assertThat(provider.callsLeft()).isZero();
        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getFinalResponse()).isEqualTo("partial-continued-text");
    }

    @Test
    @DisplayName("Judge FIX-1: per-iteration 重置 — 跨迭代各自独立触发 max_tokens 续写")
    void maxTokensRecovery_perIteration_resetsFlagBetweenIterations() {
        // 验证 PRD "同一 turn 内最多 1 次" 中 "turn" 语义为单次 LLM 调用：iter 1 成功续写后，
        // iter 2 再 max_tokens 仍能走 continuation（不是 per-run 永久 exhausted）。
        //
        // 序列：
        //  call 1 (LLM iter 1): max_tokens, partial text + tool_use(Echo) → 触发续写
        //  call 2 (cont iter 1): end_turn "rest1" → 合并 → response 仍有 toolUse → tool 执行 → iter 2
        //  call 3 (LLM iter 2): max_tokens, partial text 无 tool_use → 触发续写
        //  call 4 (cont iter 2): end_turn "rest2" → 合并 → 文本结束 loop
        //
        // 旧实现（per-run）会在 iter 2 直接 max_tokens_exhausted。
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new EchoToolStub());

        LlmProviderFactory factory = new LlmProviderFactory();
        QueueProvider provider = new QueueProvider(List.of(
                truncatedWithToolUse("partial1-", "c1", "Echo", Map.of("v", "x")),
                completed("rest1", "end_turn"),
                truncated("partial2-"),
                completed("rest2", "end_turn")
        ));
        factory.registerProvider("fake", provider);

        AgentDefinition agent = new AgentDefinition();
        agent.setName("test-agent");
        agent.setModelId("fake:m");
        agent.setSystemPrompt("sys");
        agent.setConfig(Map.of("max_loops", 5, "max_tokens", 4096));

        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        LoopResult result = engine.run(agent, "hello", new ArrayList<>(), "sid", 1L);

        assertThat(provider.callsLeft()).isZero(); // all 4 consumed
        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getFinalResponse()).isEqualTo("partial2-rest2");
    }

    private static LlmResponse truncatedWithToolUse(String partial, String toolUseId, String name,
                                                    Map<String, Object> input) {
        LlmResponse r = new LlmResponse();
        r.setContent(partial);
        r.setStopReason("max_tokens");
        r.setToolUseBlocks(List.of(new com.skillforge.core.model.ToolUseBlock(toolUseId, name, input)));
        return r;
    }

    @Test
    @DisplayName("续写仍然 max_tokens → status=max_tokens_exhausted，没有第三次尝试")
    void maxTokensContinuation_secondAlsoTruncated_returnsExhausted() {
        LlmProviderFactory factory = new LlmProviderFactory();
        QueueProvider provider = new QueueProvider(List.of(
                truncated("partial-"),
                truncated("more-but-still-truncated")
        ));
        factory.registerProvider("fake", provider);

        AgentDefinition agent = new AgentDefinition();
        agent.setName("test-agent");
        agent.setModelId("fake:m");
        agent.setSystemPrompt("sys");
        agent.setConfig(Map.of("max_loops", 5, "max_tokens", 4096));

        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", new SkillRegistry(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        LoopResult result = engine.run(agent, "hello", new ArrayList<>(), "sid", 1L);

        assertThat(provider.callsLeft()).isZero(); // 2 calls consumed; no third
        assertThat(result.getStatus()).isEqualTo("max_tokens_exhausted");
    }

    @Test
    @DisplayName("默认配置下 max_input_tokens 不强制硬停（opt-in via enforce_max_input_tokens）")
    void maxInputTokens_defaultOptIn_noHardStop() {
        LlmProviderFactory factory = new LlmProviderFactory();
        // First response inflates totalInputTokens > 500K via usage; second is text completion.
        QueueProvider provider = new QueueProvider(List.of(
                completedWithUsage("first-batch", 600_000, 100),
                completed("done", "end_turn")));
        factory.registerProvider("fake", provider);

        AgentDefinition agent = new AgentDefinition();
        agent.setName("test-agent");
        agent.setModelId("fake:m");
        agent.setSystemPrompt("sys");
        agent.setConfig(Map.of("max_loops", 3, "max_tokens", 4096));

        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", new SkillRegistry(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        LoopResult result = engine.run(agent, "hi", new ArrayList<>(), "sid", 1L);

        // Without enforce flag, loop should NOT abort with token_budget_exceeded.
        assertThat(result.getStatus()).isNotEqualTo("token_budget_exceeded");
    }

    @Test
    @DisplayName("opt-in: enforce_max_input_tokens=true → 跨迭代超过限额仍触发 token_budget_exceeded")
    void maxInputTokens_optIn_hardStops() {
        // Register an Echo tool so iter 1 can return tool_use → loop continues to iter 2
        // where the budget check fires (start-of-iteration guard).
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new EchoToolStub());

        LlmProviderFactory factory = new LlmProviderFactory();
        QueueProvider provider = new QueueProvider(List.of(
                toolUseResponseWithUsage("c1", "Echo", Map.of("v", "hi"), 600_000, 100),
                completed("done", "end_turn")));
        factory.registerProvider("fake", provider);

        AgentDefinition agent = new AgentDefinition();
        agent.setName("test-agent");
        agent.setModelId("fake:m");
        agent.setSystemPrompt("sys");
        agent.setConfig(Map.of(
                "max_loops", 5,
                "max_tokens", 4096,
                "max_input_tokens", 500_000,
                "enforce_max_input_tokens", true));

        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        LoopResult result = engine.run(agent, "hi", new ArrayList<>(), "sid", 1L);

        assertThat(result.getStatus()).isEqualTo("token_budget_exceeded");
    }

    private static LlmResponse toolUseResponseWithUsage(String id, String name,
                                                        Map<String, Object> input,
                                                        int inputTokens, int outputTokens) {
        LlmResponse r = new LlmResponse();
        r.setStopReason("tool_use");
        r.setToolUseBlocks(List.of(new com.skillforge.core.model.ToolUseBlock(id, name, input)));
        com.skillforge.core.llm.LlmResponse.Usage usage = new com.skillforge.core.llm.LlmResponse.Usage();
        usage.setInputTokens(inputTokens);
        usage.setOutputTokens(outputTokens);
        r.setUsage(usage);
        return r;
    }

    private static class EchoToolStub implements com.skillforge.core.skill.Tool {
        @Override public String getName() { return "Echo"; }
        @Override public String getDescription() { return "echo"; }
        @Override public com.skillforge.core.model.ToolSchema getToolSchema() {
            com.skillforge.core.model.ToolSchema s = new com.skillforge.core.model.ToolSchema();
            s.setName("Echo");
            s.setDescription("echo");
            s.setInputSchema(Map.of("type", "object",
                    "properties", Map.of("v", Map.of("type", "string"))));
            return s;
        }
        @Override public com.skillforge.core.skill.SkillResult execute(
                Map<String, Object> input, com.skillforge.core.skill.SkillContext ctx) {
            return com.skillforge.core.skill.SkillResult.success("ok");
        }
    }

    private static LlmResponse truncated(String partial) {
        LlmResponse r = new LlmResponse();
        r.setContent(partial);
        r.setStopReason("max_tokens");
        return r;
    }

    private static LlmResponse completed(String text, String stopReason) {
        LlmResponse r = new LlmResponse();
        r.setContent(text);
        r.setStopReason(stopReason);
        return r;
    }

    private static LlmResponse completedWithUsage(String text, int inputTokens, int outputTokens) {
        LlmResponse r = new LlmResponse();
        r.setContent(text);
        r.setStopReason("end_turn");
        com.skillforge.core.llm.LlmResponse.Usage usage = new com.skillforge.core.llm.LlmResponse.Usage();
        usage.setInputTokens(inputTokens);
        usage.setOutputTokens(outputTokens);
        r.setUsage(usage);
        return r;
    }

    private static class QueueProvider implements LlmProvider {
        private final Queue<LlmResponse> responses;

        QueueProvider(List<LlmResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        int callsLeft() {
            return responses.size();
        }

        @Override public String getName() { return "fake"; }

        @Override public LlmResponse chat(LlmRequest request) {
            return responses.remove();
        }

        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            handler.onComplete(responses.remove());
        }
    }
}
