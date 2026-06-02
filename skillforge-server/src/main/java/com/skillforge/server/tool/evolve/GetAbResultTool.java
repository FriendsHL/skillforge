package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentEvolveAbRunEntity;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.improve.agent.AgentEvolveAbEvalService;
import com.skillforge.server.repository.AgentEvolveAbRunRepository;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * (prompt: delta ≥ 15pp; skill: delta ≥ 15pp AND candidate ≥ 40pp;
 * behavior_rule: dual-criteria) so the orchestrator can reason before calling
 * {@link PromoteCandidateTool}. It does NOT bypass any guard — the real promote
 * still runs the guarded service.
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

    // Gate constants mirrored from the promote services (advisory wouldPromote only).
    private static final double PROMPT_DELTA_THRESHOLD_PP = 15.0;
    private static final double SKILL_DELTA_THRESHOLD_PP = 15.0;
    private static final double SKILL_MIN_CANDIDATE_RATE_PP = 40.0;

    private final PromptAbRunRepository promptAbRunRepository;
    private final SkillAbRunRepository skillAbRunRepository;
    private final BehaviorRuleAbRunRepository behaviorRuleAbRunRepository;
    private final AgentEvolveAbRunRepository agentEvolveAbRunRepository;
    private final ObjectMapper objectMapper;
    private final long blockTimeoutMs;
    private final long pollIntervalMs;

    public GetAbResultTool(PromptAbRunRepository promptAbRunRepository,
                           SkillAbRunRepository skillAbRunRepository,
                           BehaviorRuleAbRunRepository behaviorRuleAbRunRepository,
                           AgentEvolveAbRunRepository agentEvolveAbRunRepository,
                           ObjectMapper objectMapper) {
        this(promptAbRunRepository, skillAbRunRepository, behaviorRuleAbRunRepository,
                agentEvolveAbRunRepository, objectMapper, DEFAULT_BLOCK_TIMEOUT_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    /** Test constructor — small timeout/interval so blocking-wait tests run fast. */
    GetAbResultTool(PromptAbRunRepository promptAbRunRepository,
                    SkillAbRunRepository skillAbRunRepository,
                    BehaviorRuleAbRunRepository behaviorRuleAbRunRepository,
                    AgentEvolveAbRunRepository agentEvolveAbRunRepository,
                    ObjectMapper objectMapper,
                    long blockTimeoutMs,
                    long pollIntervalMs) {
        this.promptAbRunRepository = promptAbRunRepository;
        this.skillAbRunRepository = skillAbRunRepository;
        this.behaviorRuleAbRunRepository = behaviorRuleAbRunRepository;
        this.agentEvolveAbRunRepository = agentEvolveAbRunRepository;
        this.objectMapper = objectMapper;
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
                + "wouldPromote, perScenario?}. perScenario is available for the prompt surface "
                + "only (skill baseline is aggregate-only). wouldPromote is advisory — call "
                + "PromoteCandidate to actually promote (guards still apply).";
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
                && run.getDeltaPassRate() >= PROMPT_DELTA_THRESHOLD_PP);
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
                && delta >= SKILL_DELTA_THRESHOLD_PP
                && cand >= SKILL_MIN_CANDIDATE_RATE_PP);
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
     * {@code targetDeltaPp} meaningfully positive AND {@code regressionDeltaPp ≥}
     * {@link AgentEvolveAbEvalService#REGRESSION_FLOOR_PP}. In regression-only mode
     * (no target subset → {@code targetDeltaPp} null) it keeps only when the general
     * subset strictly improves. There is NO agent-surface promote gate in V1
     * (PromoteCandidate rejects surface=agent, §7 B2) — this is reasoning signal for
     * the orchestrator (Phase 3), not a promotable verdict.
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
        r.put("perScenario", parsePerScenario(run.getAbScenarioResultsJson()));
        return r;
    }

    /**
     * §8 子点② vs-best dual-criteria advisory gate. Target subset present →
     * targetDeltaPp strictly &gt; floor AND regressionDeltaPp ≥ regression floor.
     * No target subset (regression-only) → keep only when general strictly improves.
     */
    private static boolean agentWouldPromote(Double targetDeltaPp, Double regressionDeltaPp) {
        if (targetDeltaPp == null) {
            return regressionDeltaPp != null && regressionDeltaPp > 0.0;
        }
        boolean targetOk = targetDeltaPp > AgentEvolveAbEvalService.TARGET_MIN_DELTA_PP;
        boolean regressionOk = regressionDeltaPp != null
                && regressionDeltaPp >= AgentEvolveAbEvalService.REGRESSION_FLOOR_PP;
        return targetOk && regressionOk;
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
