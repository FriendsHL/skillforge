package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Scriptable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVOLVE-LOOP-HILLCLIMB 阶段 A — drives the real {@code evolve-loop.workflow.js}
 * through the {@link WorkflowEvaluator} with STUB agent/tool invokers (no LLM, no DB).
 * This is the deterministic-orchestration integration test for the hill-climb loop:
 * it proves the JS owns the loop — the for-iter (NOT for-issue) structure, the
 * candidate→multi-surface-diff→A/B→poll→record sequence, deterministic GetAbResult
 * polling, the weightedScore-driven keep decision (with the F3 min-measured-N + F6
 * anchor guards), the three stop conditions (target / converge / maxIter), and
 * whole-bundle winner carry-forward — all without any non-determinism.
 */
class EvolveLoopWorkflowTest {

    private final L1SandboxFactory sandbox = new L1SandboxFactory();
    private final WorkflowEvaluator evaluator = new WorkflowEvaluator(sandbox);
    private final ObjectMapper om = new ObjectMapper();

    private String evolveLoopBody() throws Exception {
        String src = new String(
                getClass().getResourceAsStream("/workflows/evolve-loop.workflow.js").readAllBytes(),
                StandardCharsets.UTF_8);
        return new WorkflowDefinitionRegistry().parse("evolve-loop.workflow.js", src).jsSource();
    }

    private Object run(String runId, Map<String, Object> args,
                       WorkflowAgentInvoker agent, WorkflowToolInvoker tools) throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext(runId, args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);
            return evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);
        } finally {
            exec.shutdownNow();
        }
    }

    private static Map<String, Object> args(int maxIter) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("targetAgentId", 7);
        a.put("maxIter", maxIter);
        a.put("reportId", "rep-1");
        a.put("autoApprove", true);
        return a;
    }

    // ───────────────────────── core hill-climb shape ─────────────────────────

    @Test
    @DisplayName("hill-climb: loop length is driven by maxIter (NOT issue count) — 1 issue, 3 rounds")
    void loopDrivenByMaxIterNotIssueCount() throws Exception {
        WorkflowAgentInvoker agent = candSeqAgent();
        List<Object[]> calls = new ArrayList<>();
        // single convertible issue, weightedScore strictly increases each round → keep all.
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> abBase(70.0, 70.0 + 10.0 * candN(input)), null);

        Object result = run("evolve-hc", args(3), agent, tools);

        Scriptable summary = (Scriptable) result;
        assertThat(summary.get("status", summary)).isEqualTo("completed");
        // 3 rounds despite only 1 issue — proves issue count ≠ loop length.
        assertThat(((Number) summary.get("evaluated", summary)).intValue()).isEqualTo(3);
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(3);
        assertThat(summary.get("stopReason", summary)).isEqualTo("maxIter");
        assertThat(count(calls, "TriggerAbEval")).isEqualTo(3);
        assertThat(count(calls, "RecordIteration")).isEqualTo(3);
    }

    @Test
    @DisplayName("hill-climb: candidate-gen prompt carries allIssues + currentBest, and history (with perCase) from iter≥2")
    void candPromptCarriesGlobalContext() throws Exception {
        List<String> prompts = new ArrayList<>();
        int[] seq = {0};
        WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
            prompts.add(prompt);
            seq[0]++;
            return cand(Map.of("promptVersionId", "cand-" + seq[0]), List.of("prompt"),
                    "change-" + seq[0], seq[0]);
        };
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null),
                        issue("i2", "medium", 2, 0.7, "behavior_rule", null)),
                input -> {
                    Map<String, Object> r = abBase(70.0, 70.0 + 10.0 * candN(input));
                    // per-case regression so history.perCaseRegressed is non-empty.
                    Map<String, Object> flips = new LinkedHashMap<>();
                    flips.put("regressed", List.of(Map.of("scenarioId", "s9", "scenarioName", "case-9")));
                    flips.put("improved", List.of());
                    flips.put("regressedTotal", 1);
                    flips.put("improvedTotal", 0);
                    r.put("perScenarioFlips", flips);
                    return r;
                }, null);

        run("evolve-ctx", args(2), agent, tools);

        assertThat(prompts).hasSize(2);
        // iter1 prompt: full clue library + currentBest, no history yet.
        assertThat(prompts.get(0)).contains("allIssues").contains("currentBest").contains("allowedSurfaces");
        assertThat(prompts.get(0)).contains("\"id\":\"i1\"").contains("\"id\":\"i2\"");
        // iter2 prompt: history is populated and carries the per-case regression names.
        assertThat(prompts.get(1)).contains("history");
        assertThat(prompts.get(1)).contains("perCaseRegressed");
        assertThat(prompts.get(1)).contains("case-9");
    }

    @Test
    @DisplayName("hill-climb: weightedScore improvement → kept=true")
    void weightedScoreDrivesKeep_improve() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> abBase(70.0, 85.0), null);   // candidate 85 > baseline 70

        Object result = run("evolve-keep", args(1), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(1);
        assertThat(nthInput(calls, "RecordIteration", 1).get("kept")).isEqualTo(Boolean.TRUE);
        // weightedScore is recorded as the hill-climb keep judge.
        assertThat(((Number) nthInput(calls, "RecordIteration", 1).get("weightedScore")).doubleValue())
                .isEqualTo(85.0);
    }

    @Test
    @DisplayName("hill-climb: weightedScore NOT above best+minImprove → kept=false")
    void weightedScoreDrivesKeep_noImprove() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> abBase(80.0, 80.0), null);   // candidate == baseline → no improvement

        Object result = run("evolve-nokeep", args(1), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(0);
        assertThat(nthInput(calls, "RecordIteration", 1).get("kept")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("hill-climb: converge-stop — N consecutive no-improve rounds break before maxIter")
    void convergeStop() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        // iter1 candidate 80 (keeps over baseline 70); iter2+ flat at 80 (no improve).
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> {
                    Map<String, Object> r = abBase(70.0, candN(input) == 1 ? 80.0 : 80.0);
                    r.put("thresholds", hillThresholds(2, null));   // streak limit 2
                    return r;
                }, null);

        Object result = run("evolve-converge", args(5), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        // iter1 keep, iter2 no-improve (noImprove=1), iter3 no-improve (noImprove=2≥2) → stop.
        assertThat(summary.get("stopReason", summary)).isEqualTo("converged");
        assertThat(((Number) summary.get("evaluated", summary)).intValue()).isEqualTo(3);
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(1);
        // tail rounds are not kept.
        assertThat(nthInput(calls, "RecordIteration", 2).get("kept")).isEqualTo(Boolean.FALSE);
        assertThat(nthInput(calls, "RecordIteration", 3).get("kept")).isEqualTo(Boolean.FALSE);
        assertThat(count(calls, "TriggerAbEval")).isEqualTo(3);   // not 5
    }

    @Test
    @DisplayName("hill-climb: target-stop — best crosses targetWeightedScore → break with stopReason=target")
    void targetStop() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> {
                    Map<String, Object> r = abBase(70.0, 90.0);   // 90 >= target 85
                    r.put("thresholds", hillThresholds(3, 85.0));  // target 85
                    return r;
                }, null);

        Object result = run("evolve-target", args(5), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        assertThat(summary.get("stopReason", summary)).isEqualTo("target");
        assertThat(((Number) summary.get("evaluated", summary)).intValue()).isEqualTo(1);
        assertThat(count(calls, "TriggerAbEval")).isEqualTo(1);   // stopped after the first cross
    }

    @Test
    @DisplayName("hill-climb: empty harvest subset (target rates null) → weightedScore=general, keep still works")
    void emptyHarvestDegrades() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> {
                    Map<String, Object> r = abBase(70.0, 82.0);
                    // empty harvest: target rates null; weightedScore degenerates to general.
                    r.put("candidateTargetRate", null);
                    r.put("baselineTargetRate", null);
                    r.put("candidateGeneralRate", 82.0);
                    r.put("baselineGeneralRate", 70.0);
                    return r;
                }, null);

        Object result = run("evolve-degrade", args(1), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(1);
        assertThat(nthInput(calls, "RecordIteration", 1).get("kept")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("S7: summary.best is the global maximum weightedScore (not the last round)")
    void bestIsGlobalMax() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        // iter1→85 keep, iter2→92 keep (new best), iter3→88 (< 92 → not kept).
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> {
                    int n = candN(input);
                    double w = n == 1 ? 85.0 : (n == 2 ? 92.0 : 88.0);
                    return abBase(70.0, w);
                }, null);

        Object result = run("evolve-best", args(3), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(2);
        Scriptable best = (Scriptable) summary.get("best", summary);
        assertThat(((Number) best.get("weightedScore", best)).doubleValue()).isEqualTo(92.0);
        // best bundle is iter2's candidate (cand-2), not iter3's.
        Scriptable bestBundle = (Scriptable) best.get("bundle", best);
        assertThat(bestBundle.get("promptVersionId", bestBundle)).isEqualTo("cand-2");
    }

    // ───────────────────────── guards (F3 / F6) still apply ─────────────────────────

    @Test
    @DisplayName("F3: weightedScore improves but measuredN < minMeasuredN → inconclusive, not kept")
    void minMeasuredNGuardBlocksKeep() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> {
                    Map<String, Object> r = abBase(70.0, 90.0);
                    r.put("measuredN", 5);   // < minMeasuredN 10
                    return r;
                }, null);

        Object result = run("evolve-f3", args(1), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(0);
        assertThat(nthInput(calls, "RecordIteration", 1).get("kept")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("F6: vs-original anchor — iter2 candidateGeneralRate below originalGeneral−floor → not kept")
    void anchorErosionBlocksKeep() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null),
                        issue("i2", "high", 3, 0.8, "prompt", null)),
                input -> {
                    int n = candN(input);
                    Map<String, Object> r;
                    if (n == 1) {
                        // iter1: anchor originalGeneral=80; candidate general 85 (above) → keep.
                        r = abBase(70.0, 90.0);
                        r.put("baselineGeneralRate", 80.0);
                        r.put("candidateGeneralRate", 85.0);
                    } else {
                        // iter2: weightedScore improves vs best but general eroded to 70
                        // (< 80 − 5 = 75) → anchor blocks keep.
                        r = abBase(90.0, 99.0);
                        r.put("baselineGeneralRate", 85.0);
                        r.put("candidateGeneralRate", 70.0);
                    }
                    return r;
                }, null);

        Object result = run("evolve-f6", args(2), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(1);
        assertThat(nthInput(calls, "RecordIteration", 1).get("kept")).isEqualTo(Boolean.TRUE);
        assertThat(nthInput(calls, "RecordIteration", 2).get("kept")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("F3 boundary: measuredN == minMeasuredN (exactly at threshold) → conclusive, keep allowed (guard is <)")
    void minMeasuredNInclusiveBoundary() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> {
                    Map<String, Object> r = abBase(70.0, 90.0);
                    r.put("measuredN", 10);   // EXACTLY minMeasuredN 10 — guard is strictly `<` → keep
                    return r;
                }, null);

        Object result = run("evolve-f3-boundary", args(1), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(1);
        assertThat(nthInput(calls, "RecordIteration", 1).get("kept")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("F6 boundary: candidateGeneralRate == originalGeneral − floor (exactly on floor) → keep allowed (anchor is <)")
    void anchorErosionInclusiveBoundary() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null),
                        issue("i2", "high", 3, 0.8, "prompt", null)),
                input -> {
                    int n = candN(input);
                    Map<String, Object> r;
                    if (n == 1) {
                        // iter1: anchor originalGeneral = baselineGeneralRate 80; candidate 85 → keep.
                        r = abBase(70.0, 90.0);
                        r.put("baselineGeneralRate", 80.0);
                        r.put("candidateGeneralRate", 85.0);
                    } else {
                        // iter2: general sits EXACTLY on the floor (80 − 5 = 75); anchor is strictly
                        // `<`, so 75 is NOT below the floor → keep allowed.
                        r = abBase(90.0, 99.0);
                        r.put("baselineGeneralRate", 85.0);
                        r.put("candidateGeneralRate", 75.0);
                    }
                    return r;
                }, null);

        Object result = run("evolve-f6-boundary", args(2), candSeqAgent(), tools);
        Scriptable summary = (Scriptable) result;
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(2);
        assertThat(nthInput(calls, "RecordIteration", 2).get("kept")).isEqualTo(Boolean.TRUE);
    }

    // ───────────────────────── F1 targeting + F4 win-streak ─────────────────────────

    @Test
    @DisplayName("F1: active harvested scenarios → every TriggerAbEval carries evalScenarioIds (fetched once)")
    void targetingPassesEvalScenarioIds() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        Map<String, Object> harvested = new LinkedHashMap<>();
        harvested.put("agentId", "7");
        harvested.put("scenarioIds", List.of("bad-165e0ed0"));
        harvested.put("count", 1);
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> abBase(70.0, 70.0 + 10.0 * candN(input)), harvested);

        run("evolve-f1", args(2), candSeqAgent(), tools);

        assertThat(count(calls, "ListActiveHarvestedScenarios")).isEqualTo(1);   // once, not per-iter
        assertThat(count(calls, "TriggerAbEval")).isEqualTo(2);
        for (int n = 1; n <= 2; n++) {
            Map<String, Object> ab = nthInput(calls, "TriggerAbEval", n);
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) ab.get("evalScenarioIds");
            assertThat(ids).containsExactly("bad-165e0ed0");
        }
    }

    @Test
    @DisplayName("F1: no active harvested scenarios (empty list) → TriggerAbEval has NO evalScenarioIds")
    void emptyHarvestedKeepsRoleSplit() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> abBase(70.0, 85.0), null);   // standardTools → empty harvested

        run("evolve-f1-empty", args(1), candSeqAgent(), tools);
        assertThat(nthInput(calls, "TriggerAbEval", 1).get("evalScenarioIds")).isNull();
    }

    @Test
    @DisplayName("F4: win-streak only — cachedBaselineScore+priorWinnerAbRunId after a keep; fresh two-arm after a reject")
    void winStreakBaselineCache() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> {
                    int n = candN(input);
                    // iter1 keeps (80>70); iter2 rejected (75<80); iter3 fresh again.
                    return abBase(70.0, n == 1 ? 80.0 : (n == 2 ? 75.0 : 90.0));
                }, null);

        run("evolve-f4", args(3), candSeqAgent(), tools);

        assertThat(count(calls, "TriggerAbEval")).isEqualTo(3);
        // iter1: fresh (nothing kept yet) → no cache fields.
        Map<String, Object> ab1 = nthInput(calls, "TriggerAbEval", 1);
        assertThat(ab1.get("cachedBaselineScore")).isNull();
        assertThat(ab1.get("priorWinnerAbRunId")).isNull();
        // iter2: prev round kept → cache = best score (iter1 candidateScore 80) + its abRunId.
        Map<String, Object> ab2 = nthInput(calls, "TriggerAbEval", 2);
        assertThat(((Number) ab2.get("cachedBaselineScore")).doubleValue()).isEqualTo(80.0);
        assertThat(ab2.get("priorWinnerAbRunId")).isEqualTo("ab-cand-1");
        // iter3: prev round rejected → fresh two-arm run again.
        Map<String, Object> ab3 = nthInput(calls, "TriggerAbEval", 3);
        assertThat(ab3.get("cachedBaselineScore")).isNull();
        assertThat(ab3.get("priorWinnerAbRunId")).isNull();
    }

    @Test
    @DisplayName("F4: cachedBaselineScore is the GLOBAL composite candidateScore, NOT the weightedScore")
    void winStreakCacheUsesGlobalScoreNotWeighted() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        // Harvest subset present → candidateScore (composite 88) is deliberately DIFFERENT from
        // weightedScore (0.6*60 + 0.4*72 = 64.8). If the cache wrongly used best.weightedScore it
        // would carry 64.8; the correct value is the global composite candidateScore 88.
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("status", "COMPLETED");
                    r.put("baselineScore", 70.0);
                    r.put("candidateScore", 88.0);          // global composite (what the cache must use)
                    r.put("delta", 18.0);
                    r.put("weightedScore", 64.8);           // hill-climb judge (NOT the cache value)
                    r.put("baselineWeightedScore", 60.0);
                    r.put("candidateGeneralRate", 60.0);
                    r.put("candidateTargetRate", 72.0);
                    r.put("baselineGeneralRate", 60.0);
                    r.put("baselineTargetRate", 60.0);
                    r.put("measuredN", 20);
                    r.put("thresholds", hillThresholds(3, null));
                    return r;
                }, null);

        run("evolve-f4-score", args(2), candSeqAgent(), tools);

        // iter1 keeps (weightedScore 64.8 > baselineWeighted 60). iter2 carries the cache.
        Map<String, Object> ab2 = nthInput(calls, "TriggerAbEval", 2);
        assertThat(((Number) ab2.get("cachedBaselineScore")).doubleValue()).isEqualTo(88.0);   // composite, not 64.8
        assertThat(ab2.get("priorWinnerAbRunId")).isEqualTo("ab-cand-1");
    }

    // ───────────────────────── multi-surface bundle + carry-forward ─────────────────────────

    @Test
    @DisplayName("multi-surface: bundle with ≥2 pointers → one semanticDelta per changed surface")
    void multiSurfaceBundle() throws Exception {
        WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("promptVersionId", "p-1");
            bundle.put("behaviorRuleVersionId", "b-1");
            return cand(bundle, List.of("prompt", "behavior_rule"), "cross-surface fix", 1);
        };
        List<Object[]> calls = new ArrayList<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> abBase(70.0, 85.0), null);

        run("evolve-multi", args(1), agent, tools);

        assertThat(count(calls, "GetCandidateDiff")).isEqualTo(2);   // one per changed surface
        assertThat(count(calls, "TriggerAbEval")).isEqualTo(1);
        Map<String, Object> ab = nthInput(calls, "TriggerAbEval", 1);
        assertThat(ab.get("surface")).isEqualTo("agent");
        Map<String, Object> cb = asMap(ab.get("candidateBundle"));
        assertThat(cb.get("promptVersionId")).isEqualTo("p-1");
        assertThat(cb.get("behaviorRuleVersionId")).isEqualTo("b-1");

        Map<String, Object> rec = nthInput(calls, "RecordIteration", 1);
        JsonNode sd = parseSemanticDelta(rec.get("semanticDelta"));
        assertThat(sd.isArray()).isTrue();
        assertThat(sd).hasSize(2);
        assertThat(List.of(sd.get(0).get("surface").asText(), sd.get(1).get("surface").asText()))
                .containsExactlyInAnyOrder("prompt", "behavior_rule");
        assertThat(rec.get("candidateId")).isEqualTo("p-1");
    }

    @Test
    @DisplayName("carry-forward: best is the merged bundle — a later surface keeps the earlier kept face")
    void carryForwardMergedBundle() throws Exception {
        int[] candSeq = {0};
        WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
            candSeq[0]++;
            Map<String, Object> bundle = new LinkedHashMap<>();
            if (candSeq[0] == 1) {
                bundle.put("promptVersionId", "p-1");
                return cand(bundle, List.of("prompt"), "prompt fix", 1);
            }
            bundle.put("behaviorRuleVersionId", "b-2");
            return cand(bundle, List.of("behavior_rule"), "rule fix", 2);
        };
        List<Object[]> calls = new ArrayList<>();
        // iter2's primary pointer is the carried prompt p-1, so abRunId collides with iter1
        // — use a per-A/B counter (not candN) so weightedScore strictly increases each round.
        int[] abCount = {0};
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> { abCount[0]++; return abBase(70.0, 70.0 + 10.0 * abCount[0]); }, null);

        Object result = run("evolve-cf", args(2), agent, tools);
        Scriptable summary = (Scriptable) result;
        assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(2);

        // iter1: candidate = {prompt p-1}; baseline empty.
        Map<String, Object> ab1 = nthInput(calls, "TriggerAbEval", 1);
        assertThat(asMap(ab1.get("candidateBundle")).get("promptVersionId")).isEqualTo("p-1");
        // iter2: baseline = iter1's kept bundle {prompt p-1}; candidate = merged
        // {prompt p-1 (carried), behavior_rule b-2 (new)}.
        Map<String, Object> ab2 = nthInput(calls, "TriggerAbEval", 2);
        Map<String, Object> base2 = asMap(ab2.get("baselineBundle"));
        assertThat(base2.get("promptVersionId")).isEqualTo("p-1");
        assertThat(base2.get("behaviorRuleVersionId")).isNull();
        Map<String, Object> cand2 = asMap(ab2.get("candidateBundle"));
        assertThat(cand2.get("promptVersionId")).isEqualTo("p-1");
        assertThat(cand2.get("behaviorRuleVersionId")).isEqualTo("b-2");
        // iter2 GetCandidateDiff is on behavior_rule with no base (iter1 didn't touch it).
        Map<String, Object> diff2 = nthInput(calls, "GetCandidateDiff", 2);
        assertThat(diff2.get("surface")).isEqualTo("behavior_rule");
        assertThat(diff2.get("baseVersionId")).isNull();
    }

    // ───────────────────────── polling + empty ─────────────────────────

    @Test
    @DisplayName("deterministic polling: a running A/B is re-polled until COMPLETED")
    void deterministicPolling() throws Exception {
        List<Object[]> calls = new ArrayList<>();
        Set<String> abSeen = new HashSet<>();
        WorkflowToolInvoker tools = standardTools(calls,
                List.of(issue("i1", "high", 3, 0.9, "prompt", null)),
                input -> {
                    String abRunId = String.valueOf(input.get("abRunId"));
                    if (abSeen.add(abRunId)) {
                        return Map.of("status", "running");   // first poll
                    }
                    return abBase(70.0, 85.0);                 // second poll terminal
                }, null);

        run("evolve-poll", args(1), candSeqAgent(), tools);
        // 2 polls for the single A/B run (running → COMPLETED).
        assertThat(count(calls, "GetAbResult")).isEqualTo(2);
    }

    @Test
    @DisplayName("empty: no convertible issues → status=empty, no candidate work")
    void emptyWhenNoConvertibleIssues() throws Exception {
        boolean[] agentCalled = {false};
        WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
            agentCalled[0] = true;
            return cand(Map.of("promptVersionId", "x"), List.of("prompt"), "y", 1);
        };
        WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
            if ("GetOptReport".equals(toolName)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("topIssues", List.of(notConvertible("i1")));
                return r;
            }
            return Map.of();
        };
        Map<String, Object> a = args(3);
        Object result = run("evolve-empty", a, agent, tools);
        Scriptable summary = (Scriptable) result;
        assertThat(summary.get("status", summary)).isEqualTo("empty");
        assertThat(agentCalled[0]).isFalse();
    }

    // ───────────────────────── stub builders ─────────────────────────

    /** Candidate-gen leaf that emits a fresh prompt-only bundle "cand-N" each call. */
    private static WorkflowAgentInvoker candSeqAgent() {
        int[] seq = {0};
        return (prompt, opts, stepIndex) -> {
            seq[0]++;
            return cand(Map.of("promptVersionId", "cand-" + seq[0]), List.of("prompt"),
                    "change-" + seq[0], seq[0]);
        };
    }

    /**
     * A standard tool invoker: GetOptReport returns {@code topIssues}; ListActive-
     * HarvestedScenarios returns {@code harvested} (or empty when null); GetAbResult
     * delegates to {@code abFn}; the mechanical tools echo canned results. All calls are
     * appended to {@code calls}.
     */
    private WorkflowToolInvoker standardTools(List<Object[]> calls, List<Map<String, Object>> topIssues,
                                              Function<Map<String, Object>, Object> abFn,
                                              Map<String, Object> harvested) {
        return (toolName, input, stepIndex) -> {
            calls.add(new Object[]{toolName, input});
            switch (toolName) {
                case "GetOptReport": {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("reportId", input.get("reportId"));
                    r.put("agentId", 7);
                    r.put("topIssues", topIssues);
                    return r;
                }
                case "ListActiveHarvestedScenarios": {
                    if (harvested != null) return harvested;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("scenarioIds", List.of());
                    r.put("count", 0);
                    return r;
                }
                case "GetCandidateDiff":
                    return diffEcho(input);
                case "TriggerAbEval":
                    return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                case "GetAbResult":
                    return abFn.apply(input);
                case "ReconcilePrediction":
                    return reconcile();
                case "RecordIteration":
                    return Map.of("stepId", "step-" + input.get("iteration"));
                default:
                    return Map.of();
            }
        };
    }

    /** Terminal GetAbResult with the hill-climb fields (weightedScore = candidate side). */
    private static Map<String, Object> abBase(double baselineWeighted, double candidateWeighted) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "COMPLETED");
        r.put("baselineScore", baselineWeighted);
        r.put("candidateScore", candidateWeighted);
        r.put("delta", candidateWeighted - baselineWeighted);
        r.put("weightedScore", candidateWeighted);
        r.put("baselineWeightedScore", baselineWeighted);
        r.put("candidateGeneralRate", candidateWeighted);
        r.put("baselineGeneralRate", baselineWeighted);
        r.put("candidateTargetRate", null);
        r.put("baselineTargetRate", null);
        r.put("measuredN", 20);
        r.put("wouldPromote", true);
        r.put("thresholds", hillThresholds(3, null));
        return r;
    }

    private static Map<String, Object> hillThresholds(int streakLimit, Double targetWeightedScore) {
        Map<String, Object> th = new LinkedHashMap<>();
        th.put("minMeasuredN", 10);
        th.put("anchorErosionFloorPp", 5.0);
        th.put("weightGeneral", 0.6);
        th.put("weightHarvest", 0.4);
        th.put("minImprovePp", 0.0);
        th.put("noImproveStreakLimit", streakLimit);
        th.put("targetWeightedScore", targetWeightedScore);   // null = no target-stop
        return th;
    }

    /** Candidate sequence number parsed from the A/B run id ("ab-cand-N"). */
    private static int candN(Map<String, Object> abResultInput) {
        return trailingInt(String.valueOf(abResultInput.get("abRunId")));
    }

    private static int trailingInt(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** A candidate-gen leaf result in the bundle shape. */
    private static Map<String, Object> cand(Map<String, Object> bundle, List<String> surfaces,
                                            String changeDesc, int seq) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("candidateBundle", bundle);
        c.put("surfaces", surfaces);
        c.put("changeDesc", changeDesc);
        Map<String, Object> pred = new LinkedHashMap<>();
        pred.put("targetProblem", "p" + seq);
        pred.put("flipToPass", List.of());
        pred.put("riskToFail", List.of());
        c.put("prediction", pred);
        return c;
    }

    /** GetCandidateDiff stub: echoes the requested surface so semanticDelta carries it. */
    private static Map<String, Object> diffEcho(Map<String, Object> input) {
        String surface = String.valueOf(input.get("surface"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("surface", surface);
        r.put("before", "before-" + surface);
        r.put("after", "after-" + surface + "-" + input.get("candidateId"));
        r.put("diff", "- before-" + surface + "\n+ after-" + surface);
        return r;
    }

    private static Map<String, Object> reconcile() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("issueId", "i1");
        r.put("targetProblem", "tp");
        r.put("hits", List.of("s1"));
        r.put("misses", List.of());
        r.put("riskHits", List.of());
        r.put("surprises", List.of());
        r.put("confidence", 1.0);
        return r;
    }

    // ── helpers ──

    private static Map<String, Object> issue(String id, String sev, int rec, double conf,
                                             String surface, Object fixSurface) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("severity", sev);
        m.put("recurrence", rec);
        m.put("confidence", conf);
        m.put("surface", surface);
        if (fixSurface != null) {
            m.put("fixSurface", fixSurface);
        }
        m.put("convertible", true);
        m.put("title", "t-" + id);
        m.put("rootCause", "rc-" + id);
        m.put("proposedFix", "fix-" + id);
        return m;
    }

    private static Map<String, Object> notConvertible(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("convertible", false);
        m.put("surface", "other");
        return m;
    }

    /** Primary pointer of a candidateBundle (prompt > behavior_rule > skill), for abRunId. */
    @SuppressWarnings("unchecked")
    private static String bundlePrimary(Object bundle) {
        if (!(bundle instanceof Map)) return "?";
        Map<String, Object> b = (Map<String, Object>) bundle;
        Object p = b.get("promptVersionId");
        if (p == null) p = b.get("behaviorRuleVersionId");
        if (p == null) p = b.get("skillDraftId");
        return p == null ? "?" : String.valueOf(p);
    }

    /** Parse a semanticDelta arg (ctx.json → JSON string) into a JsonNode. */
    private JsonNode parseSemanticDelta(Object raw) throws Exception {
        return om.readTree(String.valueOf(raw));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static int count(List<Object[]> calls, String name) {
        int n = 0;
        for (Object[] c : calls) if (name.equals(c[0])) n++;
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nthInput(List<Object[]> calls, String name, int nth) {
        int seen = 0;
        for (Object[] c : calls) {
            if (name.equals(c[0]) && ++seen == nth) {
                return (Map<String, Object>) c[1];
            }
        }
        throw new AssertionError("no " + nth + "-th call to " + name);
    }
}
