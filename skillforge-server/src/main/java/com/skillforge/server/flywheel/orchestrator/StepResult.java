package com.skillforge.server.flywheel.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: per-step result returned by
 * {@code OrchestratorAgentExecutor.dispatchSubAgents}'s aggregate future.
 *
 * <p>Source of truth ({@code Design 4}):
 * <ul>
 *   <li><b>main path</b> — worker SubAgent calls
 *       {@link com.skillforge.server.tool.flywheel.RecordOrchestrationStepResult}
 *       Tool, which writes step_output_json + publishes
 *       {@code OrchestrationStepCompletedEvent}; listener fills this record.</li>
 *   <li><b>fallback</b> — worker session terminal without Tool call →
 *       {@code SessionLoopFinishedEvent} fallback listener synthesizes a
 *       {@code StepResult} from {@code finalMessage}.</li>
 *   <li><b>timeout</b> — aggregate.get(timeout) elapsed → executor force-
 *       completes pending steps with {@code status=error,
 *       errorReason="timeout"}, {@code outputJson=null}.</li>
 * </ul>
 */
public record StepResult(
        String stepRunId,
        String status,
        JsonNode outputJson,
        String errorReason
) {
    public boolean succeeded() {
        return "completed".equals(status);
    }
}
