package com.skillforge.server.acp.otlp;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceWriteRequest;
import com.skillforge.observability.domain.LlmSpan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CcEventSpanTranslator} (ACP-EXTERNAL-AGENT P2-2) — given the
 * captured cc event shapes, assert the right {@link LlmTraceStore} calls with correct
 * kind / fields / traceId, subagent nesting (parentSpanId), idempotency, and that a
 * span-write failure never propagates (ingest stays resilient).
 */
class CcEventSpanTranslatorTest {

    private static final String SESSION = "sub-1";

    private LlmTraceStore store;
    private CcEventSpanTranslator translator;

    @BeforeEach
    void setUp() {
        store = mock(LlmTraceStore.class);
        when(store.readSpan(any())).thenReturn(Optional.empty());
        translator = new CcEventSpanTranslator(store);
    }

    private ParsedCcEvent event(String name, Long seq, Map<String, Object> attrs) {
        return new ParsedCcEvent(name, SESSION, "cc-1", seq, Instant.parse("2026-06-20T00:00:00Z"), attrs);
    }

    private Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("api_request → LLM span (kind=llm) on the sub-session's trace, tokens/cost/model mapped, nested under run root")
    void apiRequest_writesLlmSpan() {
        translator.translate(SESSION, 7L, event("claude_code.api_request", 1L, attrs(
                "model", "claude-opus",
                "input_tokens", 100L,
                "output_tokens", 50L,
                "cache_read_tokens", 20L,
                "cost_usd", 0.01,
                "duration_ms", 1200L,
                "request_id", "req-abc")));

        verify(store).upsertTraceStub(any());
        ArgumentCaptor<LlmTraceWriteRequest> cap =
                ArgumentCaptor.forClass(LlmTraceWriteRequest.class);
        verify(store).write(cap.capture());

        LlmSpan span = cap.getValue().span();
        String traceId = CcEventSpanTranslator.traceIdFor(SESSION);
        assertThat(span.traceId()).isEqualTo(traceId);
        assertThat(cap.getValue().trace().traceId()).isEqualTo(traceId);
        assertThat(span.sessionId()).isEqualTo(SESSION);
        assertThat(span.kind()).isEqualTo("llm");
        assertThat(span.model()).isEqualTo("claude-opus");
        assertThat(span.inputTokens()).isEqualTo(100);
        assertThat(span.outputTokens()).isEqualTo(50);
        assertThat(span.cacheReadTokens()).isEqualTo(20);
        assertThat(span.costUsd().doubleValue()).isEqualTo(0.01);
        assertThat(span.requestId()).isEqualTo("req-abc");
        // main-thread (no agent.name) → child of the run root span.
        assertThat(span.parentSpanId()).isEqualTo(CcEventSpanTranslator.rootSpanId(SESSION));
        // trace aggregate carries the per-call delta (DB SUMs across calls).
        assertThat(cap.getValue().trace().totalInputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("tool_result → tool span (kind=tool) keyed by tool_use_id, success+duration mapped, under run root")
    void toolResult_writesToolSpan() {
        translator.translate(SESSION, null, event("claude_code.tool_result", 2L, attrs(
                "tool_name", "Bash",
                "tool_use_id", "tu-1",
                "success", true,
                "duration_ms", 300L)));

        ArgumentCaptor<LlmTraceStore.ToolSpanWriteRequest> cap =
                ArgumentCaptor.forClass(LlmTraceStore.ToolSpanWriteRequest.class);
        verify(store).writeToolSpan(cap.capture());
        LlmTraceStore.ToolSpanWriteRequest req = cap.getValue();
        assertThat(req.traceId()).isEqualTo(CcEventSpanTranslator.traceIdFor(SESSION));
        assertThat(req.sessionId()).isEqualTo(SESSION);
        assertThat(req.name()).isEqualTo("Bash");
        assertThat(req.toolUseId()).isEqualTo("tu-1");
        assertThat(req.success()).isTrue();
        assertThat(req.latencyMs()).isEqualTo(300L);
        assertThat(req.parentSpanId()).isEqualTo(CcEventSpanTranslator.rootSpanId(SESSION));
        verify(store, never()).write(any());
    }

    @Test
    @DisplayName("subagent_completed → tool span named SubAgent:<type>, totals in summary, anchored under run root")
    void subagentCompleted_writesSubAgentSpan() {
        translator.translate(SESSION, null, event("claude_code.subagent_completed", 3L, attrs(
                "agent_type", "code-reviewer",
                "total_tokens", 500L,
                "total_tool_uses", 4L,
                "duration_ms", 8000L,
                "model", "claude-sonnet")));

        ArgumentCaptor<LlmTraceStore.ToolSpanWriteRequest> cap =
                ArgumentCaptor.forClass(LlmTraceStore.ToolSpanWriteRequest.class);
        verify(store).writeToolSpan(cap.capture());
        LlmTraceStore.ToolSpanWriteRequest req = cap.getValue();
        assertThat(req.spanId()).isEqualTo(
                CcEventSpanTranslator.subagentSpanId(SESSION, "code-reviewer"));
        assertThat(req.name()).isEqualTo("SubAgent:code-reviewer");
        assertThat(req.parentSpanId()).isEqualTo(CcEventSpanTranslator.rootSpanId(SESSION));
        assertThat(req.outputSummary()).contains("tokens=500").contains("tools=4").contains("claude-sonnet");
    }

    @Test
    @DisplayName("subagent's api_request nests under that subagent's anchor span (1-level tree)")
    void subagentApiRequest_nestsUnderSubagentSpan() {
        translator.translate(SESSION, null, event("claude_code.api_request", 4L, attrs(
                "model", "claude-sonnet",
                "input_tokens", 10L,
                "agent.name", "code-reviewer")));

        // The lazily-created subagent anchor + the api_request LLM span.
        ArgumentCaptor<LlmTraceStore.ToolSpanWriteRequest> anchor =
                ArgumentCaptor.forClass(LlmTraceStore.ToolSpanWriteRequest.class);
        verify(store).writeToolSpan(anchor.capture());
        String subSpanId = CcEventSpanTranslator.subagentSpanId(SESSION, "code-reviewer");
        assertThat(anchor.getValue().spanId()).isEqualTo(subSpanId);
        assertThat(anchor.getValue().parentSpanId()).isEqualTo(CcEventSpanTranslator.rootSpanId(SESSION));

        ArgumentCaptor<LlmTraceWriteRequest> llm =
                ArgumentCaptor.forClass(LlmTraceWriteRequest.class);
        verify(store).write(llm.capture());
        assertThat(llm.getValue().span().parentSpanId()).isEqualTo(subSpanId);
    }

    @Test
    @DisplayName("user_prompt → event span (kind=event) with prompt_length only, no content")
    void userPrompt_writesEventSpan() {
        translator.translate(SESSION, null, event("claude_code.user_prompt", 5L, attrs(
                "prompt_length", 37L)));

        ArgumentCaptor<LlmTraceStore.EventSpanWriteRequest> cap =
                ArgumentCaptor.forClass(LlmTraceStore.EventSpanWriteRequest.class);
        verify(store).writeEventSpan(cap.capture());
        assertThat(cap.getValue().eventType()).isEqualTo("user_prompt");
        assertThat(cap.getValue().inputSummary()).isEqualTo("prompt_length=37");
        assertThat(cap.getValue().parentSpanId()).isEqualTo(CcEventSpanTranslator.rootSpanId(SESSION));
    }

    @Test
    @DisplayName("hook_* / plugin_loaded / mcp_* / tool_decision are skipped (no span written)")
    void noiseEvents_skipped() {
        translator.translate(SESSION, null, event("claude_code.tool_decision", 6L, attrs("tool_name", "Bash")));
        translator.translate(SESSION, null, event("claude_code.hook_executed", 7L, attrs()));
        translator.translate(SESSION, null, event("claude_code.plugin_loaded", 8L, attrs()));
        translator.translate(SESSION, null, event("claude_code.mcp_server_connection", 9L, attrs()));

        verify(store, never()).write(any());
        verify(store, never()).writeToolSpan(any());
        verify(store, never()).writeEventSpan(any());
        verify(store, never()).upsertTraceStub(any());
    }

    @Test
    @DisplayName("idempotent: re-ingesting the same api_request (existing span) skips write() so trace tokens are not double-counted")
    void apiRequest_idempotentOnDuplicate() {
        Map<String, Object> a = attrs(
                "model", "claude-opus", "input_tokens", 100L, "request_id", "req-abc", "duration_ms", 5L);
        ParsedCcEvent ev = event("claude_code.api_request", 1L, a);

        // First ingest: span does not exist → write happens.
        when(store.readSpan(any())).thenReturn(Optional.empty());
        translator.translate(SESSION, null, ev);
        verify(store, times(1)).write(any());

        // Second ingest: span now exists → write() must be skipped (no double SUM).
        when(store.readSpan(any())).thenReturn(Optional.of(mock(LlmSpan.class)));
        translator.translate(SESSION, null, ev);
        verify(store, times(1)).write(any()); // still 1
    }

    @Test
    @DisplayName("tool span ids are deterministic for the same tool_use_id (store existsById guard makes re-ingest a no-op)")
    void toolResult_deterministicSpanId() {
        ParsedCcEvent ev = event("claude_code.tool_result", 2L, attrs(
                "tool_name", "Bash", "tool_use_id", "tu-1", "success", true));

        translator.translate(SESSION, null, ev);
        translator.translate(SESSION, null, ev);

        ArgumentCaptor<LlmTraceStore.ToolSpanWriteRequest> cap =
                ArgumentCaptor.forClass(LlmTraceStore.ToolSpanWriteRequest.class);
        verify(store, times(2)).writeToolSpan(cap.capture());
        // Both calls carry the SAME deterministic spanId → store's existsById guard dedupes.
        assertThat(cap.getAllValues().get(0).spanId())
                .isEqualTo(cap.getAllValues().get(1).spanId());
    }

    @Test
    @DisplayName("resilient: a store failure during translation never propagates (ingest stays alive)")
    void translate_neverThrows() {
        when(store.readSpan(any())).thenThrow(new RuntimeException("db down"));
        // Must not throw.
        translator.translate(SESSION, null, event("claude_code.api_request", 1L, attrs("model", "x")));
        // tool path also resilient
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(store).writeToolSpan(any());
        translator.translate(SESSION, null, event("claude_code.tool_result", 2L, attrs("tool_use_id", "tu-9")));
    }

    @Test
    @DisplayName("blank session / null event → no-op")
    void guards() {
        translator.translate(null, null, event("claude_code.api_request", 1L, attrs()));
        translator.translate("  ", null, event("claude_code.api_request", 1L, attrs()));
        translator.translate(SESSION, null, null);
        verify(store, never()).write(any());
        verify(store, never()).writeToolSpan(any());
    }
}
