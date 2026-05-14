package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryMetricSnapshotEntity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.3: wire-format DTO for the
 * {@code GET /api/canary/rollouts/{id}/metrics} endpoint.
 *
 * <p>Mirrors {@link CanaryMetricSnapshotEntity} 1:1 (per java.md footgun #6:
 * FE-BE contract — field names match Java getters so the FE TS interface
 * can mirror directly).
 *
 * <p>Decimal fields are emitted as {@link BigDecimal}; the FE
 * {@code api/canary.ts} (Phase 1.5) will parse them as {@code number} per
 * java.md footgun #6 type-mapping table.
 */
public record MetricSnapshotResponse(
        Long id,
        Long canaryId,
        Instant bucketAt,
        Integer controlSampleSize,
        Integer controlSuccessCount,
        Integer controlFailureCount,
        BigDecimal controlAvgQuality,
        BigDecimal controlAvgEfficiency,
        BigDecimal controlAvgLatency,
        BigDecimal controlAvgCost,
        Integer candidateSampleSize,
        Integer candidateSuccessCount,
        Integer candidateFailureCount,
        BigDecimal candidateAvgQuality,
        BigDecimal candidateAvgEfficiency,
        BigDecimal candidateAvgLatency,
        BigDecimal candidateAvgCost,
        BigDecimal failRateRatio,
        Instant createdAt) {

    public static MetricSnapshotResponse from(CanaryMetricSnapshotEntity e) {
        return new MetricSnapshotResponse(
                e.getId(),
                e.getCanaryId(),
                e.getBucketAt(),
                e.getControlSampleSize(),
                e.getControlSuccessCount(),
                e.getControlFailureCount(),
                e.getControlAvgQuality(),
                e.getControlAvgEfficiency(),
                e.getControlAvgLatency(),
                e.getControlAvgCost(),
                e.getCandidateSampleSize(),
                e.getCandidateSuccessCount(),
                e.getCandidateFailureCount(),
                e.getCandidateAvgQuality(),
                e.getCandidateAvgEfficiency(),
                e.getCandidateAvgLatency(),
                e.getCandidateAvgCost(),
                e.getFailRateRatio(),
                e.getCreatedAt());
    }
}
