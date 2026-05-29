package com.skillforge.workflow.exception;

/**
 * AUTOEVOLVING V1 Sprint 2: thrown by the {@code humanApprove()} host binding
 * to unwind the Rhino workflow thread when a run first reaches an approval gate.
 *
 * <p>This is <b>not an error</b>: the run row has already been transitioned to
 * {@code status=paused} and the paused {@code human_approve} step persisted, so
 * all state lives in the DB. {@code WorkflowRunnerService.runWorkflowBody} must
 * catch this <em>before</em> its generic {@code catch (Exception)} and skip
 * {@code markError} — the run stays {@code paused} and the per-name lock is
 * released so a later approve (chunk 2) can re-acquire it and resume.
 */
public class WorkflowPausedException extends RuntimeException {

    private final String runId;
    private final String stepRunId;
    private final int stepIndex;

    public WorkflowPausedException(String runId, String stepRunId, int stepIndex) {
        super("Workflow paused for human approval: runId=" + runId
                + " stepRunId=" + stepRunId + " stepIndex=" + stepIndex);
        this.runId = runId;
        this.stepRunId = stepRunId;
        this.stepIndex = stepIndex;
    }

    public String getRunId() {
        return runId;
    }

    public String getStepRunId() {
        return stepRunId;
    }

    public int getStepIndex() {
        return stepIndex;
    }
}
