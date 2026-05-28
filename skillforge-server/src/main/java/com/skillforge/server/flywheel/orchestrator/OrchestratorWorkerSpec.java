package com.skillforge.server.flywheel.orchestrator;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: one worker SubAgent's kickoff spec inside an
 * {@code OrchestratorAgentExecutor.dispatchSubAgents} fan-out.
 *
 * <p>The {@link DispatchOrchestrationStep} Tool builds a list of these from the
 * parent orchestrator agent's LLM tool-use payload; the executor turns each
 * spec into one {@code FlywheelRunStepEntity} row + one async SubAgent loop.
 *
 * @param agentId        target child agent id (resolved by the caller; the
 *                       executor does not re-resolve by name here)
 * @param agentName      human-readable agent name (kept for log / WS payload)
 * @param task           task description sent verbatim as the SubAgent's first
 *                       user message
 * @param inputJson      raw JSON payload persisted to
 *                       {@code t_flywheel_run_step.step_input_json}; free schema
 *                       per orchestrator agent
 * @param timeoutSeconds soft deadline used by the aggregator's
 *                       {@code aggregate.get(timeoutSeconds, SECONDS)}; any
 *                       worker still pending after this gets force-completed
 *                       with {@code status=error}
 */
public record OrchestratorWorkerSpec(
        Long agentId,
        String agentName,
        String task,
        String inputJson,
        int timeoutSeconds
) {
    public OrchestratorWorkerSpec {
        if (agentId == null || agentId <= 0L) {
            throw new IllegalArgumentException("agentId must be a positive long");
        }
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task is required");
        }
        if (inputJson == null || inputJson.isBlank()) {
            inputJson = "{}";
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 300; // 5 min default
        }
    }
}
