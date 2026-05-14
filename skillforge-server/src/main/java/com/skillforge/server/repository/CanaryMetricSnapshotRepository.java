package com.skillforge.server.repository;

import com.skillforge.server.entity.CanaryMetricSnapshotEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.1: JPA access for {@link CanaryMetricSnapshotEntity}.
 *
 * <p>Phase 1.1 ships one narrow read for the dashboard CanaryPanel
 * (last N hourly buckets, newest first). Write paths go through
 * {@code save} from Phase 1.4 CanaryMetricsService.
 */
public interface CanaryMetricSnapshotRepository extends JpaRepository<CanaryMetricSnapshotEntity, Long> {

    /**
     * Phase 1.5 dashboard: load the most recent N snapshots for a canary
     * (typically last 24 hours). Backed by
     * {@code idx_metric_canary_bucket(canary_id, bucket_at DESC)}.
     *
     * <p>Caller is responsible for choosing the limit via {@link Pageable}
     * — Phase 1.5 wires {@code PageRequest.of(0, 24)} from the controller.
     */
    @Query("""
            SELECT s FROM CanaryMetricSnapshotEntity s
            WHERE s.canaryId = :canaryId
            ORDER BY s.bucketAt DESC
            """)
    List<CanaryMetricSnapshotEntity> findByCanaryIdOrderByBucketAtDesc(
            @Param("canaryId") Long canaryId, Pageable pageable);
}
