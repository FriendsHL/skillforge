package com.skillforge.observability.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.observer.LlmCallContext;
import com.skillforge.core.llm.observer.LlmCallObserver;
import com.skillforge.core.llm.observer.RawHttpRequest;
import com.skillforge.core.llm.observer.RawHttpResponse;
import com.skillforge.core.llm.observer.RawStreamCapture;
import com.skillforge.observability.api.BlobRef;
import com.skillforge.observability.api.BlobStore;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceWriteRequest;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmSpanSource;
import com.skillforge.observability.domain.LlmTrace;
import com.skillforge.observability.domain.ProviderName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Plan §4.5 — observability 主入口 observer。
 *
 * <p>每次 LLM call 完成后把 blob 落盘 + DB 写入异步打包到 {@code llmObservabilityExecutor}。
 * blob 落盘失败 → 仍写 DB row + {@code blob_status='write_failed'}（plan §4.5）。
 *
 * <p>R2-W3：state map 在 {@link #onStreamComplete} / {@link #onError} 同步阶段 remove 防泄漏。
 */
@Component
public class TraceLlmCallObserver implements LlmCallObserver {

    private static final Logger log = LoggerFactory.getLogger(TraceLlmCallObserver.class);

    /** 32 KB 摘要上限。 */
    private static final int SUMMARY_CAP_BYTES = 32 * 1024;
    /** 单 blob 50 MB hard cap (plan §4.5 step 3 / R2-W4). */
    private static final long BLOB_HARD_CAP_BYTES = 50L * 1024L * 1024L;

    private final BlobStore blobStore;
    private final LlmTraceStore traceStore;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper objectMapper;

    /** R2-W3: per-spanId state for streaming callers. */
    private final Map<String, CallState> state = new java.util.concurrent.ConcurrentHashMap<>();

    public TraceLlmCallObserver(BlobStore blobStore,
                                LlmTraceStore traceStore,
                                @Qualifier("llmObservabilityExecutor") ThreadPoolTaskExecutor executor,
                                @Autowired ObjectMapper objectMapper) {
        this.blobStore = blobStore;
        this.traceStore = traceStore;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @Override
    public void beforeCall(LlmCallContext ctx, RawHttpRequest request) {
        if (ctx == null || ctx.spanId() == null) return;
        CallState s = new CallState(Instant.now(), copySanitized(request));
        state.put(ctx.spanId(), s);
    }

    @Override
    public void afterCall(LlmCallContext ctx, RawHttpResponse response, LlmResponse parsed) {
        if (ctx == null || ctx.spanId() == null) return;
        CallState s = state.remove(ctx.spanId());
        if (s == null) return;
        // Non-stream success path: schedule async write with parsed body.
        scheduleWrite(ctx, s, response == null ? null : copySanitized(response),
                parsed, null, false);
    }

    @Override
    public void onStreamComplete(LlmCallContext ctx, RawStreamCapture capture, LlmResponse parsed) {
        if (ctx == null || ctx.spanId() == null) return;
        CallState s = state.remove(ctx.spanId());
        if (s == null) return;
        scheduleWrite(ctx, s, null, parsed, capture, false);
    }

    @Override
    public void onError(LlmCallContext ctx, Throwable error, RawStreamCapture partial) {
        if (ctx == null || ctx.spanId() == null) return;
        CallState s = state.remove(ctx.spanId());
        if (s == null) {
            // beforeCall not yet seen (e.g. buildRequestBody threw); still write a minimal error row.
            s = new CallState(Instant.now(), null);
        }
        final CallState fs = s;
        final Throwable err = error == null ? new RuntimeException("unknown") : error;
        scheduleWrite(ctx, fs, null, null, partial, true, err);
    }

    private void scheduleWrite(LlmCallContext ctx, CallState s,
                               RawHttpResponse response, LlmResponse parsed,
                               RawStreamCapture capture, boolean isError) {
        scheduleWrite(ctx, s, response, parsed, capture, isError, null);
    }

    private void scheduleWrite(LlmCallContext ctx, CallState s,
                               RawHttpResponse response, LlmResponse parsed,
                               RawStreamCapture capture, boolean isError, Throwable err) {
        executor.submit(() -> {
            try {
                doWrite(ctx, s, response, parsed, capture, isError, err);
            } catch (Throwable t) {
                log.warn("Observability write failed (dropped): traceId={} spanId={}",
                        ctx.traceId(), ctx.spanId(), t);
            }
        });
    }

    private void doWrite(LlmCallContext ctx, CallState s,
                         RawHttpResponse response, LlmResponse parsed,
                         RawStreamCapture capture, boolean isError, Throwable err) {
        Instant now = Instant.now();
        // -- 1. compose payloads + summaries
        byte[] reqBytes = s.request != null ? s.request.body() : new byte[0];
        byte[] respBytes = response != null
                ? response.body()
                : (capture != null ? capture.accumulatedJson() : new byte[0]);
        byte[] sseBytes = capture != null ? capture.rawSse() : new byte[0];

        // 50 MB hard cap
        boolean truncated = false;
        if (reqBytes.length > BLOB_HARD_CAP_BYTES) {
            reqBytes = java.util.Arrays.copyOf(reqBytes, (int) BLOB_HARD_CAP_BYTES);
            truncated = true;
        }
        if (respBytes.length > BLOB_HARD_CAP_BYTES) {
            respBytes = java.util.Arrays.copyOf(respBytes, (int) BLOB_HARD_CAP_BYTES);
            truncated = true;
        }
        if (sseBytes.length > BLOB_HARD_CAP_BYTES) {
            sseBytes = java.util.Arrays.copyOf(sseBytes, (int) BLOB_HARD_CAP_BYTES);
            truncated = true;
        }

        String inputSummary = summarize(reqBytes);
        String outputSummary = summarize(respBytes);

        // -- 2. write blobs
        String inputRef = null, outputRef = null, sseRef = null;
        String blobStatus = truncated ? "truncated" : "ok";
        boolean writeFailed = false;
        try {
            if (reqBytes.length > 0) {
                inputRef = blobStore.write(BlobRef.of(now, ctx.traceId(), ctx.spanId(), "request"), reqBytes);
            }
            if (respBytes.length > 0) {
                outputRef = blobStore.write(BlobRef.of(now, ctx.traceId(), ctx.spanId(), "response"), respBytes);
            }
            if (sseBytes.length > 0 && ctx.stream()) {
                sseRef = blobStore.write(BlobRef.of(now, ctx.traceId(), ctx.spanId(), "sse"), sseBytes);
            }
        } catch (Exception e) {
            log.warn("Blob write failed: traceId={} spanId={}", ctx.traceId(), ctx.spanId(), e);
            writeFailed = true;
            inputRef = outputRef = sseRef = null;
            blobStatus = "write_failed";
        }

        // -- 3. compose domain LlmSpan + LlmTrace, then store.write
        Instant startedAt = s.startedAt != null ? s.startedAt : now;
        Instant endedAt = now;
        long latency = Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli());

        int inTokens = parsed != null && parsed.getUsage() != null
                ? parsed.getUsage().getInputTokens() : 0;
        int outTokens = parsed != null && parsed.getUsage() != null
                ? parsed.getUsage().getOutputTokens() : 0;

        Map<String, Object> attrs = new HashMap<>();
        if (capture != null && capture.sseTruncated()) attrs.put("sse_truncated", true);
        if (truncated) attrs.put("blob_truncated", true);

        String provider = ProviderName.coerce(ctx.providerName());
        if (!ProviderName.isCanonical(ctx.providerName())) {
            log.warn("Non-canonical provider name '{}' coerced to 'unknown' (spanId={})",
                    ctx.providerName(), ctx.spanId());
        }

        LlmSpan span = new LlmSpan(
                ctx.spanId(), ctx.traceId(), ctx.parentSpanId(), ctx.sessionId(),
                ctx.agentId(), provider, ctx.modelId(),
                ctx.iterationIndex(), ctx.stream(),
                inputSummary, outputSummary,
                inputRef, outputRef, sseRef, blobStatus,
                inTokens, outTokens, null,
                null, /* usageJson */
                null, /* costUsd */
                latency,
                startedAt, endedAt,
                parsed != null ? parsed.getStopReason() : null,
                null,
                parsed != null ? parsed.getReasoningContent() : null,
                isError && err != null ? safeMessage(err) : null,
                isError && err != null ? err.getClass().getSimpleName() : null,
                null,
                attrs,
                LlmSpanSource.LIVE);

        LlmTrace trace = new LlmTrace(
                ctx.traceId(), ctx.sessionId(),
                ctx.agentId(), ctx.userId(),
                /* rootName: empty here; AGENT_LOOP root sets it on first insert path; UPDATE won't override */
                null,
                startedAt, endedAt,
                inTokens, outTokens, BigDecimal.ZERO,
                LlmSpanSource.LIVE);

        try {
            traceStore.write(new LlmTraceWriteRequest(trace, span));
        } catch (Exception e) {
            log.warn("LlmTraceStore.write failed (dropped): traceId={} spanId={}",
                    ctx.traceId(), ctx.spanId(), e);
        }
        if (writeFailed) {
            log.warn("Span persisted with blob_status=write_failed: spanId={}", ctx.spanId());
        }
    }

    /** Truncate to 32 KB UTF-8 byte budget; append "...[truncated]" marker if cut. */
    private static String summarize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        if (bytes.length <= SUMMARY_CAP_BYTES) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        byte[] head = new byte[SUMMARY_CAP_BYTES];
        System.arraycopy(bytes, 0, head, 0, SUMMARY_CAP_BYTES);
        return new String(head, StandardCharsets.UTF_8) + "\n...[truncated]";
    }

    private static RawHttpRequest copySanitized(RawHttpRequest req) {
        if (req == null) return null;
        return new RawHttpRequest(req.method(), req.url(),
                HeaderSanitizer.sanitize(req.headers()),
                req.body(), req.contentType());
    }

    private static RawHttpResponse copySanitized(RawHttpResponse resp) {
        if (resp == null) return null;
        return new RawHttpResponse(resp.statusCode(),
                HeaderSanitizer.sanitize(resp.headers()),
                resp.body(), resp.contentType());
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        // Avoid leaking very long stacktrace strings
        return m.length() > 4096 ? m.substring(0, 4096) + "..." : m;
    }

    /** Per-spanId in-flight call state (cleaned in terminal hooks; R2-W3). */
    private static final class CallState {
        final Instant startedAt;
        final RawHttpRequest request;

        CallState(Instant startedAt, RawHttpRequest request) {
            this.startedAt = startedAt;
            this.request = request;
        }
    }

    /** Test-only accessor: in-flight state map size. */
    public int inFlightCount() { return state.size(); }
}
