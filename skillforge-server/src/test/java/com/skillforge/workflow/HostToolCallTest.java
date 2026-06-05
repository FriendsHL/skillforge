package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.workflow.journal.JournalCache;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Scriptable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — {@code tool()} host-binding edge cases: journal-replay
 * short-circuit (a tool() before the resume frontier returns its cached result and
 * does NOT re-invoke the side-effecting tool) and the parallel()-rejection guard.
 */
class HostToolCallTest {

    private final L1SandboxFactory sandbox = new L1SandboxFactory();
    private final WorkflowEvaluator evaluator = new WorkflowEvaluator(sandbox);
    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("resume: tool() before the frontier returns the cached result, never re-invokes")
    void resumeShortCircuitsToolCall() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            boolean[] invoked = {false};
            WorkflowToolInvoker invoker = (toolName, input, stepIndex) -> {
                invoked[0] = true;
                throw new AssertionError("tool() must NOT re-invoke on resume short-circuit");
            };

            WorkflowContext ctx = new WorkflowContext("run-resume", Map.of(), new BudgetTracker(0L));
            ctx.setObjectMapper(om);
            ctx.setResuming(true);
            ctx.setResumeFrontierIndex(5);   // stepIndex 0 < 5 → short-circuit
            ctx.setReplayComplete(false);
            ctx.setJournalCache(new FakeJournal(om.createObjectNode().put("cached", true)));

            Object result = evaluator.evaluate(
                    "return tool('TriggerAbEval', { x: 1 });", ctx, (p, o, s) -> null, invoker, exec);

            Scriptable obj = (Scriptable) result;
            assertThat(obj.get("cached", obj)).isEqualTo(Boolean.TRUE);
            assertThat(invoked[0]).isFalse();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("tool() inside parallel() is rejected (Phase 1)")
    void toolRejectedInParallel() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WorkflowContext ctx = new WorkflowContext("run-par", Map.of(), new BudgetTracker(0L));
            ctx.setObjectMapper(om);
            WorkflowToolInvoker invoker = (toolName, input, stepIndex) -> Map.of("ok", true);

            assertThatThrownBy(() -> evaluator.evaluate(
                    "return parallel([ function(){ return tool('GetAbResult', {}); } ]);",
                    ctx, (p, o, s) -> null, invoker, exec))
                    .hasMessageContaining("tool() is not supported inside parallel");
        } finally {
            exec.shutdownNow();
        }
    }

    private static final class FakeJournal implements JournalCache {
        private final JsonNode toolResult;

        FakeJournal(JsonNode toolResult) {
            this.toolResult = toolResult;
        }

        @Override
        public Optional<String> getCachedAgentFinalResponse(String runId, int stepIndex) {
            return Optional.empty();
        }

        @Override
        public Optional<JsonNode> getApproveDecision(String runId, int stepIndex) {
            return Optional.empty();
        }

        @Override
        public Optional<JsonNode> getCachedToolResult(String runId, int stepIndex) {
            return Optional.of(toolResult);
        }
    }
}
