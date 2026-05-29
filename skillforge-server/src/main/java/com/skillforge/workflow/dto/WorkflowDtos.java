package com.skillforge.workflow.dto;

import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVING V1 Sprint 2 (Task G) — REST DTOs for {@code WorkflowController}.
 *
 * <p>Item DTOs are records (FE-BE contract: field names/types pinned — java.md
 * footgun #6). List endpoints wrap these in a {@code {items, ...}} Map envelope
 * (never a bare array — footgun #6b); single resources return the record / a
 * detail Map directly.
 */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    /** {@code GET /api/workflows} item — a registered workflow definition. */
    public record WorkflowSummaryDto(String name, String description, List<PhaseDto> phases) {
    }

    /** A {@code meta.phases[]} entry. */
    public record PhaseDto(String title, String detail) {
    }

    /** {@code GET /api/workflows/runs} item — a workflow run row. */
    public record WorkflowRunSummaryDto(
            String runId,
            String name,
            String status,
            String createdAt,
            String updatedAt) {
    }

    /**
     * A step row inside {@code GET /api/workflows/runs/{runId}}.
     *
     * <p>{@code phase} (Sprint 3 B2) is the {@code meta.phases[]} title the step
     * belongs to (e.g. {@code "Load"} / {@code "Annotate"}), parsed from the
     * step's {@code step_input_json} — the FE DAG groups agent nodes by it. It is
     * null for legacy / non-workflow steps that never recorded a phase.
     *
     * <p>{@code payload} (AUTOEVOLVING V1 Sprint 4, W1) is the arbitrary JSON
     * argument passed to {@code humanApprove(payload)}, parsed from the step's
     * {@code step_input_json.payload} node. It is the same object the
     * {@code workflow_human_approve_required} WS frame carries — exposing it here
     * lets the FE approve-card page-load recovery path (list paused runs → get
     * run detail → read the pending {@code human_approve} step's payload) render
     * without waiting for a fresh WS push. {@code null} for every non-gate step.
     */
    public record WorkflowStepDto(
            Integer stepIndex,
            String stepKind,
            String status,
            String agentSlug,
            String phase,
            Object payload,
            String createdAt,
            String updatedAt) {
    }

    /** {@code POST /api/workflows/{name}/run} body. {@code args} may be null/empty. */
    public record RunWorkflowRequest(Map<String, Object> args) {
    }

    /**
     * {@code POST /api/workflows/runs/{runId}/approve} body. {@code decision} is
     * {@code 'approved'} or {@code 'rejected'}; {@code reason} optional.
     */
    public record ApproveRequest(String decision, String reason) {
    }
}
