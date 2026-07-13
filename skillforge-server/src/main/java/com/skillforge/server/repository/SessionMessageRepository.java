package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM SessionMessageEntity m WHERE m.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") String sessionId);

    long countBySessionId(String sessionId);

    Page<SessionMessageEntity> findBySessionIdOrderBySeqNoAsc(String sessionId, Pageable pageable);

    Optional<SessionMessageEntity> findTopBySessionIdOrderBySeqNoDesc(String sessionId);

    Optional<SessionMessageEntity> findTopBySessionIdAndMsgTypeAndPrunedAtIsNullOrderBySeqNoDesc(
            String sessionId, String msgType);

    Optional<SessionMessageEntity> findTopByTraceIdAndRoleOrderBySeqNoAsc(String traceId, String role);

    Optional<SessionMessageEntity> findTopByTraceIdAndRoleOrderBySeqNoDesc(String traceId, String role);

    @Query("SELECT m.contentJson FROM SessionMessageEntity m "
            + "WHERE m.sessionId = :sessionId AND m.role = 'assistant' "
            + "AND m.contentJson LIKE CONCAT('%', :attachmentId, '%')")
    List<String> findContentJsonCandidates(
            @Param("sessionId") String sessionId,
            @Param("attachmentId") String attachmentId);

    Page<SessionMessageEntity> findBySessionIdAndSeqNoGreaterThanEqualOrderBySeqNoAsc(
            String sessionId, long seqNo, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndMsgTypeOrderBySeqNoDesc(
            String sessionId, String msgType, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndPrunedAtIsNullOrderBySeqNoAsc(
            String sessionId, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndPrunedAtIsNullAndSeqNoGreaterThanOrderBySeqNoAsc(
            String sessionId, long seqNo, Pageable pageable);

    Page<SessionMessageEntity> findBySessionIdAndMsgTypeAndPrunedAtIsNullAndSeqNoGreaterThanOrderBySeqNoAsc(
            String sessionId, String msgType, long seqNo, Pageable pageable);

    long countBySessionIdAndRoleAndMsgTypeAndPrunedAtIsNullAndSeqNoGreaterThan(
            String sessionId, String role, String msgType, long seqNo);

    Optional<SessionMessageEntity> findBySessionIdAndMessageTypeAndControlId(
            String sessionId, String messageType, String controlId);

    Optional<SessionMessageEntity> findTopBySessionIdAndMessageTypeAndAnsweredAtIsNullOrderBySeqNoDesc(
            String sessionId, String messageType);

    /**
     * OBS-2 M3 follow-up — restore "trace title = first user message" semantics
     * after /api/traces switched to t_llm_trace (which has no input column).
     * <p>
     * Returns one row per traceId: (traceId, contentJson) of the earliest user
     * message belonging to that trace. PostgreSQL DISTINCT ON guarantees the
     * first row per trace_id given the ORDER BY (trace_id, seq_no ASC).
     */
    @Query(value = "SELECT DISTINCT ON (trace_id) trace_id, content_json " +
                   "FROM t_session_message " +
                   "WHERE trace_id IN (:traceIds) AND role = 'user' " +
                   "ORDER BY trace_id, seq_no ASC",
           nativeQuery = true)
    List<Object[]> findFirstUserMessageContentByTraceIds(
            @Param("traceIds") Collection<String> traceIds);

    /**
     * OBS-2 Q1: lightweight (seq_no, trace_id) projection for the trace_id
     * preservation layer. Used by:
     * <ul>
     *   <li>{@code SessionService.rewriteMessages} — snapshot pre-DELETE+INSERT
     *       trace_ids so they can be patched back onto the rewritten rows
     *       (fixes light no-boundary regression that wiped trace_id column).</li>
     *   <li>{@code CompactionService.persistCompactResult} — copy the original
     *       last-N trace_ids onto the freshly-appended retained block.</li>
     * </ul>
     */
    interface TraceIdView {
        long getSeqNo();
        String getTraceId();
    }

    @Query("SELECT m.seqNo AS seqNo, m.traceId AS traceId " +
           "FROM SessionMessageEntity m " +
           "WHERE m.sessionId = :sessionId AND m.traceId IS NOT NULL")
    List<TraceIdView> findNonNullTraceIdProjections(@Param("sessionId") String sessionId);

    @Query("SELECT m.seqNo AS seqNo, m.traceId AS traceId " +
           "FROM SessionMessageEntity m " +
           "WHERE m.sessionId = :sessionId " +
           "ORDER BY m.seqNo DESC")
    List<TraceIdView> findTailTraceIdProjections(
            @Param("sessionId") String sessionId, Pageable pageable);

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX (storage redesign P1): stamp the covering range
     * summary id onto the real rows in {@code [start, end]} that are not yet marked. Only
     * unmarked rows are updated ({@code compactedBySummaryId IS NULL}) so a re-run / merge
     * never clobbers an existing marker (the prior summary's marker stays until P2's range
     * recompute decides otherwise). Returns the number of rows updated.
     *
     * <p><b>Side-effect (P2a nit)</b>: {@code clearAutomatically = true} EVICTS the JPA persistence
     * context after the bulk UPDATE (and {@code flushAutomatically = true} flushes pending changes
     * first). This is required so a subsequent {@code SessionMessageEntity} read in the same tx sees
     * the new {@code compacted_by_summary_id} instead of a stale first-level-cache copy. Callers must
     * therefore treat any entity loaded BEFORE this call as detached/stale and re-read if needed
     * (the range-model write/recompute paths only read message rows AFTER the marker UPDATE, so they
     * are unaffected).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE SessionMessageEntity m SET m.compactedBySummaryId = :summaryId " +
           "WHERE m.sessionId = :sessionId AND m.seqNo BETWEEN :start AND :end " +
           "AND m.compactedBySummaryId IS NULL")
    int markCompactedBySummary(@Param("sessionId") String sessionId,
                               @Param("start") long start,
                               @Param("end") long end,
                               @Param("summaryId") Long summaryId);

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX (storage redesign P2b §7, B2): clear every
     * {@code compacted_by_summary_id} marker for a session. Used by
     * {@code SessionService.recomputeCompactedMarkers} as the first half of the
     * clear-then-restamp recompute: after a rewrite (divergence guard / restore /
     * branch) the markers are stale (point at pre-rewrite seq ids or were never
     * carried over), so they are wiped and re-derived from the active summary
     * ranges. Returns the number of rows cleared.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE SessionMessageEntity m SET m.compactedBySummaryId = NULL " +
           "WHERE m.sessionId = :sessionId AND m.compactedBySummaryId IS NOT NULL")
    int clearCompactedMarkers(@Param("sessionId") String sessionId);
}
