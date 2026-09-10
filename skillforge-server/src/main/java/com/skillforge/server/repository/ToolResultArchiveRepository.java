package com.skillforge.server.repository;

import com.skillforge.server.entity.ToolResultArchiveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ToolResultArchiveRepository extends JpaRepository<ToolResultArchiveEntity, Long> {

    Optional<ToolResultArchiveEntity> findFirstBySessionIdAndToolUseIdOrderByIdAsc(
            String sessionId, String toolUseId);

    /**
     * Legacy adapter used only by the pre-occurrence archive service while all new flags are off.
     * V198 retains a transitional partial unique guard only for rows whose
     * {@code session_message_id IS NULL}; a toolUseId is not an occurrence identity. Batch 6
     * replaces this adapter with the occurrence-aware resolver.
     */
    default Optional<ToolResultArchiveEntity> findBySessionIdAndToolUseId(
            String sessionId, String toolUseId) {
        return findFirstBySessionIdAndToolUseIdOrderByIdAsc(sessionId, toolUseId);
    }

    Optional<ToolResultArchiveEntity> findByArchiveId(String archiveId);

    List<ToolResultArchiveEntity> findBySessionId(String sessionId);

    List<ToolResultArchiveEntity> findBySessionIdAndSessionMessageIdIn(
            String sessionId, Collection<Long> sessionMessageIds);

    /** Unclaimed V197-and-earlier rows; consumed only by the explicit offline backfill. */
    List<ToolResultArchiveEntity> findBySessionIdAndSessionMessageIdIsNullOrderByIdAsc(
            String sessionId);

    Optional<ToolResultArchiveEntity> findBySessionIdAndSessionMessageIdAndBlockIndex(
            String sessionId, Long sessionMessageId, Integer blockIndex);

    List<ToolResultArchiveEntity> findBySessionIdAndToolUseIdAndCanonicalPayloadHash(
            String sessionId, String toolUseId, String canonicalPayloadHash);

    /**
     * P9-2 compatibility insert. V198 removes the old full (session_id, tool_use_id)
     * conflict target and retains a transitional partial unique guard only for null-
     * occurrence legacy rows. Targetless conflict handling can use that guard without
     * naming the deleted full uniqueness contract. Batch 6 replaces this path with
     * occurrence-aware insertion.
     *
     * <p>不能用 JPA 的 saveAll/save，那条路径走 SELECT-then-INSERT，UNIQUE 冲突时仍抛
     * DataIntegrityViolationException 把 TX 标 aborted。
     *
     * @return 实际写入的行数（0 = 命中 transitional legacy partial unique 或其他
     * unique constraint，1 = 本次 insert 成功）
     */
    @Modifying
    @Query(value = """
            INSERT INTO t_tool_result_archive
                (archive_id, session_id, session_message_id, tool_use_id, tool_name,
                 original_chars, preview, content, created_at)
            VALUES (:archiveId, :sessionId, :sessionMessageId, :toolUseId, :toolName,
                    :originalChars, :preview, :content, :createdAt)
            ON CONFLICT DO NOTHING
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

    /** Idempotent occurrence-owned insert used only after exact raw carrier verification. */
    @Modifying
    @Query(value = """
            INSERT INTO t_tool_result_archive
                (archive_id, session_id, session_message_id, block_index,
                 tool_use_id, tool_name, original_chars, preview, content,
                 canonical_payload_hash, payload_hash_version, created_at)
            VALUES (:archiveId, :sessionId, :sessionMessageId, :blockIndex,
                    :toolUseId, :toolName, :originalChars, :preview, :content,
                    :canonicalPayloadHash, :payloadHashVersion, :createdAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertOccurrenceIgnoreConflict(
            @Param("archiveId") String archiveId,
            @Param("sessionId") String sessionId,
            @Param("sessionMessageId") Long sessionMessageId,
            @Param("blockIndex") Integer blockIndex,
            @Param("toolUseId") String toolUseId,
            @Param("toolName") String toolName,
            @Param("originalChars") int originalChars,
            @Param("preview") String preview,
            @Param("content") String content,
            @Param("canonicalPayloadHash") String canonicalPayloadHash,
            @Param("payloadHashVersion") short payloadHashVersion,
            @Param("createdAt") Instant createdAt);

    /**
     * Claims one still-unclaimed legacy row after an offline unique exact-match scan.
     * Every legacy-shape predicate is repeated so a stale scan cannot overwrite a
     * concurrent canonical claim.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ToolResultArchiveEntity archive
               SET archive.sessionMessageId = :sessionMessageId,
                   archive.blockIndex = :blockIndex,
                   archive.canonicalPayloadHash = :canonicalPayloadHash,
                   archive.payloadHashVersion = :payloadHashVersion
             WHERE archive.id = :id
               AND archive.sessionId = :sessionId
               AND archive.sessionMessageId IS NULL
               AND archive.blockIndex IS NULL
               AND archive.canonicalPayloadHash IS NULL
               AND archive.payloadHashVersion IS NULL
            """)
    int claimLegacyOccurrence(
            @Param("id") Long id,
            @Param("sessionId") String sessionId,
            @Param("sessionMessageId") Long sessionMessageId,
            @Param("blockIndex") Integer blockIndex,
            @Param("canonicalPayloadHash") String canonicalPayloadHash,
            @Param("payloadHashVersion") short payloadHashVersion);
}
