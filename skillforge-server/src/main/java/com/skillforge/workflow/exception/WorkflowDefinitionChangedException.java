package com.skillforge.workflow.exception;

/**
 * AUTOEVOLVING V1 Sprint 2 (Task G): resume was refused because the workflow
 * definition's {@code sourceHash} no longer matches the one recorded when the run
 * started (the {@code *.workflow.js} file was edited / hot-reloaded). Replaying a
 * changed script would break the deterministic step-index alignment the journal
 * cache relies on (plan §2.2), so resume is rejected with {@code 409 Conflict}.
 */
public class WorkflowDefinitionChangedException extends RuntimeException {

    public WorkflowDefinitionChangedException(String runId) {
        super("Workflow definition changed since run " + runId
                + " started; cannot resume (sourceHash mismatch)");
    }
}
