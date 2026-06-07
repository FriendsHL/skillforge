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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 + Phase 2a — drives the real {@code evolve-loop.workflow.js}
 * through the {@link WorkflowEvaluator} with STUB agent/tool invokers (no LLM, no DB).
 * This is the deterministic-orchestration integration test: it proves the JS owns the
 * loop — issue ranking, the candidate→multi-surface-diff→A/B→poll→record sequence,
 * deterministic GetAbResult polling, the wouldPromote keep decision, and per-surface
 * winner carry-forward (the whole bundle) — without any non-determinism.
 *
 * <p>Phase 2a coverage: the candidate leaf now returns a cross-surface
 * {@code candidateBundle}; the JS composes a whole-agent A/B over the bundle,
 * computes one semantic delta per changed surface, and carries forward the merged
 * best bundle.
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

    @Test
    @DisplayName("evolve-loop: single-surface (prompt) loop degrades cleanly, carries the prompt pointer forward")
    void deterministicLoop() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            // ── stub candidate-gen leaf: a fresh prompt-only bundle per call ──
            int[] candSeq = {0};
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
                candSeq[0]++;
                Map<String, Object> bundle = new LinkedHashMap<>();
                bundle.put("promptVersionId", "cand-" + candSeq[0]);
                return cand(bundle, List.of("prompt"), "change-" + candSeq[0], candSeq[0]);
            };

            // ── stub tool() invoker: canned mechanical results, recording all calls ──
            List<Object[]> calls = new ArrayList<>();          // (toolName, input)
            Set<String> abSeen = new HashSet<>();              // for poll-then-complete
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("reportId", input.get("reportId"));
                        r.put("agentId", 7);
                        r.put("status", "completed");
                        // No fixSurface → allowedSurfaces degrades to ['prompt'] (Phase 1 equiv).
                        r.put("topIssues", List.of(
                                issue("i1", "high", 3, 0.9, "prompt", null),
                                issue("i2", "low", 1, 0.5, "prompt", null)));
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                    case "GetAbResult":
                        return abResult(input, abSeen);
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "step-" + input.get("iteration"));
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 2);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);

            WorkflowContext ctx = new WorkflowContext("evolve-run-1", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            Object result = evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            // ── summary ──
            Scriptable summary = (Scriptable) result;
            assertThat(summary.get("status", summary)).isEqualTo("completed");
            assertThat(((Number) summary.get("evaluated", summary)).intValue()).isEqualTo(2);
            assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(2);

            // ── reportId-present path skips the opt-report sub-flow ──
            assertThat(toolNames(calls)).doesNotContain("RunOptReportSubflow");
            assertThat(toolNames(calls)).contains("GetOptReport");

            // ── per-iteration mechanical sequence (×2 iterations, single surface each) ──
            assertThat(count(calls, "GetCandidateDiff")).isEqualTo(2);   // 1 changed surface × 2 iters
            assertThat(count(calls, "TriggerAbEval")).isEqualTo(2);
            assertThat(count(calls, "RecordIteration")).isEqualTo(2);
            assertThat(count(calls, "ReconcilePrediction")).isEqualTo(2);
            // Deterministic polling: each A/B run is polled twice (running → COMPLETED).
            assertThat(count(calls, "GetAbResult")).isEqualTo(4);

            // ── A/B is agent-surface; reconcile gets the same abRunId ──
            Map<String, Object> ab1 = nthInput(calls, "TriggerAbEval", 1);
            assertThat(ab1.get("surface")).isEqualTo("agent");
            assertThat(asMap(ab1.get("candidateBundle")).get("promptVersionId")).isEqualTo("cand-1");
            assertThat(nthInput(calls, "ReconcilePrediction", 1).get("abRunId")).isEqualTo("ab-cand-1");

            // ── winner carry-forward: iter-2's A/B baselines on iter-1's kept bundle ──
            Map<String, Object> ab2 = nthInput(calls, "TriggerAbEval", 2);
            assertThat(asMap(ab2.get("baselineBundle")).get("promptVersionId")).isEqualTo("cand-1");
            // candidate = merged best bundle with the changed prompt face overridden.
            assertThat(asMap(ab2.get("candidateBundle")).get("promptVersionId")).isEqualTo("cand-2");

            // ── RecordIteration carries semanticDelta(array) + reconciliation + kept + bundle ──
            Map<String, Object> rec1 = nthInput(calls, "RecordIteration", 1);
            assertThat(rec1.get("surface")).isEqualTo("agent");
            assertThat(rec1.get("kept")).isEqualTo(Boolean.TRUE);
            assertThat(((Number) rec1.get("delta")).doubleValue()).isEqualTo(20.0);
            assertThat(rec1.get("candidateId")).isEqualTo("cand-1");   // primary pointer (prompt)
            // semanticDelta is passed via ctx.json → a JSON STRING holding an array.
            JsonNode sd = parseSemanticDelta(rec1.get("semanticDelta"));
            assertThat(sd.isArray()).isTrue();
            assertThat(sd).hasSize(1);
            assertThat(sd.get(0).get("surface").asText()).isEqualTo("prompt");
            assertThat(sd.get(0).get("before").asText()).isEqualTo("before-prompt");
            // G3 reconciliation block present + structured.
            Map<String, Object> recon = asMap(rec1.get("reconciliation"));
            assertThat(recon).isNotNull().containsKeys("hits", "misses", "confidence");
            assertThat(((Number) recon.get("confidence")).doubleValue()).isEqualTo(1.0);
            assertThat(asMap(rec1.get("candidateBundle")).get("promptVersionId")).isEqualTo("cand-1");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("evolve-loop: multi-surface fixSurface → bundle with ≥2 pointers, one semanticDelta per changed surface")
    void multiSurfaceBundle() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
                Map<String, Object> bundle = new LinkedHashMap<>();
                bundle.put("promptVersionId", "p-1");
                bundle.put("behaviorRuleVersionId", "b-1");
                return cand(bundle, List.of("prompt", "behavior_rule"), "cross-surface fix", 1);
            };

            List<Object[]> calls = new ArrayList<>();
            Set<String> abSeen = new HashSet<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("topIssues", List.of(
                                issue("i1", "high", 3, 0.9, "prompt",
                                        List.of("prompt", "behavior_rule"))));
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                    case "GetAbResult":
                        return abResult(input, abSeen);
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "step-" + input.get("iteration"));
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 1);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-multi", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            // one GetCandidateDiff per changed surface (2) in a single iteration
            assertThat(count(calls, "GetCandidateDiff")).isEqualTo(2);
            assertThat(count(calls, "TriggerAbEval")).isEqualTo(1);

            // A/B candidateBundle has BOTH pointers; baseline is empty (iter 1, active).
            Map<String, Object> ab = nthInput(calls, "TriggerAbEval", 1);
            assertThat(ab.get("surface")).isEqualTo("agent");
            Map<String, Object> cb = asMap(ab.get("candidateBundle"));
            assertThat(cb.get("promptVersionId")).isEqualTo("p-1");
            assertThat(cb.get("behaviorRuleVersionId")).isEqualTo("b-1");

            // semanticDelta is an array with one entry per changed surface.
            Map<String, Object> rec = nthInput(calls, "RecordIteration", 1);
            JsonNode sd = parseSemanticDelta(rec.get("semanticDelta"));
            assertThat(sd.isArray()).isTrue();
            assertThat(sd).hasSize(2);
            assertThat(List.of(sd.get(0).get("surface").asText(), sd.get(1).get("surface").asText()))
                    .containsExactlyInAnyOrder("prompt", "behavior_rule");
            // RecordIteration bundle sidecar holds the full tuple; candidateId = prompt primary.
            Map<String, Object> recBundle = asMap(rec.get("candidateBundle"));
            assertThat(recBundle.get("promptVersionId")).isEqualTo("p-1");
            assertThat(recBundle.get("behaviorRuleVersionId")).isEqualTo("b-1");
            assertThat(rec.get("candidateId")).isEqualTo("p-1");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("evolve-loop: single non-prompt fixSurface degrades to a single pointer (no regression)")
    void singleSurfaceFixSurfaceDegrades() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
                Map<String, Object> bundle = new LinkedHashMap<>();
                bundle.put("behaviorRuleVersionId", "br-1");
                // Leaf also (wrongly) tries to change prompt — JS must drop it (越界).
                bundle.put("promptVersionId", "should-be-dropped");
                return cand(bundle, List.of("behavior_rule"), "rule fix", 1);
            };

            List<Object[]> calls = new ArrayList<>();
            Set<String> abSeen = new HashSet<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        // fixSurface is a single (non-prompt) value → whitelist ['behavior_rule'].
                        r.put("topIssues", List.of(
                                issue("i1", "high", 3, 0.9, "behavior_rule", "behavior_rule")));
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                    case "GetAbResult":
                        return abResult(input, abSeen);
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "step-" + input.get("iteration"));
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 1);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-single-br", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            // Only the in-whitelist surface survives → 1 diff, 1 A/B.
            assertThat(count(calls, "GetCandidateDiff")).isEqualTo(1);
            Map<String, Object> diffCall = nthInput(calls, "GetCandidateDiff", 1);
            assertThat(diffCall.get("surface")).isEqualTo("behavior_rule");
            assertThat(diffCall.get("candidateId")).isEqualTo("br-1");

            Map<String, Object> ab = nthInput(calls, "TriggerAbEval", 1);
            Map<String, Object> cb = asMap(ab.get("candidateBundle"));
            assertThat(cb.get("behaviorRuleVersionId")).isEqualTo("br-1");
            // Out-of-whitelist prompt pointer was dropped.
            assertThat(cb.get("promptVersionId")).isNull();

            Map<String, Object> rec = nthInput(calls, "RecordIteration", 1);
            assertThat(rec.get("surface")).isEqualTo("agent");
            // candidateId primary falls back to behavior_rule when prompt is absent.
            assertThat(rec.get("candidateId")).isEqualTo("br-1");
            JsonNode sd = parseSemanticDelta(rec.get("semanticDelta"));
            assertThat(sd).hasSize(1);
            assertThat(sd.get(0).get("surface").asText()).isEqualTo("behavior_rule");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("evolve-loop: best is the merged bundle — a later surface keeps the earlier kept face")
    void carryForwardMergedBundle() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            // iter1 changes prompt; iter2 changes behavior_rule. After both keep, iter2's
            // candidate bundle must still carry iter1's prompt pointer (merged best).
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
            Set<String> abSeen = new HashSet<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("topIssues", List.of(
                                // i1 (prompt) ranks above i2 (behavior_rule) via confidence.
                                issue("i1", "high", 3, 0.9, "prompt", "prompt"),
                                issue("i2", "high", 3, 0.8, "behavior_rule", "behavior_rule")));
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                    case "GetAbResult":
                        return abResult(input, abSeen);
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "step-" + input.get("iteration"));
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 2);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-cf", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            Object result = evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);
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
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("evolve-loop: no convertible issues → empty summary, no candidate work")
    void emptyWhenNoConvertibleIssues() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
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
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-empty", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            Object result = evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);
            Scriptable summary = (Scriptable) result;
            assertThat(summary.get("status", summary)).isEqualTo("empty");
            assertThat(agentCalled[0]).isFalse();
        } finally {
            exec.shutdownNow();
        }
    }

    // ── workflow-fix 2026-06-07 (F1/F3/F4/F6) ──

    @Test
    @DisplayName("F1: active harvested scenarios → every TriggerAbEval carries evalScenarioIds (fetched once)")
    void targetingPassesEvalScenarioIds() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            int[] candSeq = {0};
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
                candSeq[0]++;
                Map<String, Object> bundle = new LinkedHashMap<>();
                bundle.put("promptVersionId", "cand-" + candSeq[0]);
                return cand(bundle, List.of("prompt"), "change-" + candSeq[0], candSeq[0]);
            };

            List<Object[]> calls = new ArrayList<>();
            Set<String> abSeen = new HashSet<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("topIssues", List.of(
                                issue("i1", "high", 3, 0.9, "prompt", null),
                                issue("i2", "low", 1, 0.5, "prompt", null)));
                        return r;
                    }
                    case "ListActiveHarvestedScenarios": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("agentId", "7");
                        r.put("scenarioIds", List.of("bad-165e0ed0"));
                        r.put("count", 1);
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                    case "GetAbResult":
                        return abResult(input, abSeen);
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "step-" + input.get("iteration"));
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 2);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-f1", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            // fetched ONCE (after Report), not per iteration
            assertThat(count(calls, "ListActiveHarvestedScenarios")).isEqualTo(1);
            // every TriggerAbEval carries the harvested target ids
            assertThat(count(calls, "TriggerAbEval")).isEqualTo(2);
            for (int n = 1; n <= 2; n++) {
                Map<String, Object> ab = nthInput(calls, "TriggerAbEval", n);
                @SuppressWarnings("unchecked")
                List<Object> ids = (List<Object>) ab.get("evalScenarioIds");
                assertThat(ids).containsExactly("bad-165e0ed0");
            }
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("F1: no active harvested scenarios (empty list) → TriggerAbEval has NO evalScenarioIds")
    void emptyHarvestedKeepsRoleSplit() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) ->
                    cand(Map.of("promptVersionId", "cand-1"), List.of("prompt"), "c", 1);

            List<Object[]> calls = new ArrayList<>();
            Set<String> abSeen = new HashSet<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("topIssues", List.of(issue("i1", "high", 3, 0.9, "prompt", null)));
                        return r;
                    }
                    case "ListActiveHarvestedScenarios": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("scenarioIds", List.of());   // none active → keep role split
                        r.put("count", 0);
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                    case "GetAbResult":
                        return abResult(input, abSeen);
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "s");
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 1);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-f1-empty", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            Map<String, Object> ab = nthInput(calls, "TriggerAbEval", 1);
            assertThat(ab.get("evalScenarioIds")).isNull();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("F3: wouldPromote=true but measuredN < minMeasuredN (thresholds echo) → inconclusive, not kept")
    void minMeasuredNGuardBlocksKeep() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) ->
                    cand(Map.of("promptVersionId", "cand-1"), List.of("prompt"), "c", 1);

            List<Object[]> calls = new ArrayList<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("topIssues", List.of(issue("i1", "high", 3, 0.9, "prompt", null)));
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-1");
                    case "GetAbResult": {
                        // wouldPromote true BUT only 5 of 36 measured (n≈7 noise case).
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("status", "COMPLETED");
                        r.put("baselineScore", 70.0);
                        r.put("candidateScore", 90.0);
                        r.put("delta", 20.0);
                        r.put("wouldPromote", true);
                        r.put("measuredN", 5);
                        r.put("totalN", 36);
                        r.put("thresholds", Map.of("minMeasuredN", 10, "anchorErosionFloorPp", 5.0));
                        return r;
                    }
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "s");
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 1);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-f3", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            Object result = evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            Scriptable summary = (Scriptable) result;
            assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(0);
            Map<String, Object> rec = nthInput(calls, "RecordIteration", 1);
            assertThat(rec.get("kept")).isEqualTo(Boolean.FALSE);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("F3 boundary: measuredN == minMeasuredN (exactly at threshold) → conclusive, keep allowed (guard is <)")
    void minMeasuredNGuardInclusiveBoundary() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) ->
                    cand(Map.of("promptVersionId", "cand-1"), List.of("prompt"), "c", 1);

            List<Object[]> calls = new ArrayList<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("topIssues", List.of(issue("i1", "high", 3, 0.9, "prompt", null)));
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-1");
                    case "GetAbResult": {
                        // measuredN EXACTLY equals minMeasuredN — the guard is strictly
                        // `<`, so the boundary is inclusive: still conclusive → keep.
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("status", "COMPLETED");
                        r.put("baselineScore", 70.0);
                        r.put("candidateScore", 90.0);
                        r.put("delta", 20.0);
                        r.put("wouldPromote", true);
                        r.put("measuredN", 10);
                        r.put("totalN", 36);
                        r.put("thresholds", Map.of("minMeasuredN", 10, "anchorErosionFloorPp", 5.0));
                        return r;
                    }
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "s");
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 1);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-f3-boundary", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            Object result = evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            Scriptable summary = (Scriptable) result;
            assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(1);
            assertThat(nthInput(calls, "RecordIteration", 1).get("kept")).isEqualTo(Boolean.TRUE);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("F6 boundary: candidateGeneralRate == originalGeneral − floor (exactly on floor) → keep allowed (anchor is <)")
    void anchorErosionInclusiveBoundary() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            int[] candSeq = {0};
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
                candSeq[0]++;
                Map<String, Object> bundle = new LinkedHashMap<>();
                bundle.put("promptVersionId", "cand-" + candSeq[0]);
                return cand(bundle, List.of("prompt"), "change-" + candSeq[0], candSeq[0]);
            };

            List<Object[]> calls = new ArrayList<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("topIssues", List.of(
                                issue("i1", "high", 3, 0.9, "prompt", null),
                                issue("i2", "high", 3, 0.8, "prompt", null)));
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                    case "GetAbResult": {
                        String abRunId = String.valueOf(input.get("abRunId"));
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("status", "COMPLETED");
                        r.put("wouldPromote", true);
                        r.put("measuredN", 20);
                        r.put("thresholds", Map.of("minMeasuredN", 10, "anchorErosionFloorPp", 5.0));
                        if ("ab-cand-1".equals(abRunId)) {
                            // iter1: anchor = baselineGeneralRate 80.
                            r.put("baselineScore", 70.0);
                            r.put("candidateScore", 90.0);
                            r.put("delta", 20.0);
                            r.put("baselineGeneralRate", 80.0);
                            r.put("candidateGeneralRate", 85.0);
                        } else {
                            // iter2: general sits EXACTLY on the floor (80 − 5 = 75).
                            // The anchor gate is strictly `<`, so 75 is allowed → keep.
                            r.put("baselineScore", 90.0);
                            r.put("candidateScore", 92.0);
                            r.put("delta", 2.0);
                            r.put("baselineGeneralRate", 85.0);
                            r.put("candidateGeneralRate", 75.0);
                        }
                        return r;
                    }
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "s");
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 2);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-f6-boundary", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            Object result = evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            Scriptable summary = (Scriptable) result;
            assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(2);
            assertThat(nthInput(calls, "RecordIteration", 2).get("kept")).isEqualTo(Boolean.TRUE);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("F6: vs-original anchor — iter2 candidateGeneralRate below originalGeneral−floor → not kept")
    void anchorErosionBlocksKeep() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            int[] candSeq = {0};
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
                candSeq[0]++;
                Map<String, Object> bundle = new LinkedHashMap<>();
                bundle.put("promptVersionId", "cand-" + candSeq[0]);
                return cand(bundle, List.of("prompt"), "change-" + candSeq[0], candSeq[0]);
            };

            List<Object[]> calls = new ArrayList<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("topIssues", List.of(
                                issue("i1", "high", 3, 0.9, "prompt", null),
                                issue("i2", "high", 3, 0.8, "prompt", null)));
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                    case "GetAbResult": {
                        String abRunId = String.valueOf(input.get("abRunId"));
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("status", "COMPLETED");
                        r.put("wouldPromote", true);
                        r.put("measuredN", 20);
                        r.put("thresholds", Map.of("minMeasuredN", 10, "anchorErosionFloorPp", 5.0));
                        if ("ab-cand-1".equals(abRunId)) {
                            // iter1: originalGeneral anchor = baselineGeneralRate 80.
                            r.put("baselineScore", 70.0);
                            r.put("candidateScore", 90.0);
                            r.put("delta", 20.0);
                            r.put("baselineGeneralRate", 80.0);
                            r.put("candidateGeneralRate", 85.0);   // above anchor → kept
                        } else {
                            // iter2: vs-best looks fine (wouldPromote true) but general
                            // eroded below original 80 − 5 = 75 → anchor blocks keep.
                            r.put("baselineScore", 90.0);
                            r.put("candidateScore", 92.0);
                            r.put("delta", 2.0);
                            r.put("baselineGeneralRate", 85.0);
                            r.put("candidateGeneralRate", 70.0);
                        }
                        return r;
                    }
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "s");
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 2);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-f6", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            Object result = evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            Scriptable summary = (Scriptable) result;
            assertThat(((Number) summary.get("kept", summary)).intValue()).isEqualTo(1);
            assertThat(nthInput(calls, "RecordIteration", 1).get("kept")).isEqualTo(Boolean.TRUE);
            assertThat(nthInput(calls, "RecordIteration", 2).get("kept")).isEqualTo(Boolean.FALSE);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("F4: win-streak only — cachedBaselineScore+priorWinnerAbRunId after a keep; fresh two-arm after a reject")
    void winStreakBaselineCache() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            int[] candSeq = {0};
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
                candSeq[0]++;
                Map<String, Object> bundle = new LinkedHashMap<>();
                bundle.put("promptVersionId", "cand-" + candSeq[0]);
                return cand(bundle, List.of("prompt"), "change-" + candSeq[0], candSeq[0]);
            };

            List<Object[]> calls = new ArrayList<>();
            WorkflowToolInvoker tools = (toolName, input, stepIndex) -> {
                calls.add(new Object[]{toolName, input});
                switch (toolName) {
                    case "GetOptReport": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("topIssues", List.of(
                                issue("i1", "high", 3, 0.9, "prompt", null),
                                issue("i2", "high", 3, 0.8, "prompt", null),
                                issue("i3", "high", 3, 0.7, "prompt", null)));
                        return r;
                    }
                    case "GetCandidateDiff":
                        return diffEcho(input);
                    case "TriggerAbEval":
                        return Map.of("abRunId", "ab-" + bundlePrimary(input.get("candidateBundle")));
                    case "GetAbResult": {
                        String abRunId = String.valueOf(input.get("abRunId"));
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("status", "COMPLETED");
                        r.put("measuredN", 20);
                        r.put("thresholds", Map.of("minMeasuredN", 10, "anchorErosionFloorPp", 5.0));
                        if ("ab-cand-1".equals(abRunId)) {
                            r.put("baselineScore", 70.0);
                            r.put("candidateScore", 90.0);
                            r.put("delta", 20.0);
                            r.put("wouldPromote", true);    // iter1 kept → win-streak
                        } else {
                            r.put("baselineScore", 90.0);
                            r.put("candidateScore", 80.0);
                            r.put("delta", -10.0);
                            r.put("wouldPromote", false);   // iter2 rejected → iter3 fresh
                        }
                        return r;
                    }
                    case "ReconcilePrediction":
                        return reconcile();
                    case "RecordIteration":
                        return Map.of("stepId", "s");
                    default:
                        return Map.of();
                }
            };

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetAgentId", 7);
            args.put("maxIter", 3);
            args.put("reportId", "rep-1");
            args.put("autoApprove", true);
            WorkflowContext ctx = new WorkflowContext("evolve-f4", args, new BudgetTracker(0L));
            ctx.setObjectMapper(om);

            evaluator.evaluate(evolveLoopBody(), ctx, agent, tools, exec);

            assertThat(count(calls, "TriggerAbEval")).isEqualTo(3);
            // iter1: fresh (nothing kept yet) → no cache fields.
            Map<String, Object> ab1 = nthInput(calls, "TriggerAbEval", 1);
            assertThat(ab1.get("cachedBaselineScore")).isNull();
            assertThat(ab1.get("priorWinnerAbRunId")).isNull();
            // iter2: prev round kept → cache = best score + the abRunId that measured it.
            Map<String, Object> ab2 = nthInput(calls, "TriggerAbEval", 2);
            assertThat(((Number) ab2.get("cachedBaselineScore")).doubleValue()).isEqualTo(90.0);
            assertThat(ab2.get("priorWinnerAbRunId")).isEqualTo("ab-cand-1");
            // iter3: prev round rejected → fresh two-arm run again.
            Map<String, Object> ab3 = nthInput(calls, "TriggerAbEval", 3);
            assertThat(ab3.get("cachedBaselineScore")).isNull();
            assertThat(ab3.get("priorWinnerAbRunId")).isNull();
        } finally {
            exec.shutdownNow();
        }
    }

    // ── stub builders ──

    /** A candidate-gen leaf result in the Phase 2a shape. */
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

    /** GetAbResult stub: first poll running, second poll COMPLETED + wouldPromote. */
    private static Object abResult(Map<String, Object> input, Set<String> abSeen) {
        String abRunId = String.valueOf(input.get("abRunId"));
        if (abSeen.add(abRunId)) {
            return Map.of("status", "running");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "COMPLETED");
        r.put("baselineScore", 70.0);
        r.put("candidateScore", 90.0);
        r.put("delta", 20.0);
        r.put("wouldPromote", true);
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

    private static List<String> toolNames(List<Object[]> calls) {
        List<String> names = new ArrayList<>();
        for (Object[] c : calls) names.add((String) c[0]);
        return names;
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
