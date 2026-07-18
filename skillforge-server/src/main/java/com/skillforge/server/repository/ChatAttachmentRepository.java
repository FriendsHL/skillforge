package com.skillforge.server.repository;

import com.skillforge.server.entity.ChatAttachmentEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachmentEntity, String> {

    List<ChatAttachmentEntity> findBySessionIdAndIdIn(String sessionId, Collection<String> ids);

    Optional<ChatAttachmentEntity> findBySessionIdAndSourceToolUseId(
            String sessionId, String sourceToolUseId);

    List<ChatAttachmentEntity> findByOriginAndStatusAndCreatedAtBefore(
            String origin, String status, Instant before);

    @Query("SELECT a FROM ChatAttachmentEntity a WHERE a.origin = 'agent_generated' AND ("
            + "(a.status = 'uploaded' AND a.createdAt < :cutoff) OR "
            + "(a.status IN ('publishing', 'deleting') AND "
            + "((a.boundAt IS NULL AND a.createdAt < :cutoff) OR a.boundAt < :cutoff)))")
    List<ChatAttachmentEntity> findStaleGeneratedArtifacts(@Param("cutoff") Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ChatAttachmentEntity a SET a.status = 'publishing', a.boundAt = :now "
            + "WHERE a.id = :id AND a.status IN ('uploaded', 'publishing')")
    int reserveForPublishing(@Param("id") String id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ChatAttachmentEntity a SET a.status = 'deleting', a.boundAt = :now "
            + "WHERE a.id = :id AND ("
            + "(a.status = 'uploaded' AND a.createdAt < :cutoff) OR "
            + "(a.status IN ('publishing', 'deleting') AND "
            + "((a.boundAt IS NULL AND a.createdAt < :cutoff) OR a.boundAt < :cutoff)))")
    int claimStaleForCleanup(
            @Param("id") String id,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ChatAttachmentEntity a SET a.status = 'published' "
            + "WHERE a.id = :id AND a.origin = 'agent_generated' "
            + "AND a.status IN ('uploaded', 'publishing', 'deleting')")
    int markGeneratedPublished(@Param("id") String id);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ChatAttachmentEntity a SET a.sourceMessageSeq = :sourceMessageSeq, "
            + "a.status = 'published' WHERE a.id = :id AND a.sessionId = :sessionId "
            + "AND a.kind = 'interactive' AND a.origin = 'agent_generated'")
    int bindPersonalAppSourceMessage(
            @Param("sessionId") String sessionId,
            @Param("id") String id,
            @Param("sourceMessageSeq") long sourceMessageSeq);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ChatAttachmentEntity a SET a.sourceMessageSeq = NULL, "
            + "a.status = CASE WHEN a.status = 'published' THEN 'uploaded' ELSE a.status END "
            + "WHERE a.sessionId = :sessionId AND a.kind = 'interactive' "
            + "AND a.origin = 'agent_generated' AND a.sourceMessageSeq IS NOT NULL")
    int clearPersonalAppSourceMessages(@Param("sessionId") String sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ChatAttachmentEntity a SET a.status = :status, a.boundAt = :boundAt "
            + "WHERE a.id = :id AND a.status = 'deleting'")
    int releaseCleanupClaim(
            @Param("id") String id,
            @Param("status") String status,
            @Param("boundAt") Instant boundAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM ChatAttachmentEntity a WHERE a.id = :id AND a.status = 'deleting'")
    int deleteClaimedArtifact(@Param("id") String id);

    /**
     * ATTACHMENT-CLEANUP (Wave1-B): find DB rows uploaded but never bound to a message
     * older than the threshold. {@code seqNo IS NULL} means {@code bindToMessage}
     * never associated the row with a chat turn, so the row + file are orphans
     * (user uploaded then closed the page / never sent).
     */
    List<ChatAttachmentEntity> findByOriginAndStatusAndSeqNoIsNullAndCreatedAtBefore(
            String origin, String status, Instant before);

    /**
     * ATTACHMENT-CLEANUP (Wave1-B): projection of every {@code storage_path} value
     * currently in the DB. Used by the cleanup scheduler to diff the on-disk file
     * tree against the DB so {@code session DELETE CASCADE} doesn't leave orphan
     * files behind. JPQL projection avoids hydrating full entities (only one
     * String column per row, fast even at hundreds of thousands of rows).
     */
    @Query("SELECT a.storagePath FROM ChatAttachmentEntity a")
    List<String> findAllStoragePaths();

    /**
     * V73 / MULTIMODAL-OBSERVABILITY-COLUMNS: admin filter for the
     * {@code GET /api/chat/admin/chat-attachments} endpoint. Each named filter
     * parameter is optional — pass {@code null} to skip that filter. Results
     * sorted by {@code createdAt DESC} (caller's {@link Pageable} also
     * supplies the {@code limit}).
     *
     * <p>JPQL with optional null filters keeps the implementation a single
     * query (no Specification / Criteria API ceremony for 3 filter dimensions).
     * The dataset is bounded by the {@code limit} cap (max 500) so we read
     * exactly what the caller asked for.</p>
     */
    @Query("SELECT a FROM ChatAttachmentEntity a WHERE "
            + "(:errorCode IS NULL OR a.errorCode = :errorCode) AND "
            + "(:processingMode IS NULL OR a.processingMode = :processingMode) AND "
            + "(:sessionId IS NULL OR a.sessionId = :sessionId) "
            + "ORDER BY a.createdAt DESC")
    List<ChatAttachmentEntity> findByFilters(@Param("errorCode") String errorCode,
                                             @Param("processingMode") String processingMode,
                                             @Param("sessionId") String sessionId,
                                             Pageable pageable);
}
