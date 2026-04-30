package com.skillforge.server.repository;

import com.skillforge.server.entity.ToolResultArchiveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ToolResultArchiveRepository extends JpaRepository<ToolResultArchiveEntity, Long> {

    Optional<ToolResultArchiveEntity> findBySessionIdAndToolUseId(String sessionId, String toolUseId);

    Optional<ToolResultArchiveEntity> findByArchiveId(String archiveId);

    List<ToolResultArchiveEntity> findBySessionId(String sessionId);

    /**
     * P9-2 (Judge FIX-2): PostgreSQL-safe idempotent insert. ON CONFLICT DO NOTHING 让
     * UNIQUE 冲突静默失败而不 abort 当前事务（标准 PostgreSQL 8.x+ 语义），调用方在 insert
     * 之后再 lookup 一次取回胜出方行。
     *
     * <p>不能用 JPA 的 saveAll/save，那条路径走 SELECT-then-INSERT，UNIQUE 冲突时仍抛
     * DataIntegrityViolationException 把 TX 标 aborted。
     *
     * @return 实际写入的行数（0 = 已有 winner 行，1 = 本次 insert 成功）
     */
    @Modifying
    @Query(value = """
            INSERT INTO t_tool_result_archive
                (archive_id, session_id, session_message_id, tool_use_id, tool_name,
                 original_chars, preview, content, created_at)
            VALUES (:archiveId, :sessionId, :sessionMessageId, :toolUseId, :toolName,
                    :originalChars, :preview, :content, :createdAt)
            ON CONFLICT (session_id, tool_use_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("archiveId") String archiveId,
                             @Param("sessionId") String sessionId,
                             @Param("sessionMessageId") Long sessionMessageId,
                             @Param("toolUseId") String toolUseId,
                             @Param("toolName") String toolName,
                             @Param("originalChars") int originalChars,
                             @Param("preview") String preview,
                             @Param("content") String content,
                             @Param("createdAt") Instant createdAt);
}
