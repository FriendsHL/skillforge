package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * OBS-2 M4 lock-in IT — {@link AgentLoopEngine} must <strong>never</strong> invoke
 * {@link TraceCollector#record(TraceSpan)} after the legacy {@code t_trace_span}
 * write path is closed.
 *
 * <p>Equivalent to a "{@code SELECT count(*) FROM t_trace_span} does not grow" check
 * on a real DB: {@code TraceCollectorImpl.record(...)} is the only entry into
 * {@code TraceSpanRepository.save(...)}, so if AgentLoopEngine never calls
 * {@code .record()} during a chat run, no row can ever land in
 * {@code t_trace_span} via the engine. We mock {@code TraceCollector} so the IT
 * needs no DB harness while still tightly coupling the assertion to the real
 * write boundary.
 *
 * <p>The same chat run also exercises the OBS-2 lifecycle sink path
 * ({@code TraceLifecycleSink.upsertTraceStub} + {@code writeToolSpan} +
 * {@code finalizeTrace}) to prove the new write path stays healthy — this is
 * the "t_llm_trace + t_llm_span 写入正常" half of the M4 self-check.
 */
@DisplayName("OBS-2 M4 — AgentLoopEngine never writes legacy t_trace_span")
class AgentLoopEngineNoTraceCollectorRecordIT {

    private TraceCollector traceCollector;
    private TraceLifecycleSink traceLifecycleSink;
    private AgentLoopEngine engine;
    private RecordingTool echoTool;

    @BeforeEach
    void setUp() {
        traceCollector = mock(TraceCollector.class);
        traceLifecycleSink = mock(TraceLifecycleSink.class);

        SkillRegistry registry = new SkillRegistry();
        echoTool = new RecordingTool();
        registry.registerTool(echoTool);

        LlmProviderFactory factory = new LlmProviderFactory();
        // Two-turn run: first an Echo tool_use, then a final text response.
        factory.registerProvider("fake", new QueueProvider(List.of(
                toolResponse("call-1", "Echo", Map.of("value", "hi"), "tool_use"),
                textResponse("done"))));

        engine = new AgentLoopEngine(factory, "fake", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        engine.setTraceCollector(traceCollector);
        engine.setTraceLifecycleSink(traceLifecycleSink);
    }

    @Test
    @DisplayName("Tool-using chat run never invokes TraceCollector.record (no t_trace_span writes)")
    void toolUsingChatRunDoesNotInvokeTraceCollectorRecord() {
        AgentDefinition agent = agent("greeter");
        LoopContext ctx = new LoopContext();
        // Mirror ChatService: caller-provided traceId so OBS-2 invariant is preserved.
        ctx.setTraceId(UUID.randomUUID().toString());

        LoopResult result = engine.run(agent, "hello", new ArrayList<>(), "sid-1", 1L, ctx);

        assertThat(result.getFinalResponse()).isEqualTo("done");
        assertThat(echoTool.calls.get())
                .as("echo tool actually executed (run reached finally block)")
                .isEqualTo(1);

        // ── M4 invariant: legacy t_trace_span write path is fully closed.
        verify(traceCollector, never()).record(any(TraceSpan.class));

        // ── OBS-2 lifecycle sink (t_llm_trace + t_llm_span) still wired.
        // OBS-4 §2.3: upsertTraceStub now takes rootTraceId as 2nd arg.
        verify(traceLifecycleSink, times(1))
                .upsertTraceStub(eq(ctx.getTraceId()), any(), eq("sid-1"), any(), any(), anyString(), any(Instant.class));
        verify(traceLifecycleSink, atLeastOnce())
                .writeToolSpan(anyString(), eq(ctx.getTraceId()), anyString(), eq("sid-1"),
                        any(), eq("Echo"), anyString(), anyString(), anyString(),
                        any(Instant.class), any(Instant.class), anyLong(), anyInt(),
                        eq(true), any());
        verify(traceLifecycleSink, times(1))
                .finalizeTrace(eq(ctx.getTraceId()), eq("ok"), any(),
                        anyLong(), anyInt(), anyInt(), any(Instant.class));
    }

    @Test
    @DisplayName("Text-only chat run still no TraceCollector.record (sanity for non-tool path)")
    void textOnlyChatRunDoesNotInvokeTraceCollectorRecord() {
        // Replace queue with a single text-only response.
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", new QueueProvider(List.of(textResponse("just text"))));
        AgentLoopEngine textEngine = new AgentLoopEngine(factory, "fake", new SkillRegistry(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        textEngine.setTraceCollector(traceCollector);
        textEngine.setTraceLifecycleSink(traceLifecycleSink);

        AgentDefinition agent = agent("greeter");
        LoopContext ctx = new LoopContext();
        ctx.setTraceId(UUID.randomUUID().toString());

        LoopResult result = textEngine.run(agent, "hi", new ArrayList<>(), "sid-2", 1L, ctx);

        assertThat(result.getFinalResponse()).isEqualTo("just text");
        verify(traceCollector, never()).record(any(TraceSpan.class));
        // upsertTraceStub + finalizeTrace still happen on the new path.
        // OBS-4 §2.3: upsertTraceStub now takes rootTraceId as 2nd arg.
        verify(traceLifecycleSink, times(1))
                .upsertTraceStub(eq(ctx.getTraceId()), any(), eq("sid-2"), any(), any(), anyString(), any(Instant.class));
        verify(traceLifecycleSink, atLeast(1))
                .finalizeTrace(eq(ctx.getTraceId()), anyString(), any(),
                        anyLong(), anyInt(), anyInt(), any(Instant.class));
    }

    private static AgentDefinition agent(String name) {
        AgentDefinition a = new AgentDefinition();
        a.setName(name);
        a.setModelId("fake:model");
        a.setSystemPrompt("You are a test agent.");
        a.setConfig(Map.of("max_loops", 3));
        return a;
    }

    private static LlmResponse toolResponse(String id, String name, Map<String, Object> input, String stopReason) {
        LlmResponse response = new LlmResponse();
        response.setStopReason(stopReason);
        response.setToolUseBlocks(List.of(new ToolUseBlock(id, name, input)));
        return response;
    }

    private static LlmResponse textResponse(String content) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("end_turn");
        response.setContent(content);
        return response;
    }

    private static class QueueProvider implements LlmProvider {
        private final Queue<LlmResponse> responses;

        QueueProvider(List<LlmResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override public String getName() { return "fake"; }

        @Override public LlmResponse chat(LlmRequest request) {
            return responses.remove();
        }

        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            handler.onComplete(responses.remove());
        }
    }

    private static class RecordingTool implements Tool {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public String getName() { return "Echo"; }
        @Override public String getDescription() { return "test echo tool"; }

        @Override public ToolSchema getToolSchema() {
            ToolSchema schema = new ToolSchema();
            schema.setName("Echo");
            schema.setDescription("test echo tool");
            schema.setInputSchema(Map.of(
                    "type", "object",
                    "properties", Map.of("value", Map.of("type", "string")),
                    "required", List.of("value")));
            return schema;
        }

        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
            calls.incrementAndGet();
            return SkillResult.success("echo " + input.get("value"));
        }
    }
}
