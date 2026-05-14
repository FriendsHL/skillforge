package com.skillforge.server.repository;

import com.skillforge.server.entity.ChatAttachmentEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachmentEntity, String> {

    List<ChatAttachmentEntity> findBySessionIdAndIdIn(String sessionId, Collection<String> ids);

    /**
     * ATTACHMENT-CLEANUP (Wave1-B): find DB rows uploaded but never bound to a message
     * older than the threshold. {@code seqNo IS NULL} means {@code bindToMessage}
     * never associated the row with a chat turn, so the row + file are orphans
     * (user uploaded then closed the page / never sent).
     */
    List<ChatAttachmentEntity> findByStatusAndSeqNoIsNullAndCreatedAtBefore(String status, Instant before);

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
