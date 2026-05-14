package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionAnnotationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * PROD-LABEL-CLUSTER V1: JPA access for {@link SessionAnnotationEntity}.
 *
 * <p>Phase 1.1 deliberately shipped only per-session listing. Phase 1.2 adds the
 * two queries the signal-stage service needs to surface the "needs LLM" queue
 * (recent signal rows + which sessions already have an LLM row). These are
 * intentionally narrow — not a generic search surface.
 */
public interface SessionAnnotationRepository extends JpaRepository<SessionAnnotationEntity, Long> {

    List<SessionAnnotationEntity> findBySessionId(String sessionId);

    /**
     * Phase 1.2: recent rows by source, newest first. Used by
     * {@code SessionAnnotationSignalService.findSessionsNeedingLlmAnnotation} to
     * pull the candidate signal queue. The {@link Pageable} parameter is
     * Spring's standard way to bound the result set.
     */
    @Query("""
            SELECT a FROM SessionAnnotationEntity a
            WHERE a.source = :source
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<SessionAnnotationEntity> findRecentBySource(
            @Param("source") String source, Pageable pageable);

    /**
     * Phase 1.2 convenience wrapper around {@link #findRecentBySource} so the
     * service doesn't need to construct a {@link Pageable} for the common
     * "top N" use case. Limit is interpreted as result rows, not pages.
     */
    default List<SessionAnnotationEntity> findRecentByLimit(String source, int limit) {
        if (limit <= 0) return List.of();
        return findRecentBySource(source, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    /**
     * Phase 1.2: of the given {@code sessionIds}, returns those that already have
     * ≥ 1 row with the given {@code source} (typically {@code source='llm'}).
     * Used to filter the signal queue down to sessions still pending LLM annotation.
     */
    @Query("""
            SELECT DISTINCT a.sessionId FROM SessionAnnotationEntity a
            WHERE a.sessionId IN :sessionIds AND a.source = :source
            """)
    List<String> findSessionIdsWithSource(
            @Param("sessionIds") Collection<String> sessionIds,
            @Param("source") String source);
}
