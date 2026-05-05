package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SessionEntity s WHERE s.id = :id")
    Optional<SessionEntity> findByIdForUpdate(@Param("id") String id);

    List<SessionEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /** 只取顶层 session(过滤掉 SubAgent 派发出来的子 session) */
    List<SessionEntity> findByUserIdAndParentSessionIdIsNullOrderByUpdatedAtDesc(Long userId);

    /**
     * EVAL-V2 M3a §2.2 R3: 顶层 session 列表，按 origin 过滤（默认 'production'）。
     * 防止 EvalOrchestrator 派出的 eval session 污染常规列表视图。
     */
    List<SessionEntity> findByUserIdAndParentSessionIdIsNullAndOriginOrderByUpdatedAtDesc(
            Long userId, String origin);

    List<SessionEntity> findByAgentId(Long agentId);

    long countByAgentId(Long agentId);

    List<SessionEntity> findByParentSessionId(String parentSessionId);

    /**
     * EVAL-V2 Q1: list analysis sessions linked to a given eval scenario.
     * Filtered by userId so one user can't see another user's analysis
     * sessions (multi-user safety; mirrors the pattern used by listSessions).
     * Ordered by updatedAt desc so the most-recent analysis bubbles up.
     */
    List<SessionEntity> findBySourceScenarioIdAndUserIdOrderByUpdatedAtDesc(
            String sourceScenarioId, Long userId);

    /**
     * OBS-1 §7.4 R3-WN2 — fallback resolver path for SubAgent TOOL_CALL spans whose
     * output text didn't carry the expected "  childSessionId: <uuid>\n" line.
     *
     * <p>NOTE: {@code SessionEntity.createdAt} is {@code LocalDateTime} (java.md footgun #2,
     * historical). Resolver converts the span's {@code Instant startTime} to LocalDateTime
     * via {@code ZoneId.systemDefault()} at the call boundary; server deployment assumes UTC
     * (see plan §3.5 / application.yml comment).
     */
    Optional<SessionEntity> findFirstByParentSessionIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
            String parentSessionId, java.time.LocalDateTime minCreatedAt);

    /**
     * OBS-1 W-R3-N2-a — batch-resolver fallback for {@code SessionSpansService}.
     * Used to collapse N+1 fallback queries when many SubAgent TOOL_CALL spans miss the
     * primary regex path; service-side dedupes by (parentSessionId, createdAt) ordering.
     */
    List<SessionEntity> findByParentSessionIdInAndCreatedAtGreaterThanEqual(
            java.util.Collection<String> parentSessionIds,
            java.time.LocalDateTime minCreatedAt);

    long countByParentSessionIdAndRuntimeStatus(String parentSessionId, String runtimeStatus);

    /**
     * Used by {@link com.skillforge.server.init.LastUserMessageAtBackfill} to
     * find legacy rows that need seeding. {@code lastUserMessageAt} was added
     * in 2026-04-09; older rows have NULL and need to be filled from
     * {@code updatedAt}. Bulk JPQL UPDATE can't do the type conversion from
     * {@code LocalDateTime} → {@code Instant}, so the backfill iterates and
     * saves each row in Java.
     */
    List<SessionEntity> findByLastUserMessageAtIsNull();

    @Query("""
            SELECT s FROM SessionEntity s
            WHERE s.parentSessionId IS NULL
              AND s.lastUserMessageAt IS NOT NULL
              AND s.lastUserMessageAt < :cutoff
              AND (s.runtimeStatus IS NULL OR s.runtimeStatus IN ('idle', 'waiting_user'))
              AND EXISTS (
                SELECT 1 FROM SessionMessageEntity m
                WHERE m.sessionId = s.id
                  AND m.msgType = 'NORMAL'
                  AND m.prunedAt IS NULL
                  AND m.seqNo > s.lastExtractedMessageSeq
              )
            ORDER BY s.lastUserMessageAt ASC
            """)
    List<SessionEntity> findIdleExtractionCandidates(
            @Param("cutoff") java.time.Instant cutoff,
            Pageable pageable);

    @Query("""
            SELECT s FROM SessionEntity s
            WHERE s.parentSessionId IS NULL
              AND s.completedAt IS NOT NULL
              AND (s.runtimeStatus IS NULL OR s.runtimeStatus IN ('idle', 'waiting_user'))
              AND (s.digestExtractedAt IS NULL OR s.digestExtractedAt < :digestCutoff)
              AND EXISTS (
                SELECT 1 FROM SessionMessageEntity m
                WHERE m.sessionId = s.id
                  AND m.msgType = 'NORMAL'
                  AND m.prunedAt IS NULL
                  AND m.seqNo > s.lastExtractedMessageSeq
              )
            ORDER BY s.completedAt ASC
            """)
    List<SessionEntity> findDailyExtractionCandidates(
            @Param("digestCutoff") java.time.Instant digestCutoff,
            Pageable pageable);

    List<SessionEntity> findByCollabRunId(String collabRunId);

    long countByCollabRunId(String collabRunId);

    List<SessionEntity> findByCollabRunIdAndRuntimeStatus(String collabRunId, String runtimeStatus);

    @org.springframework.data.jpa.repository.Query("""
            SELECT s FROM SessionEntity s
            WHERE s.messagesJson IS NOT NULL
              AND s.messagesJson <> ''
              AND s.messagesJson <> '[]'
              AND NOT EXISTS (
                SELECT 1 FROM SessionMessageEntity m
                WHERE m.sessionId = s.id
              )
            ORDER BY s.id ASC
            """)
    Page<SessionEntity> findLegacySessionsWithoutRowMessages(Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
            SELECT s FROM SessionEntity s
            WHERE s.agentId = :agentId
              AND s.runtimeStatus IN ('completed', 'idle')
              AND s.messageCount > 0
            ORDER BY s.completedAt DESC NULLS LAST
            """)
    List<SessionEntity> findRecentEligibleSessionsForSkillDraft(
            @org.springframework.data.repository.query.Param("agentId") Long agentId,
            org.springframework.data.domain.Pageable pageable);
}
