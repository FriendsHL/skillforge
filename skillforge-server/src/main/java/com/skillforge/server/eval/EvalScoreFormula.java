package com.skillforge.server.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * EVAL-V2 M4: versioned multi-dimensional score aggregation.
 */
public final class EvalScoreFormula {

    public static final String FORMULA_VERSION = "M4_V1";
    public static final double PASS_THRESHOLD = 40.0;
    public static final double QUALITY_FLOOR_THRESHOLD = 30.0;
    public static final double QUALITY_FLOOR_CAP = 39.0;

    private static final double QUALITY_WEIGHT = 0.55;
    private static final double EFFICIENCY_WEIGHT = 0.20;
    private static final double LATENCY_WEIGHT = 0.15;
    private static final double COST_WEIGHT = 0.10;

    private EvalScoreFormula() {
    }

    public static Result calculate(double qualityScore,
                                   double efficiencyScore,
                                   Long latencyMs,
                                   Long latencyThresholdMs,
                                   BigDecimal costUsd,
                                   BigDecimal costThresholdUsd,
                                   Integer loopCount,
                                   Integer toolCallCount) {
        double normalizedQuality = clampScore(qualityScore);
        double normalizedEfficiency = clampScore(efficiencyScore);
        double latencyScore = normalizeThresholdMetric(latencyMs, latencyThresholdMs);
        double costScore = normalizeThresholdMetric(costUsd, costThresholdUsd);

        double rawComposite = normalizedQuality * QUALITY_WEIGHT
                + normalizedEfficiency * EFFICIENCY_WEIGHT
                + latencyScore * LATENCY_WEIGHT
                + costScore * COST_WEIGHT;

        boolean qualityFloorApplied = normalizedQuality < QUALITY_FLOOR_THRESHOLD
                && rawComposite > QUALITY_FLOOR_CAP;
        double finalComposite = qualityFloorApplied ? QUALITY_FLOOR_CAP : rawComposite;

        String breakdownJson = buildBreakdownJson(
                latencyMs,
                costUsd,
                loopCount,
                toolCallCount,
                normalizedQuality,
                normalizedEfficiency,
                latencyScore,
                costScore,
                qualityFloorApplied
        );

        return new Result(
                FORMULA_VERSION,
                round(normalizedQuality),
                round(normalizedEfficiency),
                round(latencyScore),
                round(costScore),
                round(finalComposite),
                breakdownJson
        );
    }

    static double normalizeThresholdMetric(Long observed, Long threshold) {
        if (observed == null || observed < 0) {
            return 0.0;
        }
        if (threshold == null || threshold <= 0) {
            return 100.0;
        }
        if (observed <= threshold) {
            return 100.0;
        }
        if (observed >= threshold * 2) {
            return 0.0;
        }
        return 100.0 * ((threshold * 2.0) - observed) / threshold;
    }

    static double normalizeThresholdMetric(BigDecimal observed, BigDecimal threshold) {
        if (observed == null || observed.signum() < 0) {
            return 0.0;
        }
        if (threshold == null || threshold.signum() <= 0) {
            return 100.0;
        }
        if (observed.compareTo(threshold) <= 0) {
            return 100.0;
        }
        BigDecimal doubledThreshold = threshold.multiply(BigDecimal.valueOf(2));
        if (observed.compareTo(doubledThreshold) >= 0) {
            return 0.0;
        }
        BigDecimal numerator = doubledThreshold.subtract(observed).multiply(BigDecimal.valueOf(100));
        return numerator.divide(threshold, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private static double clampScore(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String buildBreakdownJson(Long latencyMs,
                                             BigDecimal costUsd,
                                             Integer loopCount,
                                             Integer toolCallCount,
                                             double qualityScore,
                                             double efficiencyScore,
                                             double latencyScore,
                                             double costScore,
                                             boolean qualityFloorApplied) {
        String costLiteral = costUsd == null ? "null" : costUsd.stripTrailingZeros().toPlainString();
        return "{"
                + "\"formulaVersion\":\"" + FORMULA_VERSION + "\","
                + "\"weights\":{\"quality\":" + QUALITY_WEIGHT
                + ",\"efficiency\":" + EFFICIENCY_WEIGHT
                + ",\"latency\":" + LATENCY_WEIGHT
                + ",\"cost\":" + COST_WEIGHT + "},"
                + "\"raw\":{\"latencyMs\":" + (latencyMs == null ? "null" : latencyMs)
                + ",\"costUsd\":" + costLiteral
                + ",\"loopCount\":" + (loopCount == null ? "null" : loopCount)
                + ",\"toolCallCount\":" + (toolCallCount == null ? "null" : toolCallCount) + "},"
                + "\"scores\":{\"quality\":" + round(qualityScore)
                + ",\"efficiency\":" + round(efficiencyScore)
                + ",\"latency\":" + round(latencyScore)
                + ",\"cost\":" + round(costScore) + "},"
                + "\"caps\":{\"qualityFloorApplied\":" + qualityFloorApplied + "}"
                + "}";
    }

    public record Result(
            String formulaVersion,
            double qualityScore,
            double efficiencyScore,
            double latencyScore,
            double costScore,
            double compositeScore,
            String breakdownJson
    ) {
    }
}
