package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.AbEvalRunRequest;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.improve.behavior.BehaviorRuleAbEvalService;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module B (FR-B1 / FR-B4) — thin, agent-callable
 * wrapper that triggers the EXISTING async A/B-eval engine for one of the three
 * optimisation surfaces. This tool does NOT compute anything: it routes to the
 * surface-specific service (the same dispatch the auto-trigger listener uses,
 * {@code OptimizationEventAutoTriggerListener}) and returns the {@code abRunId}.
 *
 * <p><b>Async fire-and-trigger.</b> The underlying services build the ab_run row
 * synchronously then defer the actual eval compute to a background executor
 * (prompt: {@code registerSynchronization(afterCommit)}; skill / behavior_rule:
 * the surface coordinator). This call returns immediately with the run id; the
 * orchestrator polls {@link GetAbResultTool} for the scores.
 *
 * <p><b>Surface routing</b> (copies the listener switch, ~lines 154/177/199):
 * <ul>
 *   <li>{@code prompt} → {@link PromptImproverService#runAbTestAgainst(AbEvalRunRequest)}</li>
 *   <li>{@code skill} → {@link SkillDraftService#startAbTestFromDraft(String, List)}
 *       ({@code candidateId} is the skill <em>draft</em> id)</li>
 *   <li>{@code behavior_rule} → {@link BehaviorRuleAbEvalService#startAbForVersion(String, String)}
 *       ({@code candidateId} is the behavior-rule <em>version</em> id)</li>
 * </ul>
 *
 * <p><b>Candidate ownership.</b> For skill and behavior_rule surfaces the candidate
 * entity (draft / version) is loaded before triggering to confirm its owner-agent
 * matches the caller-supplied {@code targetAgentId}. A mismatch is rejected before
 * any eval compute is fired. Prompt surface already threads {@code targetAgentId}
 * as {@code agentId} in the {@link AbEvalRunRequest}, so the service itself enforces
 * it.
 *
 * <p><b>B4 baseline caching.</b> Per surface, the EXISTING service decides
 * whether the baseline side must be re-run; this tool deliberately does NOT add
 * its own caching layer (that would be re-implementing compute). Concretely:
 * <ul>
 *   <li><b>prompt</b>: {@code baselineId=null} resolves to the agent's active
 *       prompt version; the pipeline reuses a prior baseline eval run where one
 *       exists (genesis path only materialises a baseline the first time).</li>
 *   <li><b>skill</b>: the A/B pairs the candidate draft against a freshly-forked
 *       empty baseline; reuse is governed by the service's
 *       {@code baselineEvalRunId} lookup, not by this tool.</li>
 *   <li><b>behavior_rule</b>: baseline = "candidate rule not injected"; the
 *       service supersedes any in-flight run for the same candidate and runs
 *       with-vs-without against the agent's default dataset.</li>
 * </ul>
 * The optional {@code baselineId} / {@code evalScenarioIds} / {@code datasetVersionId}
 * inputs are passed through to the prompt service where it supports them.
 *
 * <p><b>Recursion guard (invariant).</b> Registered ONLY in the main
 * {@code SkillRegistry} (see {@code SkillForgeConfig}); deliberately ABSENT from
 * {@code WorkflowSkillRegistryFactory} (the workflow sub-agent registry). A
 * workflow sub-agent reaching {@code TriggerAbEval} would re-open a fan-out /
 * recursion path — same invariant as {@code RunWorkflowTool} (see its comment at
 * {@code RunWorkflowTool.java:46-50}). The orchestrator runs top-level, so its
 * A/B fan-out stays 2 layers deep (same as the existing flywheel).
 *
 * <p><b>FR-C7 — per-agent A/B budget cap (security CRIT-1 / HIGH-2 fix).</b>
 * The orchestrator's {@code maxIter} lives in its agent prompt (an untrusted
 * surface) and the generic engine {@code maxLoops=25} fallback is too loose for
 * real A/B compute. The cap is enforced on {@code targetAgentId}, which is
 * ALWAYS REQUIRED in this tool — so an LLM that omits {@code evolveRunId}
 * cannot bypass it. Implementation: count total {@code evolve_iteration} steps
 * across ALL evolve runs for the agent
 * ({@link FlywheelRunService#countEvolveAbTriggersForAgent(Long)}); when
 * {@code evolveRunId} is also provided use the higher of the per-run count vs
 * the per-agent count. REJECT when the count reaches the configurable cap
 * ({@code skillforge.evolve.ab-budget-per-run}, default
 * {@value #DEFAULT_AB_BUDGET_PER_RUN}). A DB error during the cap check
 * <b>fails closed</b> (rejects the trigger) — security checks must not allow
 * through on failure.
 */
public class TriggerAbEvalTool implements Tool {

    public static final String NAME = "TriggerAbEval";

    /** FR-C7: default per-evolve-run A/B trigger cap when none is configured. */
    public static final int DEFAULT_AB_BUDGET_PER_RUN = 30;

    private static final Logger log = LoggerFactory.getLogger(TriggerAbEvalTool.class);

    private final PromptImproverService promptImproverService;
    private final SkillDraftService skillDraftService;
    private final BehaviorRuleAbEvalService behaviorRuleAbEvalService;
    private final SkillDraftRepository skillDraftRepository;
    private final BehaviorRuleVersionRepository behaviorRuleVersionRepository;
    private final FlywheelRunService flywheelRunService;
    private final int abBudgetPerRun;
    private final ObjectMapper objectMapper;

    public TriggerAbEvalTool(PromptImproverService promptImproverService,
                             SkillDraftService skillDraftService,
                             BehaviorRuleAbEvalService behaviorRuleAbEvalService,
                             SkillDraftRepository skillDraftRepository,
                             BehaviorRuleVersionRepository behaviorRuleVersionRepository,
                             FlywheelRunService flywheelRunService,
                             int abBudgetPerRun,
                             ObjectMapper objectMapper) {
        this.promptImproverService = promptImproverService;
        this.skillDraftService = skillDraftService;
        this.behaviorRuleAbEvalService = behaviorRuleAbEvalService;
        this.skillDraftRepository = skillDraftRepository;
        this.behaviorRuleVersionRepository = behaviorRuleVersionRepository;
        this.flywheelRunService = flywheelRunService;
        this.abBudgetPerRun = abBudgetPerRun > 0 ? abBudgetPerRun : DEFAULT_AB_BUDGET_PER_RUN;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Trigger an asynchronous A/B evaluation of a candidate change against a baseline "
                + "for the target agent, reusing the existing eval engine. Inputs:\n"
                + "- \"surface\": one of \"prompt\", \"skill\", \"behavior_rule\".\n"
                + "- \"candidateId\": the candidate id for that surface — a prompt version id "
                + "(prompt), a skill draft id (skill), or a behavior-rule version id (behavior_rule).\n"
                + "- \"targetAgentId\": the agent being evolved.\n"
                + "- \"baselineId\" (optional, prompt only): baseline prompt version id; omit / null "
                + "to use the agent's current active prompt.\n"
                + "- \"evalScenarioIds\" (optional): explicit scenario ids; omit to use the agent's "
                + "held-out set / dataset.\n"
                + "- \"datasetVersionId\" (optional): pin the run to an immutable dataset snapshot "
                + "(mutually exclusive with evalScenarioIds; prompt/behavior_rule).\n"
                + "- \"evolveRunId\" (optional): the evolve run this A/B belongs to. Optional metadata "
                + "— the per-agent A/B budget cap fires regardless (keyed on targetAgentId).\n"
                + "Returns an \"abRunId\" immediately — the eval runs in the background. Poll "
                + "GetAbResult with that id to read the baseline/candidate scores when terminal.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("surface", Map.of(
                "type", "string",
                "enum", List.of(EvolveSurface.PROMPT.wire(),
                        EvolveSurface.SKILL.wire(),
                        EvolveSurface.BEHAVIOR_RULE.wire()),
                "description", "Optimisation surface: \"prompt\", \"skill\", or \"behavior_rule\"."
        ));
        properties.put("candidateId", Map.of(
                "type", "string",
                "description", "Candidate id for the surface (prompt version id / skill draft id / "
                        + "behavior-rule version id)."
        ));
        properties.put("targetAgentId", Map.of(
                "type", "string",
                "description", "The agent being evolved (numeric agent id)."
        ));
        properties.put("baselineId", Map.of(
                "type", "string",
                "description", "Optional baseline prompt version id (prompt surface only); "
                        + "omit / null to compare against the agent's active prompt. For a "
                        + "hill-climb iteration 2+, set this to the current-best prompt version."
        ));
        properties.put("cachedBaselineScore", Map.of(
                "type", "number",
                "description", "Optional (prompt surface, hill-climb): the current-best's already-"
                        + "known pass-rate (0..100). When supplied the A/B runs CANDIDATE-ONLY and "
                        + "reuses this as the baseline score — never re-measures the baseline "
                        + "(avoids re-eval noise + halves the work). Pair with baselineId."
        ));
        properties.put("evalScenarioIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional explicit eval scenario ids; omit to use the agent's "
                        + "held-out set."
        ));
        properties.put("datasetVersionId", Map.of(
                "type", "string",
                "description", "Optional immutable dataset version to pin the run to (mutually "
                        + "exclusive with evalScenarioIds)."
        ));
        properties.put("evolveRunId", Map.of(
                "type", "string",
                "description", "Optional evolve-run id for traceability / per-run budget precision. "
                        + "The per-agent A/B budget cap fires unconditionally on targetAgentId "
                        + "regardless of whether this is supplied."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("surface", "candidateId", "targetAgentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError(
                        "input is required (surface, candidateId, targetAgentId)");
            }
            EvolveSurface surface = EvolveSurface.fromWire(trimToNull(input.get("surface")));
            if (surface == null) {
                return SkillResult.validationError(
                        "surface is required and must be one of: " + EvolveSurface.acceptedValues());
            }
            String candidateId = trimToNull(input.get("candidateId"));
            if (candidateId == null) {
                return SkillResult.validationError("candidateId is required");
            }
            String targetAgentId = trimToNull(input.get("targetAgentId"));
            if (targetAgentId == null) {
                return SkillResult.validationError("targetAgentId is required");
            }

            // FR-C7 (CRIT-1 fix): per-agent A/B budget cap — ALWAYS enforced on
            // targetAgentId (which is required), never optional. An LLM that
            // omits evolveRunId cannot bypass the cap. evolveRunId is retained as
            // optional metadata for per-run precision (we use the higher of the
            // per-agent count vs the per-run count as the budget consumed).
            String evolveRunId = trimToNull(input.get("evolveRunId"));
            SkillResult capReject = enforceAbBudget(targetAgentId, evolveRunId);
            if (capReject != null) {
                return capReject;
            }

            // SECURITY: validate candidate belongs to targetAgentId before firing
            // any eval compute. Prompt threads targetAgentId via AbEvalRunRequest
            // so the service enforces it; skill and behavior_rule need an explicit
            // pre-trigger ownership check mirroring PromoteCandidateTool.
            if (surface == EvolveSurface.SKILL) {
                SkillResult reject = validateSkillOwnership(candidateId, targetAgentId);
                if (reject != null) {
                    return reject;
                }
            } else if (surface == EvolveSurface.BEHAVIOR_RULE) {
                SkillResult reject = validateBehaviorRuleOwnership(candidateId, targetAgentId);
                if (reject != null) {
                    return reject;
                }
            }

            String abRunId = switch (surface) {
                case PROMPT -> triggerPrompt(input, candidateId, targetAgentId);
                case SKILL -> skillDraftService.startAbTestFromDraft(
                        candidateId, evalScenarioIds(input));
                case BEHAVIOR_RULE -> behaviorRuleAbEvalService.startAbForVersion(
                        candidateId, trimToNull(input.get("datasetVersionId")));
            };

            log.info("[TriggerAbEval] surface={} candidateId={} targetAgentId={} -> abRunId={}",
                    surface.wire(), candidateId, targetAgentId, abRunId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("abRunId", abRunId);
            response.put("surface", surface.wire());
            response.put("status", "started");
            response.put("message", "A/B eval started asynchronously. Poll GetAbResult with "
                    + "surface=" + surface.wire() + " and abRunId=" + abRunId + " until terminal.");
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (IllegalArgumentException e) {
            // Bad candidate / agent / mutually-exclusive args → the LLM should fix + retry.
            return SkillResult.validationError(e.getMessage());
        } catch (IllegalStateException e) {
            // Surface preconditions not met (e.g. no dataset, candidate not in expected
            // status, active A/B already in flight) — surface the message to the agent.
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("TriggerAbEval execute failed", e);
            return SkillResult.error("TriggerAbEval error: " + e.getMessage());
        }
    }

    /**
     * FR-C7 (CRIT-1 fix) — enforce the per-agent A/B budget cap. Always keyed
     * on {@code targetAgentId} (required); {@code evolveRunId} is optional and
     * used for additional precision (we take the higher of per-agent vs per-run
     * count to be conservative). Returns a rejection {@link SkillResult} when the
     * cap is reached, or {@code null} to proceed.
     *
     * <p><b>Fail closed (HIGH-3 fix):</b> if the DB count throws, we REJECT the
     * trigger rather than allowing it through. A security check that fails open is
     * no security check.
     */
    private SkillResult enforceAbBudget(String targetAgentId, String evolveRunId) {
        long agentCount;
        try {
            long agentId = Long.parseLong(targetAgentId);
            agentCount = flywheelRunService.countEvolveAbTriggersForAgent(agentId);
        } catch (RuntimeException e) {
            // HIGH-3 fix: fail closed — if the budget count fails, REJECT rather
            // than allowing through. Security checks must not fail open.
            log.error("[TriggerAbEval] FR-C7 per-agent budget count FAILED for targetAgentId={}: {} "
                    + "— rejecting to fail closed",
                    targetAgentId, e.getMessage());
            return SkillResult.error(
                    "A/B budget check failed (targetAgentId=" + targetAgentId
                            + "): cannot confirm budget headroom — trigger rejected for safety");
        }

        // Optional per-run refinement: use the higher of agent-wide vs run-specific
        // count so we're conservative in both directions.
        long used = agentCount;
        if (evolveRunId != null) {
            try {
                long runCount = flywheelRunService.countEvolveAbTriggers(evolveRunId);
                used = Math.max(agentCount, runCount);
            } catch (RuntimeException e) {
                // Per-run count is supplementary — if it fails, fall back to per-agent
                // count (already have it). This sub-path is not a security boundary
                // because the per-agent count already enforces the cap.
                log.warn("[TriggerAbEval] FR-C7 per-run count failed for evolveRunId={}, "
                        + "using per-agent count={}: {}", evolveRunId, agentCount, e.getMessage());
            }
        }

        if (used >= abBudgetPerRun) {
            log.warn("[TriggerAbEval] FR-C7 REJECTED: targetAgentId={} reached A/B budget cap "
                    + "(used={} cap={} evolveRunId={})", targetAgentId, used, abBudgetPerRun, evolveRunId);
            String msg = "per-agent A/B budget reached (targetAgentId=" + targetAgentId
                    + ", used=" + used + ", cap=" + abBudgetPerRun + "): stop triggering A/B "
                    + "and summarize the kept candidates for human review";
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "rejected");
                body.put("reason", msg);
                body.put("targetAgentId", targetAgentId);
                body.put("abBudgetUsed", used);
                body.put("abBudgetCap", abBudgetPerRun);
                if (evolveRunId != null) {
                    body.put("evolveRunId", evolveRunId);
                }
                return SkillResult.success(objectMapper.writeValueAsString(body));
            } catch (Exception e) {
                return SkillResult.error(msg);
            }
        }
        return null;
    }

    /**
     * Validates that the skill draft's {@code targetAgentId} matches the caller's
     * {@code targetAgentId}. Returns a rejection {@link SkillResult} on mismatch,
     * or {@code null} if ownership is confirmed (proceed to trigger).
     */
    private SkillResult validateSkillOwnership(String candidateId, String targetAgentId) {
        SkillDraftEntity draft = skillDraftRepository.findById(candidateId).orElse(null);
        if (draft == null) {
            // Let the downstream service produce the "not found" error so we stay thin.
            return null;
        }
        Long draftAgentId = draft.getTargetAgentId();
        // SkillDraftEntity.targetAgentId is the agent being evolved (Long); compare as string.
        if (draftAgentId == null || !targetAgentId.equals(String.valueOf(draftAgentId))) {
            log.warn("[TriggerAbEval] REJECTED ownership mismatch surface=skill "
                    + "candidateId={} draftTargetAgentId={} targetAgentId={}",
                    candidateId, draftAgentId, targetAgentId);
            return ownershipRejected("skill", candidateId, targetAgentId);
        }
        return null;
    }

    /**
     * Validates that the behavior-rule version's {@code agentId} matches the caller's
     * {@code targetAgentId}. Returns a rejection {@link SkillResult} on mismatch,
     * or {@code null} if ownership is confirmed (proceed to trigger).
     */
    private SkillResult validateBehaviorRuleOwnership(String candidateId, String targetAgentId) {
        BehaviorRuleVersionEntity version =
                behaviorRuleVersionRepository.findById(candidateId).orElse(null);
        if (version == null) {
            // Let the downstream service produce the "not found" error.
            return null;
        }
        if (!targetAgentId.equals(version.getAgentId())) {
            log.warn("[TriggerAbEval] REJECTED ownership mismatch surface=behavior_rule "
                    + "candidateId={} versionAgentId={} targetAgentId={}",
                    candidateId, version.getAgentId(), targetAgentId);
            return ownershipRejected("behavior_rule", candidateId, targetAgentId);
        }
        return null;
    }

    private SkillResult ownershipRejected(String surface, String candidateId, String targetAgentId) {
        String msg = "candidate ownership mismatch (surface=" + surface + ", candidateId="
                + candidateId + "): candidate does not belong to targetAgentId " + targetAgentId;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "rejected");
            body.put("reason", msg);
            return SkillResult.success(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            return SkillResult.error(msg);
        }
    }

    private String triggerPrompt(Map<String, Object> input, String candidateId, String targetAgentId) {
        // B4: baselineId=null → service resolves the agent's active prompt and reuses a
        // prior baseline eval run where one exists. We don't force a baseline re-run.
        // BUG-1 hill-climb: cachedBaselineScore (0..100) makes the run candidate-only
        // and reuses that score as the baseline pass-rate (winner-carry-forward) — the
        // orchestrator supplies the current-best's score so the baseline isn't re-measured.
        AbEvalRunRequest req = new AbEvalRunRequest(
                targetAgentId,
                trimToNull(input.get("baselineId")),
                candidateId,
                evalScenarioIds(input),
                trimToNull(input.get("datasetVersionId")),
                parseRate(input.get("cachedBaselineScore")));
        return promptImproverService.runAbTestAgainst(req);
    }

    /**
     * Parse an optional cached baseline pass-rate (0..100); null/blank → null silently
     * (the caller simply didn't supply one → fresh-baseline mode). A <b>present-but-invalid</b>
     * value (non-numeric or out of range) also degrades to null, but is logged at WARN:
     * silently reverting to a re-measured baseline is the exact BUG-1 noise we're fixing,
     * so a malformed score from the orchestrator must be visible rather than swallowed.
     */
    private static Double parseRate(Object value) {
        String s = trimToNull(value);
        if (s == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(s);
            if (d >= 0.0 && d <= 100.0) {
                return d;
            }
            log.warn("[TriggerAbEval] cachedBaselineScore out of range [0,100]: '{}' — ignoring "
                    + "(A/B will re-measure the baseline fresh, reintroducing noise)", s);
            return null;
        } catch (NumberFormatException e) {
            log.warn("[TriggerAbEval] cachedBaselineScore not a number: '{}' — ignoring "
                    + "(A/B will re-measure the baseline fresh, reintroducing noise)", s);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> evalScenarioIds(Map<String, Object> input) {
        Object raw = input.get("evalScenarioIds");
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            // Defensive: stringify each element so a JSON number / odd value doesn't
            // explode the downstream ClassCastException far from here.
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException(
                "evalScenarioIds must be an array of strings (got "
                        + raw.getClass().getSimpleName() + ")");
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
