package com.skillforge.server.repository;

import com.skillforge.server.entity.MemoryProposalEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MEMORY-LLM-SYNTHESIS (V68): JPA access for {@link MemoryProposalEntity}.
 *
 * <p>Approve path uses {@link #findByIdForUpdate(Long)} (B-4 fix: PESSIMISTIC_WRITE)
 * to serialize concurrent approve calls so the source-memory race is impossible.
 */
public interface MemoryProposalRepository extends JpaRepository<MemoryProposalEntity, Long> {

    /** B-4 fix: row-level lock so concurrent approves serialize at DB level. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM MemoryProposalEntity p WHERE p.id = :id")
    Optional<MemoryProposalEntity> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT p FROM MemoryProposalEntity p "
            + "WHERE p.userId = :userId AND p.status = :status "
            + "ORDER BY p.createdAt DESC")
    List<MemoryProposalEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
            @Param("userId") Long userId, @Param("status") String status, Pageable pageable);

    @Query("SELECT p FROM MemoryProposalEntity p "
            + "WHERE p.status = :status "
            + "ORDER BY p.createdAt DESC")
    List<MemoryProposalEntity> findByStatusOrderByCreatedAtDesc(
            @Param("status") String status, Pageable pageable);

    /** Auto-archive sweep: proposed status + created_at + 7d &lt; now. */
    @Query("SELECT p FROM MemoryProposalEntity p "
            + "WHERE p.status = 'proposed' AND p.createdAt < :cutoff")
    List<MemoryProposalEntity> findStaleProposed(@Param("cutoff") Instant cutoff);

    /**
     * MEMORY-LLM-SYNTHESIS (V69 dogfood): used by {@code MemoryProposalReadyBroadcaster}
     * SESSION_END hook to compose the "N proposals pending review" WS broadcast. Returns
     * total proposed-status count across all users — admin UI is global and there is no
     * per-user admin scoping today (single-tenant dev/internal system).
     */
    long countByStatus(String status);

    /**
     * GIN index (idx_proposal_source_memory_ids_gin) backed: find proposals that reference
     * the given memory id. Cross-run dedup check uses this so the same source set is not
     * re-proposed every cron tick. Native query because JPQL has no JSONB containment op.
     */
    @Query(value = "SELECT * FROM t_memory_proposal "
            + "WHERE user_id = :userId AND status IN ('proposed','approved') "
            + "AND source_memory_ids @> CAST(:jsonId AS jsonb)",
            nativeQuery = true)
    List<MemoryProposalEntity> findReferencingMemoryId(@Param("userId") Long userId,
                                                       @Param("jsonId") String jsonIdArray);

    /**
     * SYSTEM-AGENT-TYPING Phase 2.1: 7-day output count for the memory-curator
     * monitor card. The table is natively scoped to memory-curator (no other
     * cron writes here). Each row is one proposed consolidation / dedup /
     * reflection — colloquially labelled "consolidations" in the FE card.
     */
    long countByCreatedAtAfter(Instant since);
}
