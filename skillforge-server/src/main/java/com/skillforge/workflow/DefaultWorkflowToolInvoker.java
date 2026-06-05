package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.workflow.engine.WorkflowEvolveToolRegistryFactory;
import com.skillforge.workflow.exception.WorkflowToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — production {@link WorkflowToolInvoker}: the
 * synchronous, on-the-workflow-thread execution path for the {@code tool()} host
 * binding. Mirrors {@link DefaultWorkflowAgentInvoker} structurally but invokes a
 * whitelisted Java {@code Tool} directly (no LLM sub-agent, no sub-session):
 *
 * <ol>
 *   <li>resolve {@code toolName} from the whitelist registry (unknown → throw);</li>
 *   <li>{@code appendStep} (pending) a {@code tool_call} row carrying
 *       {@code toolName / stepIndex / input};</li>
 *   <li>{@code tool.execute(input, ctx)} synchronously (pure Java — never touches
 *       Rhino, so the offload model stays safe);</li>
 *   <li>{@code transitionStepStatus} → completed (output json {@code {toolName,
 *       result, durationMs}}) or error.</li>
 * </ol>
 *
 * <p>One instance is created per-run by {@link WorkflowToolInvokerFactory},
 * capturing the run id + user id; the shared services come from the factory.
 */
public final class DefaultWorkflowToolInvoker implements WorkflowToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowToolInvoker.class);

    private final FlywheelRunService flywheelRunService;
    private final WorkflowEvolveToolRegistryFactory registryFactory;
    private final ObjectMapper objectMapper;

    // Per-run state.
    private final String runId;
    private final Long userId;

    public DefaultWorkflowToolInvoker(FlywheelRunService flywheelRunService,
                                      WorkflowEvolveToolRegistryFactory registryFactory,
                                      ObjectMapper objectMapper,
                                      String runId,
                                      Long userId) {
        this.flywheelRunService = flywheelRunService;
        this.registryFactory = registryFactory;
        this.objectMapper = objectMapper;
        this.runId = runId;
        this.userId = userId;
    }

    @Override
    public Object invoke(String toolName, Map<String, Object> input, int stepIndex) {
        SkillRegistry registry = registryFactory.workflowEvolveToolRegistry();
        Tool tool = registry.getTool(toolName).orElseThrow(() ->
                new WorkflowToolException(toolName, stepIndex,
                        "not a whitelisted workflow tool (allowed: "
                                + registry.getAllTools().stream().map(Tool::getName).sorted().toList() + ")"));

        String stepInputJson = buildStepInput(toolName, stepIndex, input);
        String stepRunId = flywheelRunService.appendStep(
                runId, stepInputJson, FlywheelRunStepEntity.STEP_KIND_TOOL_CALL, stepIndex);

        SkillContext ctx = new SkillContext();
        ctx.setUserId(userId);

        long startNanos = System.nanoTime();
        SkillResult result;
        try {
            result = tool.execute(input, ctx);
        } catch (RuntimeException ex) {
            throw failStep(stepRunId, toolName, stepIndex, "threw", ex.getMessage());
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

        if (result == null || !result.isSuccess()) {
            String err = result == null ? "tool returned null" : result.getError();
            throw failStep(stepRunId, toolName, stepIndex, "failed", err);
        }

        // Parse the tool's String output into a JSON node (Map/List shape for the
        // JS) — fall back to a text node for non-JSON output. The SAME node is both
        // stored under `result` (so journal-replay re-reads it) and converted back
        // to a Java value returned to JS, so live + replay return identical shapes.
        JsonNode resultNode = parseOutput(result.getOutput());

        ObjectNode output = objectMapper.createObjectNode();
        output.put("toolName", toolName);
        output.set("result", resultNode);
        output.put("durationMs", durationMs);
        flywheelRunService.transitionStepStatus(
                stepRunId, FlywheelRunStepEntity.STATUS_COMPLETED, output, null);

        return objectMapper.convertValue(resultNode, Object.class);
    }

    /**
     * Transition the tool_call step to {@code error} and build the
     * {@link WorkflowToolException} to throw. Both failure paths (the tool threw, or
     * it returned a non-success {@code SkillResult}) funnel through here so the step
     * is always marked errored before the exception unwinds the workflow.
     */
    private WorkflowToolException failStep(String stepRunId, String toolName, int stepIndex,
                                           String how, String reason) {
        flywheelRunService.transitionStepStatus(
                stepRunId, FlywheelRunStepEntity.STATUS_ERROR, null, reason);
        log.warn("Workflow tool() step {} ({}) {}: {}", stepIndex, toolName, how, reason);
        return new WorkflowToolException(toolName, stepIndex, reason);
    }

    private JsonNode parseOutput(String output) {
        if (output == null || output.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(output);
        } catch (Exception e) {
            // Non-JSON tool output — keep the raw string as a text node.
            return objectMapper.getNodeFactory().textNode(output);
        }
    }

    private String buildStepInput(String toolName, int stepIndex, Map<String, Object> input) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("toolName", toolName);
        node.put("stepIndex", stepIndex);
        try {
            node.set("input", objectMapper.valueToTree(input));
        } catch (RuntimeException e) {
            // Never block the run on step-input serialization.
            node.put("inputError", String.valueOf(e.getMessage()));
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"toolName\":\"" + toolName + "\",\"stepIndex\":" + stepIndex + "}";
        }
    }
}
