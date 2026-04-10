package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

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
}
