package com.skillforge.server.repository;

import com.skillforge.server.entity.CanaryMetricSnapshotEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
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

    /**
     * Phase 1.3 autoRollbackCheck: load every snapshot for a canary so the
     * service can sum samples + failure counts across the canary's lifetime.
     * Ordered newest-first for consistency with the paginated variant; volume
     * is bounded by the canary's lifetime in hours (24 per day × small N days),
     * well within memory for the rollback decision path.
     */
    @Query("""
            SELECT s FROM CanaryMetricSnapshotEntity s
            WHERE s.canaryId = :canaryId
            ORDER BY s.bucketAt DESC
            """)
    List<CanaryMetricSnapshotEntity> findAllByCanaryId(@Param("canaryId") Long canaryId);

    /**
     * SKILL-CANARY-ROLLOUT V2 Phase 1.4 — PG-safe idempotent insert for one
     * hourly metric snapshot.
     *
     * <p><b>Why native ON CONFLICT DO NOTHING, not {@code save() + catch
     * DataIntegrityViolationException}</b>: see V1 W2 / java.md footgun. On
     * PostgreSQL the first {@code save()} that violates a UNIQUE constraint
     * marks the entire transaction as aborted; subsequent operations in the
     * same transaction throw {@code JpaSystemException} (not DIVE), so the
     * catch block silently misses them and the loop poisons every canary
     * after the first collision. A single native {@code ON CONFLICT DO
     * NOTHING} statement never marks the transaction aborted, preserving
     * per-canary independence in {@code CanaryMetricsService.recompute}.
     *
     * <p>H2 (unit-test dialect) does not support {@code ON CONFLICT}; service
     * unit tests mock this method, and the persistence IT runs in
     * Testcontainers PG via {@code CanaryPersistenceIT}.
     *
     * @return rows actually inserted: {@code 1} if this caller won, {@code 0}
     *         if a row with the same (canary_id, bucket_at) already exists.
     */
    @Modifying
    @Query(value = """
            INSERT INTO t_canary_metric_snapshot (
                canary_id, bucket_at,
                control_sample_size, control_success_count, control_failure_count,
                control_avg_quality, control_avg_efficiency, control_avg_latency, control_avg_cost,
                candidate_sample_size, candidate_success_count, candidate_failure_count,
                candidate_avg_quality, candidate_avg_efficiency, candidate_avg_latency, candidate_avg_cost,
                fail_rate_ratio, created_at
            ) VALUES (
                :canaryId, :bucketAt,
                :controlSampleSize, :controlSuccessCount, :controlFailureCount,
                :controlAvgQuality, :controlAvgEfficiency, :controlAvgLatency, :controlAvgCost,
                :candidateSampleSize, :candidateSuccessCount, :candidateFailureCount,
                :candidateAvgQuality, :candidateAvgEfficiency, :candidateAvgLatency, :candidateAvgCost,
                :failRateRatio, NOW()
            )
            ON CONFLICT ON CONSTRAINT uq_metric_canary_bucket DO NOTHING
            """, nativeQuery = true)
    int upsertSnapshotSkipDuplicate(
            @Param("canaryId") Long canaryId,
            @Param("bucketAt") Instant bucketAt,
            @Param("controlSampleSize") int controlSampleSize,
            @Param("controlSuccessCount") int controlSuccessCount,
            @Param("controlFailureCount") int controlFailureCount,
            @Param("controlAvgQuality") BigDecimal controlAvgQuality,
            @Param("controlAvgEfficiency") BigDecimal controlAvgEfficiency,
            @Param("controlAvgLatency") BigDecimal controlAvgLatency,
            @Param("controlAvgCost") BigDecimal controlAvgCost,
            @Param("candidateSampleSize") int candidateSampleSize,
            @Param("candidateSuccessCount") int candidateSuccessCount,
            @Param("candidateFailureCount") int candidateFailureCount,
            @Param("candidateAvgQuality") BigDecimal candidateAvgQuality,
            @Param("candidateAvgEfficiency") BigDecimal candidateAvgEfficiency,
            @Param("candidateAvgLatency") BigDecimal candidateAvgLatency,
            @Param("candidateAvgCost") BigDecimal candidateAvgCost,
            @Param("failRateRatio") BigDecimal failRateRatio);
}
