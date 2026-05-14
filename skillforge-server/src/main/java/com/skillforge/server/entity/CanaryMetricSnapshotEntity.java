package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SKILL-CANARY-ROLLOUT V2: one row per hour bucket per canary rollout, written
 * by the {@code metrics-collector} agent's hourly tick (Phase 1.4
 * RecomputeMetrics tool).
 *
 * <p>Backed by V77 {@code t_canary_metric_snapshot}. FK to
 * {@code t_canary_rollout(id)} with {@code ON DELETE CASCADE} so deleting a
 * rollout auto-purges its metric history.
 *
 * <p>{@code uq_metric_canary_bucket} (canary_id, bucket_at) makes the hourly
 * recompute idempotent: re-running the same hour is a no-op.
 *
 * <p>Decimal precision matches {@code EvalScoreFormula} M4_V2:
 * quality/efficiency 0-100 ({@code DECIMAL(5,2)}), latency ms
 * ({@code DECIMAL(10,2)}), cost USD ({@code DECIMAL(10,6)}). Null is allowed
 * on every avg_* column because the relevant sample bucket may be empty for
 * a given hour.
 */
@Entity
@Table(
        name = "t_canary_metric_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_metric_canary_bucket",
                columnNames = {"canary_id", "bucket_at"}
        )
)
public class CanaryMetricSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "canary_id", nullable = false)
    private Long canaryId;

    @Column(name = "bucket_at", nullable = false)
    private Instant bucketAt;

    @Column(name = "control_sample_size", nullable = false)
    private Integer controlSampleSize = 0;

    @Column(name = "control_success_count", nullable = false)
    private Integer controlSuccessCount = 0;

    @Column(name = "control_failure_count", nullable = false)
    private Integer controlFailureCount = 0;

    @Column(name = "control_avg_quality", precision = 5, scale = 2)
    private BigDecimal controlAvgQuality;

    @Column(name = "control_avg_efficiency", precision = 5, scale = 2)
    private BigDecimal controlAvgEfficiency;

    @Column(name = "control_avg_latency", precision = 10, scale = 2)
    private BigDecimal controlAvgLatency;

    @Column(name = "control_avg_cost", precision = 10, scale = 6)
    private BigDecimal controlAvgCost;

    @Column(name = "candidate_sample_size", nullable = false)
    private Integer candidateSampleSize = 0;

    @Column(name = "candidate_success_count", nullable = false)
    private Integer candidateSuccessCount = 0;

    @Column(name = "candidate_failure_count", nullable = false)
    private Integer candidateFailureCount = 0;

    @Column(name = "candidate_avg_quality", precision = 5, scale = 2)
    private BigDecimal candidateAvgQuality;

    @Column(name = "candidate_avg_efficiency", precision = 5, scale = 2)
    private BigDecimal candidateAvgEfficiency;

    @Column(name = "candidate_avg_latency", precision = 10, scale = 2)
    private BigDecimal candidateAvgLatency;

    @Column(name = "candidate_avg_cost", precision = 10, scale = 6)
    private BigDecimal candidateAvgCost;

    @Column(name = "fail_rate_ratio", precision = 6, scale = 3)
    private BigDecimal failRateRatio;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CanaryMetricSnapshotEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCanaryId() { return canaryId; }
    public void setCanaryId(Long canaryId) { this.canaryId = canaryId; }

    public Instant getBucketAt() { return bucketAt; }
    public void setBucketAt(Instant bucketAt) { this.bucketAt = bucketAt; }

    public Integer getControlSampleSize() { return controlSampleSize; }
    public void setControlSampleSize(Integer controlSampleSize) { this.controlSampleSize = controlSampleSize; }

    public Integer getControlSuccessCount() { return controlSuccessCount; }
    public void setControlSuccessCount(Integer controlSuccessCount) { this.controlSuccessCount = controlSuccessCount; }

    public Integer getControlFailureCount() { return controlFailureCount; }
    public void setControlFailureCount(Integer controlFailureCount) { this.controlFailureCount = controlFailureCount; }

    public BigDecimal getControlAvgQuality() { return controlAvgQuality; }
    public void setControlAvgQuality(BigDecimal controlAvgQuality) { this.controlAvgQuality = controlAvgQuality; }

    public BigDecimal getControlAvgEfficiency() { return controlAvgEfficiency; }
    public void setControlAvgEfficiency(BigDecimal controlAvgEfficiency) { this.controlAvgEfficiency = controlAvgEfficiency; }

    public BigDecimal getControlAvgLatency() { return controlAvgLatency; }
    public void setControlAvgLatency(BigDecimal controlAvgLatency) { this.controlAvgLatency = controlAvgLatency; }

    public BigDecimal getControlAvgCost() { return controlAvgCost; }
    public void setControlAvgCost(BigDecimal controlAvgCost) { this.controlAvgCost = controlAvgCost; }

    public Integer getCandidateSampleSize() { return candidateSampleSize; }
    public void setCandidateSampleSize(Integer candidateSampleSize) { this.candidateSampleSize = candidateSampleSize; }

    public Integer getCandidateSuccessCount() { return candidateSuccessCount; }
    public void setCandidateSuccessCount(Integer candidateSuccessCount) { this.candidateSuccessCount = candidateSuccessCount; }

    public Integer getCandidateFailureCount() { return candidateFailureCount; }
    public void setCandidateFailureCount(Integer candidateFailureCount) { this.candidateFailureCount = candidateFailureCount; }

    public BigDecimal getCandidateAvgQuality() { return candidateAvgQuality; }
    public void setCandidateAvgQuality(BigDecimal candidateAvgQuality) { this.candidateAvgQuality = candidateAvgQuality; }

    public BigDecimal getCandidateAvgEfficiency() { return candidateAvgEfficiency; }
    public void setCandidateAvgEfficiency(BigDecimal candidateAvgEfficiency) { this.candidateAvgEfficiency = candidateAvgEfficiency; }

    public BigDecimal getCandidateAvgLatency() { return candidateAvgLatency; }
    public void setCandidateAvgLatency(BigDecimal candidateAvgLatency) { this.candidateAvgLatency = candidateAvgLatency; }

    public BigDecimal getCandidateAvgCost() { return candidateAvgCost; }
    public void setCandidateAvgCost(BigDecimal candidateAvgCost) { this.candidateAvgCost = candidateAvgCost; }

    public BigDecimal getFailRateRatio() { return failRateRatio; }
    public void setFailRateRatio(BigDecimal failRateRatio) { this.failRateRatio = failRateRatio; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
