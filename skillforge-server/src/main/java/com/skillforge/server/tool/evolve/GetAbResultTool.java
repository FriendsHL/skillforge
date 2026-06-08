package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.config.EvolveThresholdProperties;
import com.skillforge.server.entity.AgentEvolveAbRunEntity;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.improve.AbEvalPipeline;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.repository.AgentEvolveAbRunRepository;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module B (FR-B2) — read-only poll of a surface's
 * ab_run row. Returns {@code {status, baselineScore, candidateScore, delta,
 * deltaPassRate, wouldPromote, perScenario?}} once the run reaches a terminal
 * status; while still PENDING/RUNNING it returns just {@code {status:"running"}}
 * so the orchestrator keeps polling.
 *
 * <p>Pure data wrapper over the EXISTING ab_run repositories — no compute.
 *
 * <p><b>Ownership guard.</b> Every ab_run read is validated against the caller-
 * supplied {@code targetAgentId}: the ab_run's own {@code agentId} field must
 * match before any scores are returned. A mismatch yields
 * {@code {status:"rejected"}} with no score data, preventing one agent from
 * reading another agent's baseline/candidate scores or per-scenario details.
 *
 * <p><b>Per-scenario.</b> Only the prompt surface stores per-scenario
 * baseline+candidate results ({@code PromptAbRunEntity.abScenarioResultsJson}).
 * Skill's baseline side is aggregate-only (the baseline run is a forked empty
 * skill, no per-scenario baseline) — noted in the result. behavior_rule is
 * aggregate target/regression deltas.
 *
 * <p><b>wouldPromote</b> is advisory only: it mirrors the surface's promote gate
 * (prompt: delta ≥ prompt-delta-pp; skill: delta ≥ skill-delta-pp AND candidate ≥
 * skill-min-candidate-rate-pp; behavior_rule: dual-criteria; agent: dual-criteria
 * over {@link EvolveThresholdProperties}) so the orchestrator can reason before
 * calling {@link PromoteCandidateTool}. It does NOT bypass any guard — the real
 * promote still runs the guarded service. F5 (2026-06-07): the gate values come
 * from the SAME {@link EvolveThresholdProperties} bean the promote services use
 * (the previous mirrored constants here could silently drift).
 *
 * <p><b>Agent-surface measurement fields</b> (F3, 2026-06-07): terminal agent
 * responses additionally report {@code totalN} / {@code measuredN} (overall
 * pairwise-measured count) / {@code targetMeasuredN} / {@code generalMeasuredN}
 * (per-subset, from the persisted subset tags) plus a {@code thresholds} echo of
 * the effective gate values, so the deterministic workflow can apply the
 * min-measured-N keep guard without hardcoding a second copy of any threshold.
 */
public class GetAbResultTool implements Tool {

    public static final String NAME = "GetAbResult";

    private static final Logger log = LoggerFactory.getLogger(GetAbResultTool.class);

    // Terminal statuses across all three surfaces share the same vocabulary
    // (COMPLETED / FAILED, plus skill ERROR + behavior_rule SUPERSEDED).
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("COMPLETED", "FAILED", "ERROR", "SUPERSEDED");

    /**
     * Block up to this long waiting for the A/B run to reach a terminal status,
     * re-reading every {@link #DEFAULT_POLL_INTERVAL_MS}. Like {@code GetOptReport},
     * a blocking call keeps the orchestrator's agent loop at 1-2 iterations per
     * A/B instead of dozens of tight polls (an A/B runs for minutes), so it doesn't
     * blow the agent-loop's maxLoops budget. Bounded; the orchestrator calls again
     * to extend the wait.
     */
    static final long DEFAULT_BLOCK_TIMEOUT_MS = 90_000L;
    static final long DEFAULT_POLL_INTERVAL_MS = 3_000L;

    /** EVOLVE-LOOP-HILLCLIMB — cap per-direction perScenarioFlips entries to bound prompt size. */
    static final int MAX_FLIPS = 20;

    private final PromptAbRunRepository promptAbRunRepository;
    private final SkillAbRunRepository skillAbRunRepository;
    private final BehaviorRuleAbRunRepository behaviorRuleAbRunRepository;
    private final AgentEvolveAbRunRepository agentEvolveAbRunRepository;
    private final ObjectMapper objectMapper;
    // F5: the SAME properties bean the promote services use — no mirrored constants.
    private final EvolveThresholdProperties thresholds;
    private final long blockTimeoutMs;
    private final long pollIntervalMs;

    public GetAbResultTool(PromptAbRunRepository promptAbRunRepository,
                           SkillAbRunRepository skillAbRunRepository,
                           BehaviorRuleAbRunRepository behaviorRuleAbRunRepository,
                           AgentEvolveAbRunRepository agentEvolveAbRunRepository,
                           ObjectMapper objectMapper,
                           EvolveThresholdProperties thresholds) {
        this(promptAbRunRepository, skillAbRunRepository, behaviorRuleAbRunRepository,
                agentEvolveAbRunRepository, objectMapper, thresholds,
                DEFAULT_BLOCK_TIMEOUT_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    /** Test constructor — small timeout/interval so blocking-wait tests run fast. */
    GetAbResultTool(PromptAbRunRepository promptAbRunRepository,
                    SkillAbRunRepository skillAbRunRepository,
                    BehaviorRuleAbRunRepository behaviorRuleAbRunRepository,
                    AgentEvolveAbRunRepository agentEvolveAbRunRepository,
                    ObjectMapper objectMapper,
                    EvolveThresholdProperties thresholds,
                    long blockTimeoutMs,
                    long pollIntervalMs) {
        this.promptAbRunRepository = promptAbRunRepository;
        this.skillAbRunRepository = skillAbRunRepository;
        this.behaviorRuleAbRunRepository = behaviorRuleAbRunRepository;
        this.agentEvolveAbRunRepository = agentEvolveAbRunRepository;
        this.objectMapper = objectMapper;
        this.thresholds = thresholds;
        this.blockTimeoutMs = blockTimeoutMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Poll the status + scores of an A/B eval run (read-only). Inputs:\n"
                + "- \"surface\": one of \"prompt\", \"skill\", \"behavior_rule\".\n"
                + "- \"abRunId\": the run id returned by TriggerAbEval.\n"
                + "- \"targetAgentId\": the agent that owns this run — validated before scores are "
                + "returned; prevents reading another agent's eval results.\n"
                + "While the run is PENDING/RUNNING returns {status:\"running\"}; once terminal "
                + "returns {status, baselineScore, candidateScore, delta, deltaPassRate, "
                + "wouldPromote, perScenario?}. perScenario is available for the prompt and agent "
                + "surfaces (skill baseline is aggregate-only). surface=agent additionally returns "
                + "{totalN, measuredN, targetMeasuredN, generalMeasuredN} (pairwise-measured "
                + "scenario counts; infra ERROR/TIMEOUT excluded), the hill-climb "
                + "{weightedScore, baselineWeightedScore} (wG*generalRate + wH*harvestRate, "
                + "re-normalised over present subsets; null when no subset measured), "
                + "{perScenarioFlips:{regressed,improved,regressedTotal,improvedTotal}} "
                + "(per-case pass→fail / fail→pass reversals for history grounding, each list "
                + "capped at 20), and a {thresholds} object echoing the effective promote/keep "
                + "gate values (incl. minMeasuredN, anchorErosionFloorPp, weightGeneral, "
                + "weightHarvest, minImprovePp, noImproveStreakLimit, targetWeightedScore) — "
                + "read thresholds from here instead of hardcoding. "
                + "wouldPromote is advisory — call PromoteCandidate to actually promote (guards "
                + "still apply).";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("surface", Map.of(
                "type", "string",
                "enum", EvolveSurface.agentAbWireValues(),
                "description", "Optimisation surface: \"prompt\", \"skill\", \"behavior_rule\", or \"agent\"."
        ));
        properties.put("abRunId", Map.of(
                "type", "string",
                "description", "The A/B run id returned by TriggerAbEval."
        ));
        properties.put("targetAgentId", Map.of(
                "type", "string",
                "description", "The agent that owns this run (numeric agent id). Validated before "
                        + "scores are returned — prevents cross-agent score leakage."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("surface", "abRunId", "targetAgentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError(
                        "input is required (surface, abRunId, targetAgentId)");
            }
            EvolveSurface surface = EvolveSurface.fromWire(trimToNull(input.get("surface")));
            if (surface == null) {
                return SkillResult.validationError(
                        "surface is required and must be one of: " + EvolveSurface.acceptedValues());
            }
            String abRunId = trimToNull(input.get("abRunId"));
            if (abRunId == null) {
                return SkillResult.validationError("abRunId is required");
            }
            String targetAgentId = trimToNull(input.get("targetAgentId"));
            if (targetAgentId == null) {
                return SkillResult.validationError("targetAgentId is required");
            }

            // Block (bounded) until the A/B reaches a terminal status, re-reading
            // every pollIntervalMs. Keeps the orchestrator's agent loop at 1-2
            // iterations per A/B (which runs for minutes) instead of dozens of
            // tight polls that would blow its maxLoops budget. Each read is its own
            // autocommit query, so it observes the eval pipeline's status commits.
            long deadline = System.currentTimeMillis() + blockTimeoutMs;
            Map<String, Object> result;
            while (true) {
                result = switch (surface) {
                    case PROMPT -> readPrompt(abRunId, targetAgentId);
                    case SKILL -> readSkill(abRunId, targetAgentId);
                    case BEHAVIOR_RULE -> readBehaviorRule(abRunId, targetAgentId);
                    case AGENT -> readAgent(abRunId, targetAgentId);
                };
                if (result == null) {
                    return SkillResult.error("A/B run not found for surface=" + surface.wire()
                            + " abRunId=" + abRunId);
                }
                // "running" = still PENDING/RUNNING; anything else (terminal score
                // payload / "rejected" ownership mismatch) is returned immediately.
                boolean stillRunning = "running".equals(result.get("status"));
                if (!stillRunning || System.currentTimeMillis() >= deadline) {
                    break;
                }
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return SkillResult.success(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.error("GetAbResult execute failed", e);
            return SkillResult.error("GetAbResult error: " + e.getMessage());
        }
    }

    private Map<String, Object> readPrompt(String abRunId, String targetAgentId) {
        PromptAbRunEntity run = promptAbRunRepository.findById(abRunId).orElse(null);
        if (run == null) {
            return null;
        }
        // SECURITY: validate ownership before exposing any score data.
        Map<String, Object> ownershipViolation = checkOwnership(
                "prompt", abRunId, run.getAgentId(), targetAgentId);
        if (ownershipViolation != null) {
            return ownershipViolation;
        }
        if (!isTerminal(run.getStatus())) {
            return running(run.getStatus());
        }
        Map<String, Object> r = base(run.getStatus(),
                run.getBaselinePassRate(), run.getCandidatePassRate(), run.getDeltaPassRate());
        r.put("wouldPromote", run.getDeltaPassRate() != null
                && run.getDeltaPassRate() >= thresholds.getPromptDeltaPp());
        r.put("perScenario", parsePerScenario(run.getAbScenarioResultsJson()));
        return r;
    }

    private Map<String, Object> readSkill(String abRunId, String targetAgentId) {
        SkillAbRunEntity run = skillAbRunRepository.findById(abRunId).orElse(null);
        if (run == null) {
            return null;
        }
        // SECURITY: validate ownership before exposing any score data.
        Map<String, Object> ownershipViolation = checkOwnership(
                "skill", abRunId, run.getAgentId(), targetAgentId);
        if (ownershipViolation != null) {
            return ownershipViolation;
        }
        if (!isTerminal(run.getStatus())) {
            return running(run.getStatus());
        }
        Map<String, Object> r = base(run.getStatus(),
                run.getBaselinePassRate(), run.getCandidatePassRate(), run.getDeltaPassRate());
        Double delta = run.getDeltaPassRate();
        Double cand = run.getCandidatePassRate();
        r.put("wouldPromote", delta != null && cand != null
                && delta >= thresholds.getSkillDeltaPp()
                && cand >= thresholds.getSkillMinCandidateRatePp());
        // Skill baseline is aggregate-only — no per-scenario baseline is stored
        // (the baseline run is a forked empty skill). Documented for the agent.
        r.put("perScenario", null);
        r.put("perScenarioNote",
                "skill baseline is aggregate-only; no per-scenario baseline available");
        return r;
    }

    private Map<String, Object> readBehaviorRule(String abRunId, String targetAgentId) {
        BehaviorRuleAbRunEntity run = behaviorRuleAbRunRepository.findById(abRunId).orElse(null);
        if (run == null) {
            return null;
        }
        // SECURITY: validate ownership before exposing any score data.
        Map<String, Object> ownershipViolation = checkOwnership(
                "behavior_rule", abRunId, run.getAgentId(), targetAgentId);
        if (ownershipViolation != null) {
            return ownershipViolation;
        }
        if (!isTerminal(run.getStatus())) {
            return running(run.getStatus());
        }
        Map<String, Object> r = base(run.getStatus(),
                run.getBaselinePassRate(), run.getCandidatePassRate(), run.getDeltaPassRate());
        r.put("wouldPromote", BehaviorRulePromotionService.isDualCriteriaSatisfied(run));
        r.put("targetDeltaPp", run.getTargetDeltaPp());
        r.put("regressionDeltaPp", run.getRegressionDeltaPp());
        r.put("perScenario", null);
        return r;
    }

    /**
     * AUTOEVOLVE-AGENT-LEVEL-BUNDLE — whole-agent A/B read. Maps the
     * {@code t_agent_evolve_ab_run} row to {@code {status, baselineScore,
     * candidateScore, delta, deltaPassRate, wouldPromote, perScenario}} with the
     * same ownership-guard pattern as the other surfaces.
     *
     * <p><b>Dual-criteria {@code wouldPromote}</b> (§8 子点②, vs-best, advisory only):
     * {@code targetDeltaPp} strictly &gt; agent-target-min-delta-pp AND
     * {@code regressionDeltaPp ≥} agent-regression-floor-pp (both from
     * {@link EvolveThresholdProperties}). In regression-only mode (no target subset →
     * {@code targetDeltaPp} null — incl. the F2 "target present but never measured"
     * sentinel) it keeps only when the general subset strictly improves. There is NO
     * agent-surface promote gate in V1 (PromoteCandidate rejects surface=agent,
     * §7 B2) — this is reasoning signal for the orchestrator/workflow, not a
     * promotable verdict.
     *
     * <p><b>F3 measurement fields</b>: {@code totalN} (all scenarios in the run),
     * {@code measuredN} (overall pairwise-measured: fresh run → both arms non-infra;
     * skip run → this candidate AND the prior winner's candidate both non-infra),
     * {@code targetMeasuredN} / {@code generalMeasuredN} (measured count per persisted
     * subset tag; null on legacy rows without tags), and a {@code thresholds} echo —
     * the workflow keep-guard reads {@code thresholds.minMeasuredN} from here.
     */
    private Map<String, Object> readAgent(String abRunId, String targetAgentId) {
        AgentEvolveAbRunEntity run = agentEvolveAbRunRepository.findById(abRunId).orElse(null);
        if (run == null) {
            return null;
        }
        // SECURITY: validate ownership before exposing any score data.
        Map<String, Object> ownershipViolation = checkOwnership(
                "agent", abRunId, run.getAgentId(), targetAgentId);
        if (ownershipViolation != null) {
            return ownershipViolation;
        }
        if (!isTerminal(run.getStatus())) {
            return running(run.getStatus());
        }
        Map<String, Object> r = base(run.getStatus(),
                run.getBaselinePassRate(), run.getCandidatePassRate(), run.getDeltaPassRate());
        Double targetDeltaPp = run.getTargetDeltaPp();
        Double regressionDeltaPp = run.getRegressionDeltaPp();
        r.put("targetDeltaPp", targetDeltaPp);
        r.put("regressionDeltaPp", regressionDeltaPp);
        // item 4 — absolute per-subset rates [0,100] (null in regression-only mode);
        // the orchestrator remembers round-1 baselineGeneralRate as the vs-original
        // general anchor and gates each candidate's candidateGeneralRate against it.
        r.put("candidateTargetRate", run.getCandidateTargetRate());
        r.put("candidateGeneralRate", run.getCandidateGeneralRate());
        r.put("baselineTargetRate", run.getBaselineTargetRate());
        r.put("baselineGeneralRate", run.getBaselineGeneralRate());
        r.put("wouldPromote", agentWouldPromote(targetDeltaPp, regressionDeltaPp));
        // EVOLVE-LOOP-HILLCLIMB §2: weightedScore = wG*generalRate + wH*harvestRate
        // (harvest subset = target subset; null sentinel = subset 0-measured → drops out,
        // re-normalised over present subsets). Computed here at read time from the already-
        // persisted per-subset rates using the thresholds bean — no entity / column change.
        r.put("weightedScore", computeWeightedScore(
                run.getCandidateGeneralRate(), run.getCandidateTargetRate(),
                thresholds.getWeightGeneral(), thresholds.getWeightHarvest()));
        r.put("baselineWeightedScore", computeWeightedScore(
                run.getBaselineGeneralRate(), run.getBaselineTargetRate(),
                thresholds.getWeightGeneral(), thresholds.getWeightHarvest()));
        // EVOLVE-LOOP-HILLCLIMB: per-scenario flips (regressed / improved) so the
        // candidate-gen leaf can ground next-round decisions on concrete reversals.
        r.put("perScenarioFlips", computePerScenarioFlips(run));
        putAgentMeasurement(r, run);
        r.put("thresholds", thresholdsEcho());
        r.put("perScenario", parsePerScenario(run.getAbScenarioResultsJson()));
        return r;
    }

    /**
     * EVOLVE-LOOP-HILLCLIMB §2 — the convex hill-climb score, re-normalised over the
     * subsets actually present. Each subset rate that is non-null contributes its weight
     * to both the numerator and the denominator; a null rate (the F2 "subset present but
     * 0 measured" sentinel, or no subset at all) drops out entirely rather than being
     * counted as 0 (which would unfairly drag the score down). Both rates null → null
     * (no signal). When only the general subset is present (empty harvest) the result is
     * exactly the general pass rate.
     */
    static Double computeWeightedScore(Double generalRate, Double harvestRate,
                                       double wGeneral, double wHarvest) {
        double wSum = 0.0;
        double acc = 0.0;
        if (generalRate != null) {
            wSum += wGeneral;
            acc += wGeneral * generalRate;
        }
        if (harvestRate != null) {
            wSum += wHarvest;
            acc += wHarvest * harvestRate;
        }
        return wSum <= 0.0 ? null : acc / wSum;
    }

    /**
     * EVOLVE-LOOP-HILLCLIMB — classify each pairwise-measured scenario as regressed
     * (baseline passed, candidate failed) or improved (baseline failed, candidate
     * passed), so the candidate-gen leaf gets concrete per-case reversals as history
     * grounding (the workflow threads {@code perScenarioFlips.regressed} into the next
     * round's prompt). "Measured" mirrors {@link #putAgentMeasurement} exactly: fresh run
     * → pairwise (both arms non-infra); skip run → this candidate AND the prior winner's
     * candidate side both non-infra, degrading to candidate-side-only when the prior row
     * is unavailable. The baseline pass side is {@code s.baseline()} for a fresh run, the
     * prior winner's candidate side for a skip run.
     *
     * <p>Each entry is {@code {scenarioId, scenarioName, rationale}} (candidate-side
     * rationale). Each list is capped at {@link #MAX_FLIPS} to bound prompt size; when
     * truncated, {@code regressedTotal} / {@code improvedTotal} report the full counts.
     * Read-path only: any parse failure / absent JSON → null (never throws).
     */
    private Map<String, Object> computePerScenarioFlips(AgentEvolveAbRunEntity run) {
        List<AbScenarioResult> perScenario = readPerScenarioTyped(run.getAbScenarioResultsJson());
        if (perScenario == null) {
            return null;
        }
        Map<String, AbScenarioResult.RunResult> priorCandidate = null;
        if (run.isSkipBaseline()) {
            priorCandidate = loadPriorWinnerCandidateSide(run.getPriorWinnerAbRunId());
        }
        List<Map<String, Object>> regressed = new ArrayList<>();
        List<Map<String, Object>> improved = new ArrayList<>();
        int regressedTotal = 0;
        int improvedTotal = 0;
        for (AbScenarioResult s : perScenario) {
            String candidateStatus = s.candidate() != null ? s.candidate().status() : null;
            // Determine measured + the baseline-side RunResult to test for pass.
            AbScenarioResult.RunResult baselineSide;
            boolean measured;
            if (run.isSkipBaseline()) {
                AbScenarioResult.RunResult prior = priorCandidate != null
                        ? priorCandidate.get(s.scenarioId()) : null;
                if (priorCandidate != null) {
                    measured = AbEvalPipeline.isMeasured(candidateStatus)
                            && prior != null && AbEvalPipeline.isMeasured(prior.status());
                } else {
                    // prior winner unavailable → candidate-side-only (legacy degrade).
                    measured = AbEvalPipeline.isMeasured(candidateStatus);
                }
                baselineSide = prior;
            } else {
                String baselineStatus = s.baseline() != null ? s.baseline().status() : null;
                measured = AbEvalPipeline.pairwiseMeasured(candidateStatus, baselineStatus);
                baselineSide = s.baseline();
            }
            if (!measured) {
                continue;
            }
            boolean candidatePass = AbEvalPipeline.isPass(s.candidate());
            boolean baselinePass = AbEvalPipeline.isPass(baselineSide);
            if (baselinePass && !candidatePass) {
                regressedTotal++;
                if (regressed.size() < MAX_FLIPS) {
                    regressed.add(flipEntry(s));
                }
            } else if (!baselinePass && candidatePass) {
                improvedTotal++;
                if (improved.size() < MAX_FLIPS) {
                    improved.add(flipEntry(s));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("regressed", regressed);
        out.put("improved", improved);
        out.put("regressedTotal", regressedTotal);
        out.put("improvedTotal", improvedTotal);
        return out;
    }

    private static Map<String, Object> flipEntry(AbScenarioResult s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarioId", s.scenarioId());
        m.put("scenarioName", s.scenarioName());
        m.put("rationale", s.candidate() != null ? s.candidate().rationale() : null);
        return m;
    }

    /**
     * §8 子点② vs-best dual-criteria advisory gate (F5: config-driven floors).
     * Target subset present → targetDeltaPp strictly &gt; agent-target-min-delta-pp
     * AND regressionDeltaPp ≥ agent-regression-floor-pp. No target subset
     * (regression-only) → keep only when general strictly improves.
     */
    private boolean agentWouldPromote(Double targetDeltaPp, Double regressionDeltaPp) {
        if (targetDeltaPp == null) {
            return regressionDeltaPp != null && regressionDeltaPp > 0.0;
        }
        boolean targetOk = targetDeltaPp > thresholds.getAgentTargetMinDeltaPp();
        boolean regressionOk = regressionDeltaPp != null
                && regressionDeltaPp >= thresholds.getAgentRegressionFloorPp();
        return targetOk && regressionOk;
    }

    /**
     * F3 — compute totalN / measuredN / targetMeasuredN / generalMeasuredN from the
     * run's persisted per-scenario JSON. "Measured" mirrors
     * {@code AgentEvolveAbEvalService.computeDeltas}'s denominator semantics:
     * <ul>
     *   <li>fresh run → same-round pairwise (both arms non-infra);</li>
     *   <li>skip run → cross-round pairwise (this run's candidate AND the prior
     *       winner's candidate side both non-infra); when the prior winner row /
     *       JSON is unavailable (legacy), degrades to candidate-side-only with a
     *       warn.</li>
     * </ul>
     * Per-subset counts come from the persisted subset tags (F3); legacy rows
     * without tags report null (unknown), never a misleading 0. Read-path only:
     * any parse failure degrades to null fields, never an error.
     */
    private void putAgentMeasurement(Map<String, Object> r, AgentEvolveAbRunEntity run) {
        List<AbScenarioResult> perScenario = readPerScenarioTyped(run.getAbScenarioResultsJson());
        if (perScenario == null) {
            r.put("totalN", null);
            r.put("measuredN", null);
            r.put("targetMeasuredN", null);
            r.put("generalMeasuredN", null);
            return;
        }
        Map<String, AbScenarioResult.RunResult> priorCandidate = null;
        if (run.isSkipBaseline()) {
            priorCandidate = loadPriorWinnerCandidateSide(run.getPriorWinnerAbRunId());
        }
        boolean hasSubsetTags = false;
        int measured = 0;
        int targetMeasured = 0;
        int generalMeasured = 0;
        for (AbScenarioResult s : perScenario) {
            if (s.subset() != null) {
                hasSubsetTags = true;
            }
            boolean m;
            String candidateStatus = s.candidate() != null ? s.candidate().status() : null;
            if (run.isSkipBaseline()) {
                if (priorCandidate != null) {
                    AbScenarioResult.RunResult prior = priorCandidate.get(s.scenarioId());
                    m = AbEvalPipeline.isMeasured(candidateStatus)
                            && prior != null && AbEvalPipeline.isMeasured(prior.status());
                } else {
                    // prior winner unavailable → candidate-side-only (legacy degrade).
                    m = AbEvalPipeline.isMeasured(candidateStatus);
                }
            } else {
                String baselineStatus = s.baseline() != null ? s.baseline().status() : null;
                m = AbEvalPipeline.pairwiseMeasured(candidateStatus, baselineStatus);
            }
            if (m) {
                measured++;
                if (AbScenarioResult.SUBSET_TARGET.equals(s.subset())) {
                    targetMeasured++;
                } else if (AbScenarioResult.SUBSET_GENERAL.equals(s.subset())) {
                    generalMeasured++;
                }
            }
        }
        r.put("totalN", perScenario.size());
        r.put("measuredN", measured);
        r.put("targetMeasuredN", hasSubsetTags ? targetMeasured : null);
        r.put("generalMeasuredN", hasSubsetTags ? generalMeasured : null);
    }

    /** F5 — echo the effective gate thresholds so the workflow never hardcodes a copy. */
    private Map<String, Object> thresholdsEcho() {
        Map<String, Object> th = new LinkedHashMap<>();
        th.put("promptDeltaPp", thresholds.getPromptDeltaPp());
        th.put("skillDeltaPp", thresholds.getSkillDeltaPp());
        th.put("skillMinCandidateRatePp", thresholds.getSkillMinCandidateRatePp());
        th.put("agentTargetMinDeltaPp", thresholds.getAgentTargetMinDeltaPp());
        th.put("agentRegressionFloorPp", thresholds.getAgentRegressionFloorPp());
        th.put("minMeasuredN", thresholds.getMinMeasuredN());
        th.put("anchorErosionFloorPp", thresholds.getAnchorErosionFloorPp());
        // EVOLVE-LOOP-HILLCLIMB 阶段 A: the workflow reads these from here (never hardcodes).
        // targetWeightedScore is nullable — null is emitted verbatim, meaning "no target-stop".
        th.put("weightGeneral", thresholds.getWeightGeneral());
        th.put("weightHarvest", thresholds.getWeightHarvest());
        th.put("minImprovePp", thresholds.getMinImprovePp());
        th.put("noImproveStreakLimit", thresholds.getNoImproveStreakLimit());
        th.put("targetWeightedScore", thresholds.getTargetWeightedScore());
        return th;
    }

    /** Parse the per-scenario JSON into typed records; null on blank/parse failure. */
    private List<AbScenarioResult> readPerScenarioTyped(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AbScenarioResult>>() {});
        } catch (Exception e) {
            log.warn("[GetAbResult] failed to parse abScenarioResultsJson as typed records: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Load the prior winner run's candidate-side results keyed by scenarioId (the
     * cross-round pairwise counterpart for a skip run). Null on any failure —
     * the read path degrades instead of erroring.
     */
    private Map<String, AbScenarioResult.RunResult> loadPriorWinnerCandidateSide(
            String priorWinnerAbRunId) {
        if (priorWinnerAbRunId == null) {
            return null;
        }
        AgentEvolveAbRunEntity prior = agentEvolveAbRunRepository
                .findById(priorWinnerAbRunId).orElse(null);
        if (prior == null) {
            log.warn("[GetAbResult] prior winner ab_run {} not found — measuredN degrades to "
                    + "candidate-side-only", priorWinnerAbRunId);
            return null;
        }
        List<AbScenarioResult> priorPerScenario = readPerScenarioTyped(prior.getAbScenarioResultsJson());
        if (priorPerScenario == null) {
            return null;
        }
        Map<String, AbScenarioResult.RunResult> map = new HashMap<>();
        for (AbScenarioResult r2 : priorPerScenario) {
            map.put(r2.scenarioId(), r2.candidate());
        }
        return map;
    }

    /**
     * Returns a rejection map if the run's agentId does not match the caller's
     * targetAgentId, or {@code null} if ownership is confirmed (proceed normally).
     * Deliberately returns no score data on mismatch.
     */
    private Map<String, Object> checkOwnership(String surface, String abRunId,
                                                String runAgentId, String targetAgentId) {
        if (!targetAgentId.equals(runAgentId)) {
            log.warn("[GetAbResult] REJECTED ownership mismatch surface={} abRunId={} "
                    + "runAgentId={} targetAgentId={}", surface, abRunId, runAgentId, targetAgentId);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "rejected");
            r.put("reason", "abRunId does not belong to targetAgentId " + targetAgentId);
            return r;
        }
        return null;
    }

    private Object parsePerScenario(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            // Pass the stored per-scenario structure straight through as parsed JSON.
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("[GetAbResult] failed to parse abScenarioResultsJson: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> base(String status,
                                            Double baselineScore,
                                            Double candidateScore,
                                            Double deltaPassRate) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("baselineScore", baselineScore);
        r.put("candidateScore", candidateScore);
        // delta + deltaPassRate are the same persisted field; both keys are emitted
        // because the brief's result contract lists both names.
        r.put("delta", deltaPassRate);
        r.put("deltaPassRate", deltaPassRate);
        return r;
    }

    private static Map<String, Object> running(String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "running");
        r.put("rawStatus", status);
        return r;
    }

    private static boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status);
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
