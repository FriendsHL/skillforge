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
public interface SessionAnnotationRepository
        extends JpaRepository<SessionAnnotationEntity, Long>, SessionAnnotationRepositoryCustom {

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
     * SYSTEM-AGENT-TYPING F7 Phase 1.2: recent signal rows scoped to a given
     * {@code agent_type} (joined via {@code t_session → t_agent}), newest first.
     * Used by {@link
     * com.skillforge.server.sessionannotation.SessionAnnotationSignalService#findSessionsNeedingLlmAnnotation(int)}
     * to surface user-agent candidates first so they don't get starved by the
     * hourly-cron system agents that always dominate the global most-recent
     * signal stream.
     *
     * <p>Why a native JOIN: the join target {@code t_agent.agent_type} is on a
     * different aggregate root than {@code SessionAnnotationEntity} — modeling
     * the relationship via JPA associations would require a synthetic
     * {@code @ManyToOne} chain on the annotation entity that nothing else
     * needs. Native SQL keeps the entity model focused.
     */
    @Query(value = """
            SELECT sa.* FROM t_session_annotation sa
              JOIN t_session s ON s.id = sa.session_id
              JOIN t_agent ag ON ag.id = s.agent_id
             WHERE sa.source = :source
               AND ag.agent_type = :agentType
             ORDER BY sa.created_at DESC, sa.id DESC
             LIMIT :lim
            """, nativeQuery = true)
    List<SessionAnnotationEntity> findRecentBySourceAndAgentType(
            @Param("source") String source,
            @Param("agentType") String agentType,
            @Param("lim") int lim);

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

    // upsertSkipDuplicate moved to SessionAnnotationRepositoryCustom + SessionAnnotationRepositoryImpl
    // (V1 W2 fix retains ON CONFLICT DO NOTHING RETURNING id semantics, but Spring Data JPA
    // @Modifying contract rejects Long return type — runtime IllegalArgumentException on first
    // PG invocation. Custom impl via EntityManager bypasses the constraint.)

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

    /**
     * SKILL-CANARY-ROLLOUT V2 Phase 1.4: window-scoped fetch for one annotation
     * type, used by {@code CanaryMetricsService.recompute} to pull every
     * {@code annotation_type='outcome'} row written by the V1 session-annotator
     * within the last hour bucket.
     *
     * <p>Ordering is newest-first by {@code created_at} so callers can early-cut
     * a stream when needed; volumes per hour are bounded by the V1 dogfood
     * session count, so no pagination needed.
     */
    @Query("""
            SELECT a FROM SessionAnnotationEntity a
            WHERE a.annotationType = :annotationType
              AND a.createdAt >= :since
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<SessionAnnotationEntity> findByTypeCreatedSince(
            @Param("annotationType") String annotationType,
            @Param("since") Instant since);

    /**
     * SYSTEM-AGENT-TYPING Phase 2.1: 7-day output count for the session-annotator
     * monitor card. The table is natively scoped to session-annotator (no other
     * cron writes here), so a plain {@code created_at} filter is sufficient.
     */
    long countByCreatedAtAfter(Instant since);
}
