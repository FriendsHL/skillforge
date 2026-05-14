package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionPatternEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * PROD-LABEL-CLUSTER V1: JPA access for {@link SessionPatternEntity}.
 *
 * <p>{@link #findBySignature(String)} backs the cluster-upsert idempotency
 * path in {@code SessionPatternClusterService.recompute} (Phase 1.4).
 *
 * <p>{@link #findWithFilters} backs the {@code GET /api/insights/patterns}
 * endpoint. Nullable filter params let callers omit any of outcome / surface
 * / agentId; null = "no filter on that dimension".
 */
public interface SessionPatternRepository extends JpaRepository<SessionPatternEntity, Long> {

    Optional<SessionPatternEntity> findBySignature(String signature);

    /**
     * Phase 1.4: optional-filter listing for the Insights API. Each param may be
     * null (no filter on that dimension). Sort is fixed
     * {@code member_count DESC, last_seen_at DESC} per tech-design §6 — biggest
     * + most-recently-active cluster first.
     *
     * <p>{@link Pageable} carries the {@code limit}; pass
     * {@code PageRequest.of(0, limit)}.
     */
    @Query("""
            SELECT p FROM SessionPatternEntity p
            WHERE (:outcome IS NULL OR p.outcome = :outcome)
              AND (:surface IS NULL OR p.suspectSurface = :surface)
              AND (:agentId IS NULL OR p.agentId = :agentId)
            ORDER BY p.memberCount DESC, p.lastSeenAt DESC
            """)
    List<SessionPatternEntity> findWithFilters(
            @Param("outcome") String outcome,
            @Param("surface") String surface,
            @Param("agentId") Long agentId,
            Pageable pageable);
}
