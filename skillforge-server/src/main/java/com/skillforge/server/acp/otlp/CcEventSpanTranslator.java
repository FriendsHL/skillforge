package com.skillforge.server.acp.otlp;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceWriteRequest;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmSpanSource;
import com.skillforge.observability.domain.LlmTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Translates a single PII-filtered cc OTLP event ({@link ParsedCcEvent}, already
 * persisted as a {@code t_acp_cc_event} row) into a span on the cc sub-session's
 * trace (ACP-EXTERNAL-AGENT P2-2).
 *
 * <p>cc emits OTLP <i>log events</i>, not spans (spike-verified 2026-06-19), so
 * there is no native span parent/child chain. This translator <b>reconstructs</b> a
 * 1-level tree onto the existing {@link LlmTraceStore} model:
 * <ul>
 *   <li>One trace per cc sub-session, keyed by a deterministic {@code traceId}
 *       derived from the SkillForge sub-session id. A {@code upsertTraceStub} (its
 *       SQL is {@code INSERT ... ON CONFLICT DO NOTHING}) is issued before each span
 *       so the trace row always exists, no matter event arrival order.</li>
 *   <li>A synthetic run-root span (spanId == traceId) anchors main-thread spans.</li>
 *   <li>{@code api_request} → LLM span ({@code kind=llm}) via {@link LlmTraceStore#write}.</li>
 *   <li>{@code tool_result} → tool span ({@code kind=tool}) via {@link LlmTraceStore#writeToolSpan}.
 *       {@code tool_decision} is skipped (the result row carries name/success/duration).</li>
 *   <li>{@code subagent_completed} → a tool span named after the subagent
 *       ({@code agent_type}); the existing model has <b>no dedicated {@code SubAgent}
 *       kind</b> — SkillForge's own subagent dispatches are {@code kind=tool} named
 *       {@code "SubAgent"}, so a cc subagent is modeled the same way (a tool node
 *       under the run root that other spans nest beneath). This span doubles as the
 *       nesting anchor for that subagent's children.</li>
 *   <li>{@code user_prompt} → event span ({@code kind=event}, prompt_length only).</li>
 *   <li>{@code hook_*} / {@code plugin_loaded} / {@code mcp_server_connection} /
 *       {@code tool_decision} → SKIP (noise).</li>
 * </ul>
 *
 * <p><b>Nesting (1 level):</b> events with no {@code agent.name} (the cc main thread)
 * are children of the run root; events with {@code agent.name=<sub>} are children of
 * that subagent's anchor span (also created lazily and idempotently from the same
 * {@code agent.name}, so an {@code api_request} that arrives before its
 * {@code subagent_completed} still nests correctly). Deeper nesting is not
 * reconstructable from flat events (documented limitation — 1 level is the target).
 *
 * <p><b>Idempotency:</b> every span id is deterministic. Tool/event spans are
 * guarded by {@code existsById} inside the store, so a re-ingested event is a no-op.
 * LLM spans go through {@link LlmTraceStore#write}, which <i>SUMs</i> trace tokens
 * before its own existsById guard — so this translator checks {@link
 * LlmTraceStore#readSpan} first and skips {@code write} entirely when the LLM span
 * already exists, preventing double token/cost accounting on re-ingest.
 *
 * <p><b>Resilience:</b> {@link #translate} never throws — any failure is logged and
 * swallowed so a span-write problem can not break P2-1 ingest (the raw row is the
 * source of truth; spans are a best-effort projection).
 */
public class CcEventSpanTranslator {

    private static final Logger log = LoggerFactory.getLogger(CcEventSpanTranslator.class);

    /** Namespace prefixes for deterministic UUID derivation (stable across restarts). */
    private static final String TRACE_NS = "acp-cc-trace:";
    private static final String SUBAGENT_NS = "acp-cc-subagent:";
    private static final String SPAN_NS = "acp-cc-span:";

    /** Provider label so the span renders as a cc (Claude Code) LLM call. */
    private static final String CC_PROVIDER = "claude-code";

    private final LlmTraceStore traceStore;

    public CcEventSpanTranslator(LlmTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    /**
     * Project one already-persisted, PII-filtered cc event into a span on the cc
     * sub-session's trace. Never throws.
     *
     * @param sfSessionId the SkillForge cc sub-session id (== span/trace sessionId)
     * @param agentId     owning agent id (nullable; carried onto the span)
     * @param ev          the parsed, PII-filtered event (attributes already stripped)
     */
    public void translate(String sfSessionId, Long agentId, ParsedCcEvent ev) {
        if (sfSessionId == null || sfSessionId.isBlank() || ev == null) {
            return;
        }
        String name = ev.eventName();
        if (name == null) {
            return;
        }
        try {
            switch (classify(name)) {
                case API_REQUEST -> translateApiRequest(sfSessionId, agentId, ev);
                case TOOL_RESULT -> translateToolResult(sfSessionId, agentId, ev);
                case SUBAGENT_COMPLETED -> translateSubagentCompleted(sfSessionId, agentId, ev);
                case USER_PROMPT -> translateUserPrompt(sfSessionId, agentId, ev);
                case SKIP -> { /* hook_* / plugin_loaded / mcp_* / tool_decision / unknown */ }
            }
        } catch (RuntimeException e) {
            // Never break ingest: the raw t_acp_cc_event row is already persisted.
            log.warn("cc event→span translation failed (dropped): session={} event={}: {}",
                    sfSessionId, name, e.toString());
        }
    }

    // ───────────────────────────── classification ─────────────────────────────

    private enum Kind { API_REQUEST, TOOL_RESULT, SUBAGENT_COMPLETED, USER_PROMPT, SKIP }

    private Kind classify(String eventName) {
        // cc events arrive as "claude_code.<event>"; tolerate a missing prefix.
        String e = eventName.startsWith("claude_code.")
                ? eventName.substring("claude_code.".length())
                : eventName;
        return switch (e) {
            case "api_request" -> Kind.API_REQUEST;
            case "tool_result" -> Kind.TOOL_RESULT;
            case "subagent_completed" -> Kind.SUBAGENT_COMPLETED;
            case "user_prompt" -> Kind.USER_PROMPT;
            default -> Kind.SKIP; // tool_decision / hook_* / plugin_loaded / mcp_server_connection / ...
        };
    }

    // ───────────────────────────── translators ─────────────────────────────

    private void translateApiRequest(String sfSessionId, Long agentId, ParsedCcEvent ev) {
        Map<String, Object> a = ev.attributes();
        String traceId = traceIdFor(sfSessionId);
        ensureTraceStub(sfSessionId, agentId, ev.ts());

        // Nesting: an api_request from a subagent (agent.name present) is a child of
        // that subagent's anchor span; a main-thread api_request is a child of the run root.
        String agentName = str(a.get("agent.name"));
        String parentSpanId = parentFor(sfSessionId, agentId, agentName, ev.ts());

        String spanId = spanIdFor(sfSessionId, "api_request", ev.eventSeq(),
                firstNonBlank(str(a.get("request_id")), null));

        // Idempotency: write() SUMs trace tokens unconditionally before its span
        // existsById guard, so skip the whole call when the span already exists.
        if (traceStore.readSpan(spanId).isPresent()) {
            return;
        }

        Instant started = ev.ts() != null ? ev.ts() : Instant.now();
        long durationMs = longVal(a.get("duration_ms"), 0L);
        Instant ended = started.plusMillis(durationMs);

        int inputTokens = (int) longVal(firstAttr(a, "input_tokens", "gen_ai.usage.input_tokens"), 0L);
        int outputTokens = (int) longVal(firstAttr(a, "output_tokens", "gen_ai.usage.output_tokens"), 0L);
        Integer cacheRead = intOrNull(firstAttr(a, "cache_read_tokens", "cache_read_input_tokens"));
        Integer cacheCreation = intOrNull(firstAttr(a, "cache_creation_tokens", "cache_creation_input_tokens"));
        BigDecimal cost = decimalOrNull(a.get("cost_usd"));
        String model = str(a.get("model"));
        String requestId = str(a.get("request_id"));

        LlmTrace trace = new LlmTrace(
                traceId, sfSessionId, agentId, /* userId */ null, ccRootName(sfSessionId),
                started, ended,
                inputTokens, outputTokens, cost,
                LlmSpanSource.LIVE);

        LlmSpan span = new LlmSpan(
                spanId, traceId, parentSpanId, sfSessionId, agentId,
                CC_PROVIDER, model,
                /* iterationIndex */ 0, /* stream */ false,
                /* inputSummary */ summarizeApiRequest(agentName, a),
                /* outputSummary */ null,
                /* inputBlobRef */ null, /* outputBlobRef */ null, /* rawSseBlobRef */ null,
                /* blobStatus */ "ok",
                inputTokens, outputTokens, cacheRead, cacheCreation,
                /* usageJson */ null,
                cost, durationMs, started, ended,
                /* finishReason */ "stop", requestId,
                /* reasoningContent */ null, /* error */ null, /* errorType */ null,
                /* toolUseId */ null, /* attributes */ Map.of(),
                LlmSpanSource.LIVE,
                /* kind */ "llm", /* eventType */ null, /* name */ null);

        traceStore.write(new LlmTraceWriteRequest(trace, span));
    }

    private void translateToolResult(String sfSessionId, Long agentId, ParsedCcEvent ev) {
        Map<String, Object> a = ev.attributes();
        String traceId = traceIdFor(sfSessionId);
        ensureTraceStub(sfSessionId, agentId, ev.ts());

        String agentName = str(a.get("agent.name"));
        String parentSpanId = parentFor(sfSessionId, agentId, agentName, ev.ts());

        String toolUseId = str(a.get("tool_use_id"));
        // spanId: prefer tool_use_id (stable per tool call); fall back to event.sequence.
        String spanId = spanIdFor(sfSessionId, "tool_result", ev.eventSeq(), toolUseId);

        Instant started = ev.ts() != null ? ev.ts() : Instant.now();
        long durationMs = longVal(a.get("duration_ms"), 0L);
        Instant ended = started.plusMillis(durationMs);
        boolean success = boolVal(a.get("success"), true);
        String toolName = firstNonBlank(str(a.get("tool_name")), "tool");

        traceStore.writeToolSpan(new LlmTraceStore.ToolSpanWriteRequest(
                spanId, traceId, parentSpanId, sfSessionId, agentId,
                toolName, toolUseId,
                /* inputSummary */ sizeSummary("input", a, "input_size", "input_tokens"),
                /* outputSummary */ sizeSummary("result", a, "result_size", "output_size"),
                started, ended, durationMs,
                /* iterationIndex */ 0, success,
                /* error */ success ? null : "tool failed"));
    }

    private void translateSubagentCompleted(String sfSessionId, Long agentId, ParsedCcEvent ev) {
        Map<String, Object> a = ev.attributes();
        String traceId = traceIdFor(sfSessionId);
        ensureTraceStub(sfSessionId, agentId, ev.ts());

        // The subagent's anchor span — same deterministic id used by parentFor(), so
        // child api_request/tool spans (which referenced agent.name) point here. This
        // is a tool span (kind=tool) named after the subagent, mirroring how
        // SkillForge's own SubAgent dispatch appears in the tree.
        String agentType = firstNonBlank(
                str(a.get("agent_type")), str(a.get("agent.name")), "subagent");
        String spanId = subagentSpanId(sfSessionId, agentType);

        Instant started = ev.ts() != null ? ev.ts() : Instant.now();
        long durationMs = longVal(a.get("duration_ms"), 0L);
        Instant ended = started.plusMillis(durationMs);
        long totalTokens = longVal(a.get("total_tokens"), 0L);
        long totalToolUses = longVal(a.get("total_tool_uses"), 0L);
        String model = str(a.get("model"));

        String outputSummary = "tokens=" + totalTokens + " tools=" + totalToolUses
                + (model != null ? " model=" + model : "");

        // A subagent always nests directly under the run root (1-level tree target).
        traceStore.writeToolSpan(new LlmTraceStore.ToolSpanWriteRequest(
                spanId, traceId, rootSpanId(sfSessionId), sfSessionId, agentId,
                /* name */ "SubAgent:" + agentType, /* toolUseId */ null,
                /* inputSummary */ "agent_type=" + agentType,
                outputSummary,
                started, ended, durationMs,
                /* iterationIndex */ 0, /* success */ true, /* error */ null));
    }

    private void translateUserPrompt(String sfSessionId, Long agentId, ParsedCcEvent ev) {
        Map<String, Object> a = ev.attributes();
        String traceId = traceIdFor(sfSessionId);
        ensureTraceStub(sfSessionId, agentId, ev.ts());

        String spanId = spanIdFor(sfSessionId, "user_prompt", ev.eventSeq(), null);
        Instant started = ev.ts() != null ? ev.ts() : Instant.now();
        long promptLength = longVal(a.get("prompt_length"), 0L);

        traceStore.writeEventSpan(new LlmTraceStore.EventSpanWriteRequest(
                spanId, traceId, rootSpanId(sfSessionId), sfSessionId, agentId,
                /* eventType */ "user_prompt", /* name */ "user_prompt",
                /* inputSummary */ "prompt_length=" + promptLength,
                /* outputSummary */ null,
                started, started, /* latencyMs */ 0L,
                /* iterationIndex */ 0, /* success */ true, /* error */ null));
    }

    // ───────────────────────────── nesting / ids ─────────────────────────────

    /**
     * Resolve the parent span for an event: a subagent's anchor span when
     * {@code agentName} is set (created idempotently here so children can arrive
     * before {@code subagent_completed}), else the run root.
     */
    private String parentFor(String sfSessionId, Long agentId, String agentName, Instant ts) {
        if (agentName == null || agentName.isBlank() || isMainThread(agentName)) {
            return rootSpanId(sfSessionId);
        }
        String spanId = subagentSpanId(sfSessionId, agentName);
        // Lazily create the anchor so child spans ALWAYS have a valid parent even when
        // their subagent_completed event hasn't arrived yet — nesting safety (no orphan
        // children flattening the tree) is the priority here. Idempotent via the store's
        // existsById guard.
        //
        // Ordering trade-off (documented 1-level limitation): cc emits subagent_completed
        // AFTER the subagent's child events, so in practice a child usually arrives first
        // and this placeholder (0 duration, no totals) wins; the later subagent_completed
        // write for the SAME spanId is then skipped by the guard, so the rich totals are
        // not back-filled onto the anchor. The subagent's per-call detail still lives in
        // its (correctly-nested) child LLM/tool spans; only the rolled-up summary on the
        // anchor is lost. Back-filling would require an UPDATE path the store doesn't
        // expose — out of scope for the 1-level target.
        Instant when = ts != null ? ts : Instant.now();
        traceStore.writeToolSpan(new LlmTraceStore.ToolSpanWriteRequest(
                spanId, traceIdFor(sfSessionId), rootSpanId(sfSessionId), sfSessionId, agentId,
                "SubAgent:" + agentName, null,
                "agent_type=" + agentName, null,
                when, when, 0L,
                0, true, null));
        return spanId;
    }

    /** cc's main thread shows up either as no agent.name or a sentinel; treat both as root. */
    private boolean isMainThread(String agentName) {
        return "main".equalsIgnoreCase(agentName) || "root".equalsIgnoreCase(agentName);
    }

    /**
     * Ensure the trace row exists (idempotent INSERT ... ON CONFLICT DO NOTHING) so
     * every span has a parent trace, regardless of which event arrives first.
     */
    private void ensureTraceStub(String sfSessionId, Long agentId, Instant ts) {
        String traceId = traceIdFor(sfSessionId);
        traceStore.upsertTraceStub(new LlmTraceStore.TraceStubRequest(
                traceId, /* rootTraceId */ traceId, sfSessionId, agentId,
                /* userId */ null, ccRootName(sfSessionId),
                ts != null ? ts : Instant.now()));
    }

    /**
     * Deterministic trace id for a cc sub-session (stable, idempotent).
     *
     * <p>P2-3a: {@code public} so {@code AcpAgentRunner} can resolve the SAME trace id
     * it must finalize on run completion — the derivation is shared here (single source
     * of truth), never duplicated as a literal in the runner.
     */
    public static String traceIdFor(String sfSessionId) {
        return deterministicUuid(TRACE_NS + sfSessionId);
    }

    /** The run-root span id == traceId (matches the engine convention rootSpan.id == traceId). */
    static String rootSpanId(String sfSessionId) {
        return traceIdFor(sfSessionId);
    }

    /** Deterministic anchor span id for a subagent within a sub-session. */
    static String subagentSpanId(String sfSessionId, String agentName) {
        return deterministicUuid(SUBAGENT_NS + sfSessionId + ":" + agentName);
    }

    /**
     * Deterministic span id for a leaf event. Prefers a natural key (request_id /
     * tool_use_id) when present; otherwise falls back to the event.sequence so
     * re-ingest of the same event maps to the same span (idempotent). When neither
     * is available, derives from event kind + a best-effort discriminator so we
     * never collide unrelated events under one id.
     */
    static String spanIdFor(String sfSessionId, String kind, Long eventSeq, String naturalKey) {
        String disc = naturalKey != null && !naturalKey.isBlank()
                ? naturalKey
                : (eventSeq != null ? "seq:" + eventSeq : "kind:" + kind);
        return deterministicUuid(SPAN_NS + sfSessionId + ":" + kind + ":" + disc);
    }

    private static String deterministicUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String ccRootName(String sfSessionId) {
        return "claude-code";
    }

    // ───────────────────────────── attr helpers ─────────────────────────────

    private static String summarizeApiRequest(String agentName, Map<String, Object> a) {
        String model = str(a.get("model"));
        StringBuilder sb = new StringBuilder();
        if (model != null) sb.append("model=").append(model);
        if (agentName != null && !agentName.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("agent=").append(agentName);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String sizeSummary(String label, Map<String, Object> a, String... keys) {
        for (String k : keys) {
            Object v = a.get(k);
            if (v != null) {
                return label + "_size=" + v;
            }
        }
        return null;
    }

    private static Object firstAttr(Map<String, Object> a, String... keys) {
        for (String k : keys) {
            Object v = a.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static long longVal(Object v, long dflt) {
        if (v == null) return dflt;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Integer intOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimalOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean boolVal(Object v, boolean dflt) {
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return dflt;
    }
}
