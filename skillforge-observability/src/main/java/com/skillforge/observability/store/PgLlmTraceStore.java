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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plan §3.1 + §4.5 R2-B3 — PG 实现：
 * upsertTrace 用原生 SQL ON CONFLICT，DB 端累加避免 read-modify-write race。
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

    @PersistenceContext
    private EntityManager em;

    private final LlmTraceRepository traceRepository;
    private final LlmSpanRepository spanRepository;

    public PgLlmTraceStore(LlmTraceRepository traceRepository, LlmSpanRepository spanRepository) {
        this.traceRepository = traceRepository;
        this.spanRepository = spanRepository;
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

    @Override
    @Transactional(readOnly = true)
    public Optional<LlmSpan> readSpan(String spanId) {
        return spanRepository.findById(spanId).map(this::toDomain);
    }

    private LlmTrace toDomain(LlmTraceEntity te) {
        return new LlmTrace(
                te.getTraceId(), te.getSessionId(),
                te.getAgentId(), te.getUserId(), te.getRootName(),
                te.getStartedAt(), te.getEndedAt(),
                te.getTotalInputTokens(), te.getTotalOutputTokens(),
                te.getTotalCostUsd(),
                LlmSpanSource.fromWire(te.getSource()));
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
                LlmSpanSource.fromWire(e.getSource()));
    }
}
