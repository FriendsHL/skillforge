package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.workflow.exception.WorkflowPausedException;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mozilla.javascript.Scriptable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * AUTOEVOLVING V1 Sprint 3 — drives the REAL {@code opt-report.workflow.js}
 * through {@link WorkflowEvaluator} with a stub {@link WorkflowAgentInvoker}
 * (no real LLM / no token spend) to assert the DSL port's structural equivalence
 * to the agent-driven report-generator path:
 *
 * <ul>
 *   <li>deterministic fanout: {@code ceil(total/5)} annotate batches (12 → 3);</li>
 *   <li>phase progression Load → Annotate → Aggregate → Approve;</li>
 *   <li>the aggregator's summaryJson (report-generator STEP 6 shape, incl. W1
 *       {@code failureCount}) reaches the humanApprove gate intact —
 *       deterministic fields ({@code totalSessions}/{@code successRate}) preserved
 *       (W2: NOT asserting topIssues[].id literal equality — LLM non-determinism).</li>
 * </ul>
 */
class OptReportWorkflowEquivalenceTest {

    private final L1SandboxFactory sandbox = new L1SandboxFactory();
    private final WorkflowEvaluator evaluator = new WorkflowEvaluator(sandbox);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String workflowBody;

    @BeforeEach
    void loadWorkflowSource() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/workflows/opt-report.workflow.js")) {
            assertThat(in).as("opt-report.workflow.js on classpath").isNotNull();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // The registry strips `export` before registering; mirror that so the
            // `export const meta` line parses inside the evaluator's IIFE wrap.
            workflowBody = JsKeywordStripper.strip(raw, "export");
        }
    }

    private WorkflowContext newCtx(String runId, Map<String, Object> args, FlywheelRunService runService) {
        WorkflowContext ctx = new WorkflowContext(runId, args, new BudgetTracker(0L));
        ctx.setObjectMapper(objectMapper);
        ctx.setFlywheelRunService(runService);
        return ctx;
    }

    /** Loader output: {total, items:[{sessionId}×n]} — report-generator STEP 1 shape. */
    private static Map<String, Object> loaderResult(int total) {
        List<Object> items = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            items.add(Map.of("sessionId", "sess-" + i));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("items", items);
        return m;
    }

    /** Aggregator output: report-generator STEP 6 summaryJson (incl. W1 failureCount). */
    private static Map<String, Object> aggregatorSummary() {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", "issue-1");
        issue.put("title", "loop inefficiency");
        issue.put("severity", "high");
        issue.put("sessionCount", 2);
        issue.put("exampleSessionIds", List.of("sess-1", "sess-2"));
        issue.put("suspectSurface", "behavior_rule");
        issue.put("confidence", 0.8);
        issue.put("suggestion", "add a stop-after-N-failures rule");
        issue.put("actionType", "new");

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalSessions", 12);
        s.put("successCount", 9);
        s.put("failureCount", 3);
        s.put("successRate", 0.75);
        s.put("topIssues", List.of(issue));
        s.put("batchesTotal", 3);
        s.put("batchesSucceeded", 3);
        s.put("contentMd", "# Report\n...");
        return s;
    }

    /** G5 holistic-error-span-analyzer output: preconditionIssues[]. */
    private static Map<String, Object> holisticResult() {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("toolName", "Edit");
        issue.put("errorPattern", "old_string not found in file");
        issue.put("sessionCount", 7);
        issue.put("rootCause", "edit attempted before reading the target file");
        issue.put("evidence", "sess-1 step 3 Edit with no prior Read");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("preconditionIssues", List.of(issue));
        return m;
    }

    @Test
    @DisplayName("12 sessions → 3 annotate batches, holistic preconditionIssues injected into aggregator, summary reaches gate (W1/W2/G5)")
    void runsThroughToApproveGateWithEquivalentSummary() {
        Map<String, Integer> callsBySlug = new ConcurrentHashMap<>();
        Map<String, String> promptBySlug = new ConcurrentHashMap<>();
        WorkflowAgentInvoker stub = (prompt, opts, stepIndex) -> {
            String slug = String.valueOf(opts.get("agentSlug"));
            callsBySlug.merge(slug, 1, Integer::sum);
            promptBySlug.put(slug, prompt);
            return switch (slug) {
                case "opt-report-orchestrator" -> loaderResult(12);
                case "session-batch-annotator" -> "annotated batch ok";
                case "holistic-error-span-analyzer" -> holisticResult();
                case "opt-report-aggregator" -> aggregatorSummary();
                default -> null;
            };
        };

        FlywheelRunService runService = mock(FlywheelRunService.class);
        Map<String, Object> args = Map.of("agentId", 42L, "windowDays", 7);
        WorkflowContext ctx = newCtx("run-eq-1", args, runService);

        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            // humanApprove parks the run → WorkflowPausedException unwinds the JS thread.
            assertThatThrownBy(() -> evaluator.evaluate(workflowBody, ctx, stub, exec))
                    .isInstanceOf(WorkflowPausedException.class);
        } finally {
            exec.shutdownNow();
        }

        // Deterministic fanout: ceil(12/5) = 3 annotate batches.
        assertThat(callsBySlug.get("opt-report-orchestrator")).isEqualTo(1);
        assertThat(callsBySlug.get("session-batch-annotator")).isEqualTo(3);
        // G5: holistic phase runs once between Annotate and Aggregate.
        assertThat(callsBySlug.get("holistic-error-span-analyzer")).isEqualTo(1);
        assertThat(callsBySlug.get("opt-report-aggregator")).isEqualTo(1);

        // G5: the aggregator's user message carries the injected preconditionIssues
        // (path a — JS stringify into the prompt), so the aggregator can surface them
        // as their own fine-grained issue instead of folding into a coarse bucket.
        String aggregatorPrompt = promptBySlug.get("opt-report-aggregator");
        assertThat(aggregatorPrompt).contains("HOLISTIC");
        assertThat(aggregatorPrompt).contains("edit attempted before reading the target file");

        // The gate step carries the aggregator summary as its payload.
        ArgumentCaptor<String> stepInput = ArgumentCaptor.forClass(String.class);
        verify(runService).appendStep(eq("run-eq-1"), stepInput.capture(), any(), any());
        verify(runService).pauseRun(eq("run-eq-1"), any());

        JsonNode payload;
        try {
            payload = objectMapper.readTree(stepInput.getValue()).path("payload");
        } catch (Exception e) {
            throw new AssertionError("gate step_input_json not parseable: " + stepInput.getValue(), e);
        }
        // W1: failureCount present. W2: deterministic fields match; do NOT compare topIssues[].id.
        assertThat(payload.path("totalSessions").asInt()).isEqualTo(12);
        assertThat(payload.path("successCount").asInt()).isEqualTo(9);
        assertThat(payload.path("failureCount").asInt()).isEqualTo(3);
        assertThat(payload.path("successRate").asDouble()).isEqualTo(0.75);
        assertThat(payload.path("topIssues")).hasSize(1);
        assertThat(payload.path("topIssues").get(0).path("failureCount").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("empty load (total=0) short-circuits to status=empty without annotating or pausing")
    void emptyLoadShortCircuits() {
        AtomicInteger annotatorCalls = new AtomicInteger();
        WorkflowAgentInvoker stub = (prompt, opts, stepIndex) -> {
            String slug = String.valueOf(opts.get("agentSlug"));
            if ("session-batch-annotator".equals(slug)) {
                annotatorCalls.incrementAndGet();
            }
            if ("opt-report-orchestrator".equals(slug)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("total", 0);
                m.put("items", List.of());
                return m;
            }
            return null;
        };

        FlywheelRunService runService = mock(FlywheelRunService.class);
        WorkflowContext ctx = newCtx("run-eq-empty", Map.of("agentId", 42L, "windowDays", 7), runService);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        Object result;
        try {
            result = evaluator.evaluate(workflowBody, ctx, stub, exec);
        } finally {
            exec.shutdownNow();
        }

        Scriptable obj = (Scriptable) result;
        assertThat(obj.get("status", obj)).isEqualTo("empty");
        assertThat(annotatorCalls.get()).isZero();
    }
}
