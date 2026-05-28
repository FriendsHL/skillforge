package com.skillforge.server.flywheel.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: Spring event fired by
 * {@link com.skillforge.server.tool.flywheel.RecordOrchestrationStepResult} Tool
 * after a worker SubAgent reports its step output back to the framework.
 *
 * <p>Consumed by {@link WorkerCompletionListener} (main path; AFTER_COMMIT
 * fallbackExecution=true REQUIRES_NEW) to release the pending
 * {@code CompletableFuture<StepResult>} held by the parent
 * {@code OrchestratorAgentExecutor.dispatchSubAgents} aggregator.
 *
 * @param stepRunId      {@code FlywheelRunStepEntity#getId()}
 * @param status         terminal status (one of
 *                       {@link com.skillforge.server.flywheel.run.FlywheelRunStepEntity}
 *                       {@code .STATUS_COMPLETED} / {@code .STATUS_ERROR})
 * @param outputJson     opaque per-orchestrator JSON written to
 *                       {@code step_output_json}; may be {@code null} when the
 *                       Tool didn't supply one
 * @param errorReason    populated when {@code status=error}; null otherwise
 * @param triggerSource  "tool" (worker called the Tool) or "fallback"
 *                       (synthesized from SessionLoopFinishedEvent) — kept for
 *                       observability so dashboards can surface fallback rate
 */
public record OrchestrationStepCompletedEvent(
        String stepRunId,
        String status,
        JsonNode outputJson,
        String errorReason,
        String triggerSource
) {
    public static final String TRIGGER_TOOL = "tool";
    public static final String TRIGGER_FALLBACK = "fallback";
    public static final String TRIGGER_TIMEOUT = "timeout";
}
