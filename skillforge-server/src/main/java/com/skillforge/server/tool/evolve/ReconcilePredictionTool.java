package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentEvolveAbRunEntity;
import com.skillforge.server.evolve.dto.PredictionDto;
import com.skillforge.server.evolve.dto.ReconciliationDto;
import com.skillforge.server.improve.AbEvalPipeline;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.repository.AgentEvolveAbRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b (G3) — deterministic reconciliation of a
 * per-iteration {@link PredictionDto} against a whole-agent A/B run's per-scenario
 * flips. Read-only, NO LLM, NO writes: pure data comparison the orchestrator runs
 * after {@code GetAbResult} so "predicted-flip vs actual-flip" becomes a signal
 * fed into the next round.
 *
 * <p><b>Baseline source.</b> A "flip to pass" is baseline-not-pass → candidate-pass.
 * For a fresh round the baseline is THIS run's baseline side; for a carry-forward
 * (skip-baseline) round THIS run's baseline side is the CACHED sentinel, so the
 * real comparison point is the prior winner's CANDIDATE side — exactly the
 * cross-round pairwise baseline the A/B itself scores against (mirrors
 * {@code AgentEvolveAbEvalService.computeDeltas}). The tool resolves the right
 * baseline per run and only scores scenarios measured on BOTH sides.
 *
 * <p><b>Honest confidence.</b> {@code confidence = hits/(hits+misses)} over the
 * EVALUABLE predicted-flip ids only (both arms measured); when none are evaluable
 * (empty {@code flipToPass}, or a run with no usable per-scenario data) it is null
 * rather than a misleading 0.
 *
 * <p><b>Recursion guard (invariant).</b> Registered ONLY in the main
 * {@code SkillRegistry}; ABSENT from {@code WorkflowSkillRegistryFactory}.
 *
 * <p><b>Blind-test safe.</b> Operates only on scenario ids + pass/fail flips; it
 * never reads or emits anything about HOW a candidate changed.
 */
public class ReconcilePredictionTool implements Tool {

    public static final String NAME = "ReconcilePrediction";

    private static final Logger log = LoggerFactory.getLogger(ReconcilePredictionTool.class);

    private final AgentEvolveAbRunRepository agentEvolveAbRunRepository;
    private final ObjectMapper objectMapper;

    public ReconcilePredictionTool(AgentEvolveAbRunRepository agentEvolveAbRunRepository,
                                   ObjectMapper objectMapper) {
        this.agentEvolveAbRunRepository = agentEvolveAbRunRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Deterministically reconcile a falsifiable prediction against a whole-agent A/B run's "
                + "per-scenario flips (read-only, no LLM). Inputs:\n"
                + "- \"prediction\": {issueId?, targetProblem, flipToPass:[scenarioId], "
                + "riskToFail:[scenarioId], rationale?} — the prediction you staked before the A/B "
                + "(names the problem + scenario ids only).\n"
                + "- \"abRunId\": the whole-agent A/B run id (surface=agent) returned by TriggerAbEval.\n"
                + "- \"targetAgentId\": the agent that owns the run — validated before any data is read.\n"
                + "Returns {issueId, targetProblem, hits, misses, riskHits, surprises, confidence}: "
                + "hits = flipToPass ids that actually flipped to PASS; misses = predicted flips that "
                + "did not; riskHits = riskToFail ids that actually regressed to FAIL; surprises = "
                + "scenarios that flipped but were not predicted; confidence = hits/(hits+misses) over "
                + "the evaluable predicted flips (null when none are evaluable). Feed the result into "
                + "your next-round reflection.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> prediction = new LinkedHashMap<>();
        prediction.put("type", "object");
        prediction.put("description", "{issueId?, targetProblem, flipToPass:[scenarioId], "
                + "riskToFail:[scenarioId], rationale?}");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prediction", prediction);
        properties.put("abRunId", Map.of("type", "string",
                "description", "The whole-agent A/B run id (surface=agent)."));
        properties.put("targetAgentId", Map.of("type", "string",
                "description", "The agent that owns the run (validated)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("prediction", "abRunId", "targetAgentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (prediction, abRunId, targetAgentId)");
            }
            PredictionDto prediction = parsePrediction(input.get("prediction"));
            if (prediction == null) {
                return SkillResult.validationError("prediction is required (object with targetProblem + "
                        + "flipToPass/riskToFail scenario ids)");
            }
            String abRunId = trimToNull(input.get("abRunId"));
            if (abRunId == null) {
                return SkillResult.validationError("abRunId is required");
            }
            String targetAgentId = trimToNull(input.get("targetAgentId"));
            if (targetAgentId == null) {
                return SkillResult.validationError("targetAgentId is required");
            }

            AgentEvolveAbRunEntity run = agentEvolveAbRunRepository.findById(abRunId).orElse(null);
            if (run == null) {
                return SkillResult.error("agent A/B run not found: " + abRunId);
            }
            // SECURITY: ownership — don't reconcile against another agent's run.
            if (!targetAgentId.equals(run.getAgentId())) {
                log.warn("[ReconcilePrediction] REJECTED ownership mismatch abRunId={} runAgentId={} targetAgentId={}",
                        abRunId, run.getAgentId(), targetAgentId);
                Map<String, Object> rejected = new LinkedHashMap<>();
                rejected.put("status", "rejected");
                rejected.put("reason", "abRunId does not belong to targetAgentId " + targetAgentId);
                return SkillResult.success(objectMapper.writeValueAsString(rejected));
            }

            Map<String, ScenarioOutcome> outcomes = buildOutcomes(run);
            ReconciliationDto recon = reconcile(prediction.flipToPass(), prediction.riskToFail(), outcomes);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("issueId", prediction.issueId());
            payload.put("targetProblem", prediction.targetProblem());
            payload.put("hits", recon.hits());
            payload.put("misses", recon.misses());
            payload.put("riskHits", recon.riskHits());
            payload.put("surprises", recon.surprises());
            payload.put("confidence", recon.confidence());

            log.info("[ReconcilePrediction] abRunId={} issueId={} hits={} misses={} riskHits={} surprises={} confidence={}",
                    abRunId, prediction.issueId(), recon.hits().size(), recon.misses().size(),
                    recon.riskHits().size(), recon.surprises().size(), recon.confidence());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("ReconcilePrediction execute failed", e);
            return SkillResult.error("ReconcilePrediction error: " + e.getMessage());
        }
    }

    /**
     * Build {@code scenarioId → outcome} from the run's per-scenario results,
     * resolving the correct baseline side: prior winner's candidate side for a
     * skip-baseline (carry-forward) round, else this run's baseline side.
     */
    private Map<String, ScenarioOutcome> buildOutcomes(AgentEvolveAbRunEntity run) {
        List<AbScenarioResult> current = parsePerScenario(run.getAbScenarioResultsJson());
        Map<String, AbScenarioResult.RunResult> baselineSide;
        if (run.isSkipBaseline() && run.getPriorWinnerAbRunId() != null) {
            AgentEvolveAbRunEntity prior =
                    agentEvolveAbRunRepository.findById(run.getPriorWinnerAbRunId()).orElse(null);
            List<AbScenarioResult> priorPs = prior == null
                    ? List.of() : parsePerScenario(prior.getAbScenarioResultsJson());
            baselineSide = indexCandidateSide(priorPs);   // cross-round pairwise baseline
        } else {
            baselineSide = indexBaselineSide(current);
        }
        Map<String, ScenarioOutcome> outcomes = new LinkedHashMap<>();
        for (AbScenarioResult r : current) {
            if (r == null || r.scenarioId() == null) continue;
            AbScenarioResult.RunResult cand = r.candidate();
            AbScenarioResult.RunResult base = baselineSide.get(r.scenarioId());
            outcomes.put(r.scenarioId(), new ScenarioOutcome(
                    measured(base), passOf(base), measured(cand), passOf(cand)));
        }
        return outcomes;
    }

    /**
     * Pure deterministic reconciliation over pre-resolved per-scenario outcomes.
     * A predicted/actual flip is only scored when BOTH arms were measured.
     */
    static ReconciliationDto reconcile(List<String> flipToPass, List<String> riskToFail,
                                       Map<String, ScenarioOutcome> outcomes) {
        Set<String> flip = new LinkedHashSet<>(nullToEmpty(flipToPass));
        Set<String> risk = new LinkedHashSet<>(nullToEmpty(riskToFail));

        List<String> hits = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        for (String id : flip) {
            ScenarioOutcome o = outcomes.get(id);
            if (o == null || !o.bothMeasured()) {
                continue;   // not evaluable → excluded from hits AND from the confidence denominator
            }
            if (o.flippedToPass()) {
                hits.add(id);
            } else {
                misses.add(id);
            }
        }

        List<String> riskHits = new ArrayList<>();
        for (String id : risk) {
            ScenarioOutcome o = outcomes.get(id);
            if (o != null && o.bothMeasured() && o.regressedToFail()) {
                riskHits.add(id);
            }
        }

        List<String> surprises = new ArrayList<>();
        for (Map.Entry<String, ScenarioOutcome> e : outcomes.entrySet()) {
            String id = e.getKey();
            if (flip.contains(id) || risk.contains(id)) continue;
            if (e.getValue().bothMeasured() && e.getValue().flippedEither()) {
                surprises.add(id);
            }
        }

        int evaluable = hits.size() + misses.size();
        Double confidence = evaluable == 0 ? null : (double) hits.size() / evaluable;
        return new ReconciliationDto(hits, misses, riskHits, surprises, confidence);
    }

    /** Per-scenario pass/measured facts for both arms. */
    record ScenarioOutcome(boolean baselineMeasured, boolean baselinePass,
                           boolean candidateMeasured, boolean candidatePass) {
        boolean bothMeasured() { return baselineMeasured && candidateMeasured; }
        boolean flippedToPass() { return !baselinePass && candidatePass; }
        boolean regressedToFail() { return baselinePass && !candidatePass; }
        boolean flippedEither() { return baselinePass != candidatePass; }
    }

    private List<AbScenarioResult> parsePerScenario(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<AbScenarioResult> parsed = objectMapper.readValue(
                    json, new TypeReference<List<AbScenarioResult>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("[ReconcilePrediction] failed to parse abScenarioResultsJson: {}", e.getMessage());
            return List.of();
        }
    }

    private static Map<String, AbScenarioResult.RunResult> indexCandidateSide(List<AbScenarioResult> ps) {
        Map<String, AbScenarioResult.RunResult> map = new LinkedHashMap<>();
        for (AbScenarioResult r : ps) {
            if (r != null && r.scenarioId() != null) map.put(r.scenarioId(), r.candidate());
        }
        return map;
    }

    private static Map<String, AbScenarioResult.RunResult> indexBaselineSide(List<AbScenarioResult> ps) {
        Map<String, AbScenarioResult.RunResult> map = new LinkedHashMap<>();
        for (AbScenarioResult r : ps) {
            if (r != null && r.scenarioId() != null) map.put(r.scenarioId(), r.baseline());
        }
        return map;
    }

    private static boolean measured(AbScenarioResult.RunResult rr) {
        return rr != null && AbEvalPipeline.isMeasured(rr.status());
    }

    private static boolean passOf(AbScenarioResult.RunResult rr) {
        return rr != null && AbEvalPipeline.isPass(rr);
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private PredictionDto parsePrediction(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            if (raw instanceof Map<?, ?> map) {
                return objectMapper.convertValue(map, PredictionDto.class);
            }
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(s, PredictionDto.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("prediction must be an object "
                    + "{targetProblem, flipToPass:[...], riskToFail:[...]} (got: " + raw + ")");
        }
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
