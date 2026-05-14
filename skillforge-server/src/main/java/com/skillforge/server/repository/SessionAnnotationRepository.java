package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionAnnotationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    /**
     * Phase 1.4: distinct session ids with at least one annotation created on or
     * after {@code since}. Used by
     * {@code SessionPatternClusterService.recompute(window)} to find the working
     * set of sessions to fold into cluster signatures. Per-session annotation
     * fetch is then done via {@link #findBySessionId(String)}; volumes in V1
     * dogfood are small enough that this hop is fine.
     */
    @Query("""
            SELECT DISTINCT a.sessionId FROM SessionAnnotationEntity a
            WHERE a.createdAt >= :since
            """)
    List<String> findDistinctSessionIdsCreatedSince(@Param("since") Instant since);

    /**
     * V1 W2 fix (Postgres aborted-tx bug): native PG upsert that inserts a new
     * row or skips silently on UNIQUE conflict. Returns the generated id of the
     * newly-inserted row, or {@code null} when the UNIQUE constraint
     * {@code uq_session_annotation} matched an existing row (caller treats that
     * as a no-op duplicate).
     *
     * <p><b>Why native</b>: the prior implementation called
     * {@code saveAndFlush} inside a {@code try/catch DataIntegrityViolationException}
     * loop. On Postgres, the first UNIQUE conflict aborts the entire transaction
     * — subsequent {@code saveAndFlush} calls throw {@link org.springframework.orm.jpa.JpaSystemException}
     * ("current transaction is aborted, commands ignored until end of transaction
     * block"), not {@code DataIntegrityViolationException}, so the catch misses
     * them and the caller's per-session {@code catch (Exception)} silently
     * swallows the remainder of the batch. {@code ON CONFLICT DO NOTHING} is
     * a single statement that never marks the transaction aborted, restoring
     * the intended per-row idempotency.
     *
     * <p>H2 (unit-test dialect) does not support the {@code ON CONFLICT} clause;
     * the IT suite ({@code SessionAnnotationPersistenceIT}) covers real-PG
     * behaviour, while service unit tests mock this method.
     *
     * @return the generated row id, or {@code null} if the row already existed
     *         (UNIQUE conflict on {@code uq_session_annotation}).
     */
    @Modifying
    @Query(value = """
            INSERT INTO t_session_annotation (
                session_id, annotation_type, annotation_value, source,
                confidence, reasoning, created_at
            ) VALUES (
                :sessionId, :annotationType, :annotationValue, :source,
                :confidence, :reasoning, NOW()
            )
            ON CONFLICT ON CONSTRAINT uq_session_annotation
            DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    Long upsertSkipDuplicate(
            @Param("sessionId") String sessionId,
            @Param("annotationType") String annotationType,
            @Param("annotationValue") String annotationValue,
            @Param("source") String source,
            @Param("confidence") BigDecimal confidence,
            @Param("reasoning") String reasoning);

    /**
     * SKILL-CANARY-ROLLOUT V2 Phase 1.2: look up the canary group annotation
     * (if any) previously written by {@code CanaryAllocator} for this session
     * + surface type. Used to keep a session pinned to the same skill version
     * across its lifetime (ratify decision: "session 锁版本" — sessions never
     * switch skill version mid-flight).
     *
     * <p>Value format is {@code <surfaceType>:<skillName>} (e.g.
     * {@code "skill:my-skill"}). We filter by the surface prefix here so the
     * caller only needs to provide the surface; multiple surfaces can coexist
     * per session in the future (prompt, behavior_rule, ...).
     *
     * <p>Returns at most one row (the {@code DESC} ordering + {@code LIMIT 1}
     * picks the most recent if more than one was somehow written despite
     * {@code uq_session_annotation} — defensive). The UNIQUE constraint on
     * (session_id, annotation_type, annotation_value, source) means the same
     * value never repeats, but two different values for the same surface
     * are theoretically possible if the allocator ever mis-fired; this query
     * deterministically picks the latest.
     */
    @Query(value = """
            SELECT a.annotation_value FROM t_session_annotation a
            WHERE a.session_id = :sessionId
              AND a.annotation_type = 'canary_group'
              AND a.annotation_value LIKE CONCAT(:surfaceType, ':%')
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findCanaryGroup(
            @Param("sessionId") String sessionId,
            @Param("surfaceType") String surfaceType);
}
