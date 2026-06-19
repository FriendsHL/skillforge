package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Range-summary store for the compaction storage redesign (storage-redesign.md §2.2, P1).
 */
public interface SessionSummaryRepository extends JpaRepository<SessionSummaryEntity, Long> {

    /** All active (non-superseded) summaries for a session, ordered by covered start_seq. */
    List<SessionSummaryEntity> findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(String sessionId);

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2b-2a (§5(A) dual-read): cheap indexed
     * existence check for "this session has at least one ACTIVE range summary", i.e. it was
     * compacted under the range model. Backed by the partial index {@code idx_ss_session_active}
     * ({@code WHERE superseded_by IS NULL}). Used by {@link SessionService#getContextMessages} to
     * gate the derived read so flipping the flag ON does not derive (= dump the entire history) for
     * old-model / never-compacted sessions that have no summary rows.
     */
    boolean existsBySessionIdAndSupersededByIsNull(String sessionId);

    /** All summaries for a session (active + superseded), ordered by covered start_seq. */
    List<SessionSummaryEntity> findBySessionIdOrderByStartSeqAsc(String sessionId);

    /** The latest active (non-superseded) summary — highest start_seq, i.e. the current rolling summary. */
    Optional<SessionSummaryEntity> findTopBySessionIdAndSupersededByIsNullOrderByStartSeqDesc(String sessionId);

    /**
     * Q3 merge: mark a prior active summary as superseded by a newer one. {@code @Transactional}
     * mirrors {@code SessionMessageRepository.markCompactedBySummary} so a stray call outside an
     * active tx does not raise {@code TransactionRequiredException}.
     */
    @Modifying
    @Transactional
    @Query("UPDATE SessionSummaryEntity s SET s.supersededBy = :newId WHERE s.id = :id")
    int markSuperseded(@Param("id") Long id, @Param("newId") Long newId);

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2b (§4, B2): on restore, drop any summary
     * whose covered range extends past the restore point — its covered rows no longer exist after the
     * rewrite, so the marker recompute must not re-stamp them. Deletes summaries with
     * {@code end_seq > :endSeq} (a summary fully inside [0,endSeq] is preserved). Returns the number
     * of summaries deleted.
     *
     * <p>{@code clearAutomatically = true} (mirrors {@link SessionMessageRepository#clearCompactedMarkers}):
     * restoreFromCheckpoint runs this prune-delete then IMMEDIATELY calls {@code recomputeCompactedMarkers},
     * which re-reads {@code SessionSummaryEntity} in the SAME transaction. Without evicting the L1
     * persistence context, the recompute could see just-deleted summaries (stale first-level cache)
     * and re-stamp markers for pruned ranges.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM SessionSummaryEntity s WHERE s.sessionId = :sessionId AND s.endSeq > :endSeq")
    int deleteBySessionIdAndEndSeqGreaterThan(@Param("sessionId") String sessionId,
                                              @Param("endSeq") long endSeq);
}
