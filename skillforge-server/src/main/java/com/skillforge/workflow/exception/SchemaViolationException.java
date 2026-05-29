package com.skillforge.workflow.exception;

import java.util.List;

/**
 * AUTOEVOLVING V1 Sprint 2: thrown when an {@code agent('...', {schema})} call's
 * output still fails JSON-Schema validation after all retry attempts are
 * exhausted ({@code DefaultWorkflowAgentInvoker}, 3 total attempts per
 * FR-1.4 / W2). Propagates out of the workflow body and is handled by
 * {@code WorkflowRunnerService.runWorkflowBody}'s generic catch → the run is
 * marked {@code error}.
 */
public class SchemaViolationException extends RuntimeException {

    private final int stepIndex;
    private final transient List<String> violations;

    public SchemaViolationException(int stepIndex, List<String> violations) {
        super("Agent output violated schema after retries (stepIndex=" + stepIndex
                + "): " + violations);
        this.stepIndex = stepIndex;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public List<String> getViolations() {
        return violations;
    }
}
