package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

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
     * 一次性回填: 老 session 的 last_user_message_at 是 NULL(2026-04-09 之前创建的),
     * 用 updated_at 当作近似值。新 session 走 ChatService.chatAsync 的正常路径。
     * 启动时运行一次, 之后空操作。
     */
    @Modifying
    @Transactional
    @Query("UPDATE SessionEntity s SET s.lastUserMessageAt = s.updatedAt WHERE s.lastUserMessageAt IS NULL")
    int backfillNullLastUserMessageAt();
}
