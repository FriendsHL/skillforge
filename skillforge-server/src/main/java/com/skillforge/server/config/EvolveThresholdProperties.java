package com.skillforge.server.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AUTOEVOLVE-CLOSE-LOOP workflow-fix F5 (2026-06-07) — single source of truth for
 * the evolve promote/keep thresholds, replacing the hardcoded constants that had
 * drifted into THREE places (PromptPromotionService 15.0 / SkillAbEvalService
 * 15.0+40.0 / GetAbResultTool mirrored copies). The promote services and the
 * advisory {@code GetAbResultTool.wouldPromote} now inject THIS bean, so the
 * advisory signal can never drift from the real gate again.
 *
 * <p>Default values (2026-06-07 threshold reform):
 * <ul>
 *   <li>{@code prompt-delta-pp}: <b>5</b> (was 15 since 2026-04-16 {@code 4ec32aeb};
 *       at n=36 scenarios 15pp ≈ 5-6 net flips — structurally unreachable; 5pp ≈ 2
 *       net flips)</li>
 *   <li>{@code skill-delta-pp}: <b>5</b> (was 15, same rationale);
 *       {@code skill-min-candidate-rate-pp}: 40 (unchanged)</li>
 *   <li>{@code agent-target-min-delta-pp}: 0 and {@code agent-regression-floor-pp}:
 *       −3 (values unchanged — moved from {@code AgentEvolveAbEvalService} constants
 *       into config)</li>
 *   <li>{@code min-measured-n}: 10 (F3 — minimum overall pairwise-measured scenarios
 *       for a keep decision to be conclusive)</li>
 *   <li>{@code anchor-erosion-floor-pp}: 5 (F6 — vs-original general anchor:
 *       candidateGeneralRate must stay within this many pp of the round-1
 *       baselineGeneralRate)</li>
 * </ul>
 *
 * <p>The behavior_rule surface gate (+10pp/−3pp dual criteria in
 * {@code BehaviorRulePromotionService}) is deliberately NOT covered — it is already
 * a sane dual-criteria gate and stays out of this batch's blast radius.
 *
 * <p><b>EVOLVE-LOOP-HILLCLIMB 阶段 A (2026-06-08)</b> adds five hill-climb knobs:
 * {@code weight-general} / {@code weight-harvest} (the weightedScore =
 * wG*generalPassRate + wH*harvestPassRate convex combination, re-normalised over the
 * subsets actually present), {@code min-improve-pp} (a candidate must beat the
 * current best's weightedScore by this many pp to be kept — temp=0 noise guard),
 * {@code no-improve-streak-limit} (converge-stop after this many consecutive
 * no-new-best rounds), and {@code target-weighted-score} (nullable — when set,
 * stop as soon as best ≥ this; null = pure hill-climb, never stop on a target).
 */
@Validated
@ConfigurationProperties(prefix = "skillforge.evolve.thresholds")
public class EvolveThresholdProperties {

    /**
     * Prompt-surface promote gate: deltaPassRate must be ≥ this many pp.
     * Negative would auto-promote on a DECLINE — fail-fast at startup.
     */
    @PositiveOrZero
    private double promptDeltaPp = 5.0;

    /**
     * Skill-surface promote gate: deltaPassRate must be ≥ this many pp.
     * Negative would auto-promote on a DECLINE — fail-fast at startup.
     */
    @PositiveOrZero
    private double skillDeltaPp = 5.0;

    /** Skill-surface promote gate: candidate pass-rate must be ≥ this many pp (a rate, [0,100]). */
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private double skillMinCandidateRatePp = 40.0;

    /**
     * Agent-surface advisory gate: targetDeltaPp must be strictly &gt; this.
     * Deliberately unconstrained: a vs-best delta floor may legitimately be set
     * negative (tolerate small target noise) or positive (demand a margin).
     */
    private double agentTargetMinDeltaPp = 0.0;

    /**
     * Agent-surface advisory gate: regressionDeltaPp must be ≥ this (vs-best floor).
     * Deliberately unconstrained: typically negative (allowed regression), but 0 /
     * positive (demand general improvement) is a valid stricter policy.
     */
    private double agentRegressionFloorPp = -3.0;

    /**
     * F3 — minimum overall pairwise-measured scenario count for an agent A/B keep
     * decision to be conclusive (guards against keeping on n≈7 noise).
     * Negative would silently disable the guard (any measuredN passes) — fail-fast
     * at startup; 0 is the explicit "guard off" value.
     */
    @Min(0)
    private int minMeasuredN = 10;

    /**
     * F6 — vs-original general anchor erosion floor: a candidate's general rate may
     * sit at most this many pp below the round-1 original general rate. Negative
     * (a stricter-than-original requirement) is almost certainly a sign error in
     * config — fail-fast at startup; 0 means "no erosion allowed at all".
     */
    @PositiveOrZero
    private double anchorErosionFloorPp = 5.0;

    // ── EVOLVE-LOOP-HILLCLIMB 阶段 A (2026-06-08) ──

    /**
     * Hill-climb weightedScore: weight on the general (benchmark/regression) subset's
     * pass rate. The score is wGeneral*generalRate + wHarvest*harvestRate, re-normalised
     * over the subsets actually present (a missing subset drops out of both numerator and
     * denominator). Out of [0,1] would make the weight meaningless — fail-fast at startup.
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double weightGeneral = 0.6;

    /**
     * Hill-climb weightedScore: weight on the harvest (= target / harvested bad-case)
     * subset's pass rate. Empty harvest subset → this weight drops out and the score
     * degenerates to the pure general pass rate.
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double weightHarvest = 0.4;

    /**
     * Hill-climb keep gate: a candidate's weightedScore must beat the current best by
     * strictly more than this many pp to be kept (guards against keeping on temp=0
     * noise). Negative would keep on a DECLINE — fail-fast at startup; 0 = keep any
     * strict improvement.
     */
    @PositiveOrZero
    private double minImprovePp = 0.0;

    /**
     * Hill-climb converge-stop: stop after this many consecutive rounds that produce no
     * new best. 0 would converge-stop immediately (before any round can improve) — must
     * be ≥ 1.
     */
    @Min(1)
    private int noImproveStreakLimit = 3;

    /**
     * Hill-climb target-stop (nullable): when set, stop as soon as best.weightedScore ≥
     * this value (a rate in [0,100]). Null = pure hill-climb — never stop on a target,
     * rely on converge-stop / maxIter. jakarta validation skips null, so leaving the yml
     * key absent is the intended "no target" mode.
     */
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Double targetWeightedScore;

    public double getPromptDeltaPp() {
        return promptDeltaPp;
    }

    public void setPromptDeltaPp(double promptDeltaPp) {
        this.promptDeltaPp = promptDeltaPp;
    }

    public double getSkillDeltaPp() {
        return skillDeltaPp;
    }

    public void setSkillDeltaPp(double skillDeltaPp) {
        this.skillDeltaPp = skillDeltaPp;
    }

    public double getSkillMinCandidateRatePp() {
        return skillMinCandidateRatePp;
    }

    public void setSkillMinCandidateRatePp(double skillMinCandidateRatePp) {
        this.skillMinCandidateRatePp = skillMinCandidateRatePp;
    }

    public double getAgentTargetMinDeltaPp() {
        return agentTargetMinDeltaPp;
    }

    public void setAgentTargetMinDeltaPp(double agentTargetMinDeltaPp) {
        this.agentTargetMinDeltaPp = agentTargetMinDeltaPp;
    }

    public double getAgentRegressionFloorPp() {
        return agentRegressionFloorPp;
    }

    public void setAgentRegressionFloorPp(double agentRegressionFloorPp) {
        this.agentRegressionFloorPp = agentRegressionFloorPp;
    }

    public int getMinMeasuredN() {
        return minMeasuredN;
    }

    public void setMinMeasuredN(int minMeasuredN) {
        this.minMeasuredN = minMeasuredN;
    }

    public double getAnchorErosionFloorPp() {
        return anchorErosionFloorPp;
    }

    public void setAnchorErosionFloorPp(double anchorErosionFloorPp) {
        this.anchorErosionFloorPp = anchorErosionFloorPp;
    }

    public double getWeightGeneral() {
        return weightGeneral;
    }

    public void setWeightGeneral(double weightGeneral) {
        this.weightGeneral = weightGeneral;
    }

    public double getWeightHarvest() {
        return weightHarvest;
    }

    public void setWeightHarvest(double weightHarvest) {
        this.weightHarvest = weightHarvest;
    }

    public double getMinImprovePp() {
        return minImprovePp;
    }

    public void setMinImprovePp(double minImprovePp) {
        this.minImprovePp = minImprovePp;
    }

    public int getNoImproveStreakLimit() {
        return noImproveStreakLimit;
    }

    public void setNoImproveStreakLimit(int noImproveStreakLimit) {
        this.noImproveStreakLimit = noImproveStreakLimit;
    }

    public Double getTargetWeightedScore() {
        return targetWeightedScore;
    }

    public void setTargetWeightedScore(Double targetWeightedScore) {
        this.targetWeightedScore = targetWeightedScore;
    }
}
