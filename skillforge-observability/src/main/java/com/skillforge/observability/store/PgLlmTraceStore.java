package com.skillforge.observability.store;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceWriteRequest;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmSpanSource;
import com.skillforge.observability.domain.LlmTrace;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Plan §3.1 + §4.5 R2-B3 — PG 实现：
 * upsertTrace 用原生 SQL ON CONFLICT，DB 端累加避免 read-modify-write race。
 *
 * <p>OBS-2 M1：新增 5 个方法（{@link #upsertTraceStub} / {@link #writeToolSpan} /
 * {@link #writeEventSpan} / {@link #finalizeTrace} / {@link #listSpansByTrace}）。
 * 异步方法走 {@code llmObservabilityExecutor}（与 OBS-1 同一线程池），失败 log.warn + drop。
 */
@Service
public class PgLlmTraceStore implements LlmTraceStore {

    private static final Logger log = LoggerFactory.getLogger(PgLlmTraceStore.class);

    private static final String UPSERT_TRACE_SQL = """
            INSERT INTO t_llm_trace (
              trace_id, session_id, agent_id, user_id, root_name,
              started_at, ended_at, total_input_tokens, total_output_tokens, total_cost_usd,
              source, created_at
            ) VALUES (
              :traceId, :sessionId, :agentId, :userId, :rootName,
              :startedAt, :endedAt, :inDelta, :outDelta, :costDelta,
              :source, now()
            )
            ON CONFLICT (trace_id) DO UPDATE SET
              ended_at            = GREATEST(t_llm_trace.ended_at, EXCLUDED.ended_at),
              total_input_tokens  = t_llm_trace.total_input_tokens  + EXCLUDED.total_input_tokens,
              total_output_tokens = t_llm_trace.total_output_tokens + EXCLUDED.total_output_tokens,
              total_cost_usd      = COALESCE(t_llm_trace.total_cost_usd, 0) + COALESCE(EXCLUDED.total_cost_usd, 0)
            """;

    /** OBS-2 M1 §B.3 — trace stub 同步 INSERT；DO NOTHING 让二次调用幂等。 */
    private static final String INSERT_TRACE_STUB_SQL = """
            INSERT INTO t_llm_trace (
              trace_id, session_id, agent_id, user_id, agent_name, root_name,
              status, started_at, total_input_tokens, total_output_tokens,
              total_duration_ms, tool_call_count, event_count,
              source, created_at
            ) VALUES (
              :traceId, :sessionId, :agentId, :userId, :agentName, :agentName,
              'running', :startedAt, 0, 0,
              0, 0, 0,
              'live', now()
            )
            ON CONFLICT (trace_id) DO NOTHING
            """;

    /**
     * OBS-2 M1 §B.3 R2-B3 — finalizeTrace UPDATE 加 {@code status='running'} 守卫，
     * 让 terminal 状态不被覆盖（幂等）。
     */
    private static final String FINALIZE_TRACE_SQL = """
            UPDATE t_llm_trace
            SET status            = :status,
                error             = :error,
                total_duration_ms = :totalDurationMs,
                tool_call_count   = :toolCallCount,
                event_count       = :eventCount,
                ended_at          = :endedAt
            WHERE trace_id = :traceId
              AND status   = 'running'
            """;

    @PersistenceContext
    private EntityManager em;

    private final LlmTraceRepository traceRepository;
    private final LlmSpanRepository spanRepository;
    private final ThreadPoolTaskExecutor executor;
    /**
     * OBS-2 M1: explicit TransactionTemplate so async methods (writeToolSpan / writeEventSpan /
     * finalizeTrace) running on {@code llmObservabilityExecutor} get a real transaction
     * boundary. {@code @Transactional} on protected methods called via {@code this.do…} from
     * within {@code executor.submit(...)} does NOT trigger Spring AOP (self-invocation
     * footgun) — TransactionTemplate avoids the AOP path entirely.
     */
    private final TransactionTemplate txTemplate;

    public PgLlmTraceStore(LlmTraceRepository traceRepository,
                           LlmSpanRepository spanRepository,
                           @Qualifier("llmObservabilityExecutor") ThreadPoolTaskExecutor executor,
                           PlatformTransactionManager transactionManager) {
        this.traceRepository = traceRepository;
        this.spanRepository = spanRepository;
        this.executor = executor;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional
    public void write(LlmTraceWriteRequest request) {
        upsertTrace(request.trace());
        insertSpan(request.span());
    }

    private void upsertTrace(LlmTrace t) {
        em.createNativeQuery(UPSERT_TRACE_SQL)
                .setParameter("traceId", t.traceId())
                .setParameter("sessionId", t.sessionId())
                .setParameter("agentId", t.agentId())
                .setParameter("userId", t.userId())
                .setParameter("rootName", t.rootName())
                .setParameter("startedAt", t.startedAt())
                .setParameter("endedAt", t.endedAt())
                .setParameter("inDelta", t.totalInputTokens())
                .setParameter("outDelta", t.totalOutputTokens())
                .setParameter("costDelta", t.totalCostUsd())
                .setParameter("source", t.source().wireValue())
                .executeUpdate();
    }

    private void insertSpan(LlmSpan s) {
        // live path: spanId is freshly generated → no PK conflict; legacy ETL goes through SQL migration.
        if (spanRepository.existsById(s.spanId())) {
            log.debug("LlmSpan already exists, skipping insert: spanId={}", s.spanId());
            return;
        }
        LlmSpanEntity e = new LlmSpanEntity();
        e.setSpanId(s.spanId());
        e.setTraceId(s.traceId());
        e.setParentSpanId(s.parentSpanId());
        e.setSessionId(s.sessionId());
        e.setAgentId(s.agentId());
        e.setProvider(s.provider());
        e.setModel(s.model());
        e.setIterationIndex(s.iterationIndex());
        e.setStream(s.stream());
        e.setInputSummary(s.inputSummary());
        e.setOutputSummary(s.outputSummary());
        e.setInputBlobRef(s.inputBlobRef());
        e.setOutputBlobRef(s.outputBlobRef());
        e.setRawSseBlobRef(s.rawSseBlobRef());
        e.setBlobStatus(s.blobStatus());
        e.setInputTokens(s.inputTokens());
        e.setOutputTokens(s.outputTokens());
        e.setCacheReadTokens(s.cacheReadTokens());
        e.setUsageJson(s.usageJson());
        e.setCostUsd(s.costUsd());
        e.setLatencyMs(s.latencyMs());
        e.setStartedAt(s.startedAt());
        e.setEndedAt(s.endedAt());
        e.setFinishReason(s.finishReason());
        e.setRequestId(s.requestId());
        e.setReasoningContent(s.reasoningContent());
        e.setError(s.error());
        e.setErrorType(s.errorType());
        e.setToolUseId(s.toolUseId());
        e.setSource(s.source().wireValue());
        e.setKind(s.kind());
        e.setEventType(s.eventType());
        e.setName(s.name());
        e.setCreatedAt(Instant.now());
        // attributes_json serialized by caller into LlmSpan if needed; this layer doesn't re-encode.
        spanRepository.save(e);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TraceWithSpans> readByTraceId(String traceId) {
        return traceRepository.findById(traceId).map(te -> {
            List<LlmSpan> spans = new ArrayList<>();
            for (LlmSpanEntity se : spanRepository.findByTraceIdOrderByStartedAtAsc(traceId)) {
                spans.add(toDomain(se));
            }
            return new TraceWithSpans(toDomain(te), spans);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<LlmSpan> listSpansBySession(String sessionId, Instant since, int limit) {
        List<LlmSpanEntity> raw = since != null
                ? spanRepository.findBySessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(sessionId, since)
                : spanRepository.findBySessionIdOrderByStartedAtAsc(sessionId);
        List<LlmSpan> out = new ArrayList<>();
        int n = 0;
        for (LlmSpanEntity e : raw) {
            if (n++ >= limit) break;
            out.add(toDomain(e));
        }
        return out;
    }

    /**
     * OBS-2 M3: list spans for a session, narrowed to {@code kinds} when provided, paginated.
     *
     * <p>Used by {@code GET /api/observability/sessions/{id}/spans} after M3 cut-over.
     *
     * <p>r2 W-4 fix: null/empty {@code kinds} now defaults to all three kinds and routes
     * through the Pageable-aware repository method, so the SQL LIMIT always applies.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LlmSpan> listSpansBySession(String sessionId, Set<String> kinds, Instant since, int limit) {
        int effectiveLimit = Math.max(1, limit);
        org.springframework.data.domain.Pageable page =
                org.springframework.data.domain.PageRequest.of(0, effectiveLimit);
        Set<String> effectiveKinds = (kinds == null || kinds.isEmpty())
                ? Set.of("llm", "tool", "event")
                : kinds;
        List<LlmSpanEntity> raw = since != null
                ? spanRepository.findBySessionIdAndKindInAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                        sessionId, effectiveKinds, since, page)
                : spanRepository.findBySessionIdAndKindInOrderByStartedAtAsc(sessionId, effectiveKinds, page);
        List<LlmSpan> out = new ArrayList<>(raw.size());
        for (LlmSpanEntity e : raw) {
            out.add(toDomain(e));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LlmSpan> readSpan(String spanId) {
        return spanRepository.findById(spanId).map(this::toDomain);
    }

    // ===== OBS-2 M1: 新增方法实现 =====

    /**
     * §B.3 — 同步 + @Transactional。Engine 调用处必须包 try-catch 隔离 DB 故障（§C.2 R2-B1）。
     */
    @Override
    @Transactional
    public void upsertTraceStub(TraceStubRequest request) {
        em.createNativeQuery(INSERT_TRACE_STUB_SQL)
                .setParameter("traceId", request.traceId())
                .setParameter("sessionId", request.sessionId())
                .setParameter("agentId", request.agentId())
                .setParameter("userId", request.userId())
                .setParameter("agentName", request.agentName())
                .setParameter("startedAt", request.startedAt())
                .executeUpdate();
    }

    /**
     * §B.3 — 异步执行；失败 log.warn + drop（与 OBS-1 pattern 一致）。
     */
    @Override
    public void writeToolSpan(ToolSpanWriteRequest request) {
        executor.submit(() -> {
            try {
                txTemplate.executeWithoutResult(status -> doWriteToolSpan(request));
            } catch (Exception e) {
                log.warn("writeToolSpan failed (dropped): traceId={} spanId={}",
                        request.traceId(), request.spanId(), e);
            }
        });
    }

    private void doWriteToolSpan(ToolSpanWriteRequest request) {
        if (spanRepository.existsById(request.spanId())) {
            log.debug("LlmSpan (tool) already exists, skipping insert: spanId={}", request.spanId());
            return;
        }
        LlmSpanEntity e = new LlmSpanEntity();
        e.setSpanId(request.spanId());
        e.setTraceId(request.traceId());
        e.setParentSpanId(request.parentSpanId());
        e.setSessionId(request.sessionId());
        e.setAgentId(request.agentId());
        e.setProvider(null);
        e.setModel(null);
        e.setIterationIndex(request.iterationIndex());
        e.setStream(false);
        e.setInputSummary(request.inputSummary());
        e.setOutputSummary(request.outputSummary());
        e.setBlobStatus("ok");
        e.setInputTokens(0);
        e.setOutputTokens(0);
        e.setLatencyMs(request.latencyMs());
        e.setStartedAt(request.startedAt());
        e.setEndedAt(request.endedAt());
        e.setFinishReason(request.success() ? "success" : "error");
        e.setError(request.error());
        e.setErrorType(request.success() ? null : "tool_error");
        e.setToolUseId(request.toolUseId());
        e.setSource(LlmSpanSource.LIVE.wireValue());
        e.setKind("tool");
        e.setEventType(null);
        e.setName(request.name());
        e.setCreatedAt(Instant.now());
        spanRepository.save(e);
    }

    /**
     * §B.3 — 异步执行；失败 log.warn + drop。
     */
    @Override
    public void writeEventSpan(EventSpanWriteRequest request) {
        executor.submit(() -> {
            try {
                txTemplate.executeWithoutResult(status -> doWriteEventSpan(request));
            } catch (Exception e) {
                log.warn("writeEventSpan failed (dropped): traceId={} spanId={} eventType={}",
                        request.traceId(), request.spanId(), request.eventType(), e);
            }
        });
    }

    private void doWriteEventSpan(EventSpanWriteRequest request) {
        if (spanRepository.existsById(request.spanId())) {
            log.debug("LlmSpan (event) already exists, skipping insert: spanId={}", request.spanId());
            return;
        }
        LlmSpanEntity e = new LlmSpanEntity();
        e.setSpanId(request.spanId());
        e.setTraceId(request.traceId());
        e.setParentSpanId(request.parentSpanId());
        e.setSessionId(request.sessionId());
        e.setAgentId(request.agentId());
        e.setProvider(null);
        e.setModel(null);
        e.setIterationIndex(request.iterationIndex());
        e.setStream(false);
        e.setInputSummary(request.inputSummary());
        e.setOutputSummary(request.outputSummary());
        e.setBlobStatus("ok");
        e.setInputTokens(0);
        e.setOutputTokens(0);
        e.setLatencyMs(request.latencyMs());
        e.setStartedAt(request.startedAt());
        e.setEndedAt(request.endedAt());
        e.setFinishReason(request.success() ? "success" : "error");
        e.setError(request.error());
        e.setErrorType(request.success() ? null : "event_error");
        e.setSource(LlmSpanSource.LIVE.wireValue());
        e.setKind("event");
        e.setEventType(request.eventType());
        e.setName(request.name() != null ? request.name() : request.eventType());
        e.setCreatedAt(Instant.now());
        spanRepository.save(e);
    }

    /**
     * §B.3 R2-B3 — 异步执行；SQL {@code WHERE status='running'} 提供幂等保护，
     * terminal 状态不被覆盖。失败 log.warn + drop。
     *
     * <p>R2-W5 已知限制：与 writeToolSpan 共享多线程池（corePoolSize=2/maxPoolSize=4），
     * 调度顺序不保证。M1 双写期对前端透明（读路径仍走 t_trace_span）；M3 评估时再决定是否
     * 用 sequential flush 策略。
     */
    @Override
    public void finalizeTrace(TraceFinalizeRequest request) {
        executor.submit(() -> {
            try {
                txTemplate.executeWithoutResult(status -> doFinalizeTrace(request));
            } catch (Exception e) {
                log.warn("finalizeTrace failed (dropped): traceId={} status={}",
                        request.traceId(), request.status(), e);
            }
        });
    }

    private void doFinalizeTrace(TraceFinalizeRequest request) {
        int updated = em.createNativeQuery(FINALIZE_TRACE_SQL)
                .setParameter("traceId", request.traceId())
                .setParameter("status", request.status())
                .setParameter("error", request.error())
                .setParameter("totalDurationMs", request.totalDurationMs())
                .setParameter("toolCallCount", request.toolCallCount())
                .setParameter("eventCount", request.eventCount())
                .setParameter("endedAt", request.endedAt())
                .executeUpdate();
        if (updated == 0) {
            log.debug("finalizeTrace no-op (already terminal or trace missing): traceId={}",
                    request.traceId());
        }
    }

    /**
     * OBS-2 M3 W2: push limit + kind filter down to SQL/pagination instead of loading every
     * span row for the trace into memory and slicing in Java. For long sessions (thousands
     * of tool calls) the previous loop materialised the entire trace's spans before
     * truncating; with this fix the DB returns at most {@code limit} rows.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LlmTrace> listTracesBySessionAsc(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        int effectiveLimit = Math.max(1, limit);
        org.springframework.data.domain.Pageable page =
                org.springframework.data.domain.PageRequest.of(0, effectiveLimit);
        List<LlmTraceEntity> raw = traceRepository
                .findBySessionIdOrderByStartedAtAsc(sessionId, page);
        List<LlmTrace> out = new ArrayList<>(raw.size());
        for (LlmTraceEntity te : raw) {
            out.add(toDomain(te));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LlmSpan> listSpansByTrace(String traceId, Set<String> kinds, int limit) {
        int effectiveLimit = Math.max(1, limit);
        org.springframework.data.domain.Pageable page =
                org.springframework.data.domain.PageRequest.of(0, effectiveLimit);
        List<LlmSpanEntity> raw = (kinds == null || kinds.isEmpty())
                ? spanRepository.findByTraceIdOrderByStartedAtAsc(traceId, page)
                : spanRepository.findByTraceIdAndKindInOrderByStartedAtAsc(traceId, kinds, page);
        List<LlmSpan> out = new ArrayList<>(raw.size());
        for (LlmSpanEntity e : raw) {
            out.add(toDomain(e));
        }
        return out;
    }

    private LlmTrace toDomain(LlmTraceEntity te) {
        return new LlmTrace(
                te.getTraceId(), te.getSessionId(),
                te.getAgentId(), te.getUserId(), te.getRootName(),
                te.getStartedAt(), te.getEndedAt(),
                te.getTotalInputTokens(), te.getTotalOutputTokens(),
                te.getTotalCostUsd(),
                LlmSpanSource.fromWire(te.getSource()),
                te.getStatus(),
                te.getError(),
                te.getTotalDurationMs(),
                te.getToolCallCount(),
                te.getEventCount(),
                te.getAgentName());
    }

    private LlmSpan toDomain(LlmSpanEntity e) {
        return new LlmSpan(
                e.getSpanId(), e.getTraceId(), e.getParentSpanId(), e.getSessionId(),
                e.getAgentId(), e.getProvider(), e.getModel(),
                e.getIterationIndex(), e.isStream(),
                e.getInputSummary(), e.getOutputSummary(),
                e.getInputBlobRef(), e.getOutputBlobRef(), e.getRawSseBlobRef(),
                e.getBlobStatus(),
                e.getInputTokens(), e.getOutputTokens(), e.getCacheReadTokens(),
                e.getUsageJson(),
                e.getCostUsd(), e.getLatencyMs(),
                e.getStartedAt(), e.getEndedAt(),
                e.getFinishReason(), e.getRequestId(),
                e.getReasoningContent(),
                e.getError(), e.getErrorType(), e.getToolUseId(),
                java.util.Map.of(),
                LlmSpanSource.fromWire(e.getSource()),
                e.getKind() != null ? e.getKind() : "llm",
                e.getEventType(),
                e.getName());
    }

    /** Test-only accessor for the executor (lets tests await async writes). */
    public ThreadPoolTaskExecutor getExecutor() {
        return executor;
    }
}
