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
}
