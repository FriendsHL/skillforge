package com.skillforge.workflow.exception;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — thrown when a deterministic {@code tool()} node
 * fails: the whitelisted tool is unknown, returns a non-success
 * {@code SkillResult}, or its {@code execute} throws. Propagates out of the
 * workflow JS to {@code WorkflowRunnerService.runWorkflowBody}'s
 * {@code catch(Exception)} → {@code markError}, so a mechanical-step failure
 * fails the run (it must not be silently swallowed — the loop depends on the
 * tool's side effect, e.g. the A/B trigger).
 */
public class WorkflowToolException extends RuntimeException {

    private final String toolName;
    private final int stepIndex;

    public WorkflowToolException(String toolName, int stepIndex, String message) {
        super("tool('" + toolName + "') failed at stepIndex=" + stepIndex + ": " + message);
        this.toolName = toolName;
        this.stepIndex = stepIndex;
    }

    public String getToolName() {
        return toolName;
    }

    public int getStepIndex() {
        return stepIndex;
    }
}
