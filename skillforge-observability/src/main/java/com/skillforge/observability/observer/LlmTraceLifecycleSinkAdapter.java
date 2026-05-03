package com.skillforge.observability.observer;

import com.skillforge.core.engine.TraceLifecycleSink;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceStore.EventSpanWriteRequest;
import com.skillforge.observability.api.LlmTraceStore.ToolSpanWriteRequest;
import com.skillforge.observability.api.LlmTraceStore.TraceFinalizeRequest;
import com.skillforge.observability.api.LlmTraceStore.TraceStubRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * OBS-2 M1: bridge between core's {@link TraceLifecycleSink} interface and the
 * observability-side {@link LlmTraceStore}. Lets {@link com.skillforge.core.engine.AgentLoopEngine}
 * call into trace lifecycle methods without depending on observability module.
 *
 * <p>Spring picks this up as a bean and the {@code @Bean AgentLoopEngine} factory wires
 * it via {@code engine.setTraceLifecycleSink(...)}.
 */
@Component
public class LlmTraceLifecycleSinkAdapter implements TraceLifecycleSink {

    private final LlmTraceStore traceStore;

    public LlmTraceLifecycleSinkAdapter(LlmTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @Override
    public void upsertTraceStub(String traceId, String rootTraceId, String sessionId, Long agentId,
                                Long userId, String agentName, Instant startedAt) {
        traceStore.upsertTraceStub(new TraceStubRequest(
                traceId, rootTraceId, sessionId, agentId, userId, agentName, startedAt));
    }

    @Override
    public void writeToolSpan(String spanId, String traceId, String parentSpanId, String sessionId,
                              Long agentId, String name, String toolUseId, String inputSummary,
                              String outputSummary, Instant startedAt, Instant endedAt, long latencyMs,
                              int iterationIndex, boolean success, String error) {
        traceStore.writeToolSpan(new ToolSpanWriteRequest(
                spanId, traceId, parentSpanId, sessionId, agentId,
                name, toolUseId, inputSummary, outputSummary,
                startedAt, endedAt, latencyMs, iterationIndex, success, error));
    }

    @Override
    public void writeEventSpan(String spanId, String traceId, String parentSpanId, String sessionId,
                               Long agentId, String eventType, String name, String inputSummary,
                               String outputSummary, Instant startedAt, Instant endedAt, long latencyMs,
                               int iterationIndex, boolean success, String error) {
        traceStore.writeEventSpan(new EventSpanWriteRequest(
                spanId, traceId, parentSpanId, sessionId, agentId,
                eventType, name, inputSummary, outputSummary,
                startedAt, endedAt, latencyMs, iterationIndex, success, error));
    }

    @Override
    public void finalizeTrace(String traceId, String status, String error, long totalDurationMs,
                              int toolCallCount, int eventCount, Instant endedAt) {
        traceStore.finalizeTrace(new TraceFinalizeRequest(
                traceId, status, error, totalDurationMs, toolCallCount, eventCount, endedAt));
    }
}
