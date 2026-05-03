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
}
