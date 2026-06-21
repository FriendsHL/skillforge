package com.skillforge.observability.repository;

import com.skillforge.observability.entity.LlmSpanEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface LlmSpanRepository extends JpaRepository<LlmSpanEntity, String> {

    List<LlmSpanEntity> findBySessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
            String sessionId, Instant since);

    List<LlmSpanEntity> findBySessionIdOrderByStartedAtAsc(String sessionId);

    List<LlmSpanEntity> findBySessionIdAndKindInAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
            String sessionId, Set<String> kinds, Instant since, Pageable pageable);

    List<LlmSpanEntity> findBySessionIdAndKindInOrderByStartedAtAsc(
            String sessionId, Set<String> kinds, Pageable pageable);

    List<LlmSpanEntity> findBySessionIdInOrderByStartedAtAsc(Collection<String> sessionIds);

    List<LlmSpanEntity> findByTraceIdOrderByStartedAtAsc(String traceId);

    /**
     * OBS-4 M2: batch lookup spans across multiple traces — used by
     * {@code GET /api/traces/{rootTraceId}/tree} to fetch every span belonging to any
     * trace in the investigation in one query, then group by traceId in memory.
     */
    List<LlmSpanEntity> findByTraceIdInOrderByStartedAtAsc(Collection<String> traceIds);

    /**
     * OBS-2 M3 W2: paginated lookup with kind filter so {@link
     * com.skillforge.observability.api.LlmTraceStore#listSpansByTrace} pushes the
     * limit down to SQL instead of loading every span for the trace into memory.
     */
    List<LlmSpanEntity> findByTraceIdAndKindInOrderByStartedAtAsc(
            String traceId, Set<String> kinds, Pageable pageable);

    List<LlmSpanEntity> findByTraceIdOrderByStartedAtAsc(String traceId, Pageable pageable);

    List<LlmSpanEntity> findByStartedAtBefore(Instant cutoff);

    /**
     * OBS-2 M3 r2 B-1: batch GROUP BY count for {@code GET /api/traces} N+1 elimination.
     *
     * <p>Returns one {@code [traceId, count]} row per trace that has at least one span of
     * the given kind. Traces with zero spans are absent — callers must default missing
     * keys to 0 (see {@code TracesController.listTraces}).
     */
    @Query("SELECT s.traceId, count(s) FROM LlmSpanEntity s "
            + "WHERE s.traceId IN :traceIds AND s.kind = :kind "
            + "GROUP BY s.traceId")
    List<Object[]> countByTraceIdsAndKind(@Param("traceIds") Collection<String> traceIds,
                                          @Param("kind") String kind);

    /**
     * ACP-EXTERNAL-AGENT P2-3a: count a single trace's spans grouped by {@code kind}.
     *
     * <p>Returns one {@code [kind, count]} row per kind present on the trace
     * (e.g. {@code ["llm", 4]}, {@code ["tool", 3]}, {@code ["event", 1]}). Used by
     * {@code AcpAgentRunner} to finalize the cc sub-session trace with authoritative
     * tool/event counts recomputed from the actual spans the cc event translator wrote.
     * {@code kind} may be NULL for legacy rows — callers treat NULL as "llm".
     */
    @Query("SELECT s.kind, count(s) FROM LlmSpanEntity s "
            + "WHERE s.traceId = :traceId "
            + "GROUP BY s.kind")
    List<Object[]> countByTraceIdGroupByKind(@Param("traceId") String traceId);

    /**
     * ANNOTATOR-BEHAVIOR-SIGNALS (2026-05-22): per-tool call count for a session.
     * Used by {@code SpanBehaviorStatsTool} (STEP 1.5 of the session-annotator pipeline)
     * to derive behavioral efficiency signals (hasToolOveruse, top tool churn).
     *
     * <p>Returns one {@code [name, count]} row per tool that was invoked at least once
     * in the session, ordered by descending count. {@code name} may be NULL for legacy
     * pre-OBS-2 M0 rows where {@code kind=tool} but {@code name} was not yet captured —
     * callers should filter NULLs out client-side or treat them as an "unknown tool"
     * bucket.
     */
    @Query("SELECT s.name, COUNT(s) FROM LlmSpanEntity s "
            + "WHERE s.sessionId = :sessionId AND s.kind = 'tool' "
            + "GROUP BY s.name ORDER BY COUNT(s) DESC")
    List<Object[]> countToolCallsByNameForSession(@Param("sessionId") String sessionId);

    /**
     * ANNOTATOR-BEHAVIOR-SIGNALS (2026-05-22): total LLM-call count for a session.
     * Used by {@code SpanBehaviorStatsTool} as {@code totalTurns} — each LLM call is
     * one Agent Loop turn, so a session with >20 LLM spans is a candidate
     * {@code loop_inefficiency} outcome.
     */
    long countBySessionIdAndKind(String sessionId, String kind);

    /**
     * ANNOTATOR-BEHAVIOR-SIGNALS (2026-05-22): count of error spans for a session.
     * Used by {@code SpanBehaviorStatsTool} to populate {@code errorSpanCount} which
     * the LLM annotator inspects alongside per-tool counts to derive
     * {@code suspect_surface} (e.g. error span + high-count tool name → behavior_rule).
     */
    long countBySessionIdAndErrorIsNotNull(String sessionId);
}
