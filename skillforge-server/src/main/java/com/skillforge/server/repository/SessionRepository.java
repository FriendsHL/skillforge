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

    List<SessionEntity> findByAgentId(Long agentId);

    long countByAgentId(Long agentId);

    List<SessionEntity> findByParentSessionId(String parentSessionId);

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

    List<SessionEntity> findByCompletedAtIsNotNullAndDigestExtractedAtIsNull();

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
