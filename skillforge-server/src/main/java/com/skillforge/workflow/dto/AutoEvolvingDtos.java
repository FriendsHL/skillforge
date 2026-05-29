package com.skillforge.workflow.dto;

import java.util.List;

/**
 * AUTOEVOLVING V1 Sprint 4 (Task C) — REST DTOs for {@code AutoEvolvingController}.
 *
 * <p>All records (FE-BE contract: field names/types pinned — java.md footgun
 * #6). The {@code GET /api/autoevolving/overview} endpoint returns a single
 * {@link OverviewResponse} object envelope (never a bare array — footgun #6b);
 * the FE binds it as {@code api.get<OverviewResponse>}.
 */
public final class AutoEvolvingDtos {

    private AutoEvolvingDtos() {
    }

    /**
     * Top-level overview payload. Single object (not a list) so the FE wrapper
     * is {@code api.get<OverviewResponse>(...)} reading {@code r.data} directly.
     */
    public record OverviewResponse(
            KpiDto kpi,
            List<RecentReportDto> recentReports,
            List<RecentAnomalyDto> recentAnomalies) {
    }

    /**
     * The 4-card KPI strip. {@code autoResearchPending} is a V1 placeholder
     * (always {@code null}; the auto-research signal is deferred to V2 —
     * FR-6.2) — FE renders an "N/A" card. The three live counts are
     * non-negative longs.
     */
    public record KpiDto(
            long workflowRunning,
            long workflowCompletedThisWeek,
            long memoryProposalPending,
            Long autoResearchPending) {
    }

    /**
     * One row in the cross-agent "recent OPT-REPORT" panel. {@code agentName}
     * is resolved from {@code t_agent}; null when the agent row was deleted.
     * {@code topIssueCount} is the length of {@code summary_json.topIssues}
     * (0 when the report has no structured summary / is unparseable — never
     * throws, KPI display is tolerant).
     */
    public record RecentReportDto(
            String reportId,
            Long agentId,
            String agentName,
            String windowEnd,
            String status,
            int topIssueCount) {
    }

    /**
     * One row in the simplified anomaly panel (FR-6.5): a failed workflow run.
     * {@code name} is the {@code workflow_name} parsed from the run's
     * {@code input_json}; {@code errorReason} is the run-level failure detail.
     */
    public record RecentAnomalyDto(
            String runId,
            String name,
            String status,
            String errorReason,
            String updatedAt) {
    }
}
