package com.skillforge.workflow;

import java.util.Map;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — seam between the Rhino {@code tool()} host binding
 * ({@code HostToolCall}) and the actual synchronous Java tool execution.
 *
 * <p>Unlike {@link WorkflowAgentInvoker} (which spawns an LLM sub-agent),
 * {@code tool()} invokes a whitelisted Java {@code Tool} <strong>directly on the
 * workflow thread</strong> — no LLM, no sub-session. It is the clean way to land
 * the evolve-loop's deterministic "mechanical" nodes (TriggerAbEval / GetAbResult
 * / RecordIteration / ...) as DAG steps without wrapping each in an agent (the
 * design's node-type rule: 🟩 tool node, not 🟨 agent leaf).
 *
 * <p>Like {@link WorkflowAgentInvoker#invoke}, this runs pure Java and never
 * touches the Rhino {@code Context}/scope.
 *
 * @param toolName   the whitelisted tool name (e.g. {@code "TriggerAbEval"})
 * @param input      the tool input map (already deep-converted to plain Java)
 * @param stepIndex  deterministic invoke-order index (shared counter with
 *                   {@code agent()} / {@code humanApprove()})
 * @return the tool's output parsed to a Java value (Map/List for JSON output,
 *         String otherwise) to hand back to the workflow JS
 */
@FunctionalInterface
public interface WorkflowToolInvoker {
    Object invoke(String toolName, Map<String, Object> input, int stepIndex);
}
