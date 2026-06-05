package com.skillforge.workflow;

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
 * AUTOEVOLVE-CLOSE-LOOP P1 — drives the real {@code evolve-loop.workflow.js} through
 * the {@link WorkflowEvaluator} with STUB agent/tool invokers (no LLM, no DB). This
 * is the deterministic-orchestration integration test (design §6 structure): it
 * proves the JS owns the loop — issue ranking, the candidate→diff→A/B→poll→record
 * sequence, deterministic GetAbResult polling, the wouldPromote keep decision, and
 * winner carry-forward — without any non-determinism.
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
    @DisplayName("evolve-loop: deterministic candidate→diff→A/B→poll→record per issue, with carry-forward")
    void deterministicLoop() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            // ── stub candidate-gen leaf: a fresh candidate id per call ──
            int[] candSeq = {0};
            WorkflowAgentInvoker agent = (prompt, opts, stepIndex) -> {
                candSeq[0]++;
                Map<String, Object> cand = new LinkedHashMap<>();
                cand.put("candidateId", "cand-" + candSeq[0]);
                cand.put("surface", "prompt");
                cand.put("changeDesc", "change-" + candSeq[0]);
                Map<String, Object> pred = new LinkedHashMap<>();
                pred.put("targetProblem", "p" + candSeq[0]);
                pred.put("flipToPass", List.of());
                pred.put("riskToFail", List.of());
                cand.put("prediction", pred);
                return cand;
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
                        r.put("topIssues", List.of(
                                issue("i1", "high", 3, 0.9, "prompt"),
                                issue("i2", "low", 1, 0.5, "prompt")));
                        return r;
                    }
                    case "GetCandidateDiff": {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("surface", "prompt");
                        r.put("before", "old prompt");
                        r.put("after", "new prompt for " + input.get("candidateId"));
                        r.put("diff", "- old prompt\n+ new prompt");
                        return r;
                    }
                    case "TriggerAbEval": {
                        // agent-surface A/B over a single-pointer prompt bundle.
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cb = (Map<String, Object>) input.get("candidateBundle");
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("abRunId", "ab-" + (cb == null ? "?" : cb.get("promptVersionId")));
                        return r;
                    }
                    case "GetAbResult": {
                        String abRunId = String.valueOf(input.get("abRunId"));
                        if (abSeen.add(abRunId)) {
                            // First poll for this run → still running (forces a re-poll).
                            return Map.of("status", "running");
                        }
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("status", "COMPLETED");
                        r.put("baselineScore", 70.0);
                        r.put("candidateScore", 90.0);
                        r.put("delta", 20.0);
                        r.put("wouldPromote", true);   // → kept
                        return r;
                    }
                    case "ReconcilePrediction": {
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
                    case "RecordIteration": {
                        return Map.of("stepId", "step-" + input.get("iteration"));
                    }
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

            // ── per-iteration mechanical sequence (×2 iterations) ──
            assertThat(count(calls, "GetCandidateDiff")).isEqualTo(2);
            assertThat(count(calls, "TriggerAbEval")).isEqualTo(2);
            assertThat(count(calls, "RecordIteration")).isEqualTo(2);
            // G3: ReconcilePrediction runs once per iteration (regression guard — was
            // missing in the first P1 cut, dropping reconciliation vs the orchestrator).
            assertThat(count(calls, "ReconcilePrediction")).isEqualTo(2);
            // Deterministic polling: each A/B run is polled twice (running → COMPLETED).
            assertThat(count(calls, "GetAbResult")).isEqualTo(4);

            // ── A/B is agent-surface; reconcile gets the same abRunId ──
            Map<String, Object> ab1 = nthInput(calls, "TriggerAbEval", 1);
            assertThat(ab1.get("surface")).isEqualTo("agent");
            @SuppressWarnings("unchecked")
            Map<String, Object> cb1 = (Map<String, Object>) ab1.get("candidateBundle");
            assertThat(cb1.get("promptVersionId")).isEqualTo("cand-1");
            Map<String, Object> recon1Call = nthInput(calls, "ReconcilePrediction", 1);
            assertThat(recon1Call.get("abRunId")).isEqualTo("ab-cand-1");

            // ── winner carry-forward: iter-2's A/B baselines on iter-1's kept candidate bundle ──
            Map<String, Object> ab2 = nthInput(calls, "TriggerAbEval", 2);
            @SuppressWarnings("unchecked")
            Map<String, Object> bb2 = (Map<String, Object>) ab2.get("baselineBundle");
            assertThat(bb2.get("promptVersionId")).isEqualTo("cand-1");

            // ── RecordIteration carries semanticDelta + reconciliation + kept + bundle ──
            Map<String, Object> rec1 = nthInput(calls, "RecordIteration", 1);
            assertThat(rec1.get("kept")).isEqualTo(Boolean.TRUE);
            assertThat(((Number) rec1.get("delta")).doubleValue()).isEqualTo(20.0);
            @SuppressWarnings("unchecked")
            Map<String, Object> sd = (Map<String, Object>) rec1.get("semanticDelta");
            assertThat(sd).containsKeys("surface", "before", "after", "diff", "changeDesc");
            assertThat(String.valueOf(sd.get("before"))).isEqualTo("old prompt");
            // G3 reconciliation block must be present + structured (the regression this fix closes).
            @SuppressWarnings("unchecked")
            Map<String, Object> recon = (Map<String, Object>) rec1.get("reconciliation");
            assertThat(recon).isNotNull().containsKeys("hits", "misses", "confidence");
            assertThat(((Number) recon.get("confidence")).doubleValue()).isEqualTo(1.0);
            @SuppressWarnings("unchecked")
            Map<String, Object> bundle = (Map<String, Object>) rec1.get("candidateBundle");
            assertThat(bundle.get("promptVersionId")).isEqualTo("cand-1");
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
                return Map.of("candidateId", "x", "surface", "prompt", "changeDesc", "y");
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

    // ── helpers ──

    private static Map<String, Object> issue(String id, String sev, int rec, double conf, String surface) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("severity", sev);
        m.put("recurrence", rec);
        m.put("confidence", conf);
        m.put("surface", surface);
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
