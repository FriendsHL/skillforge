package com.skillforge.workflow.exception;

/**
 * AUTOEVOLVING V1 Sprint 2 (Task G): an approve / resume was requested for a run
 * that is not currently {@code paused} on a {@code humanApprove()} gate. The REST
 * layer maps this to {@code 409 Conflict}.
 */
public class WorkflowNotPausedException extends RuntimeException {

    public WorkflowNotPausedException(String runId, String currentStatus) {
        super("Workflow run " + runId + " is not paused (status=" + currentStatus
                + "); cannot approve / resume");
    }
}
