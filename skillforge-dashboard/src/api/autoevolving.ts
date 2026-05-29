/**
 * AUTOEVOLVING V1 Sprint 4 (FE) — REST client + types for the `/autoevolving`
 * overview surface (`AutoEvolvingController`).
 *
 * Single read-only aggregation endpoint that collapses three signal sources +
 * KPI counts into one round-trip (see sprint4-plan-draft.md §5 "难题 4"):
 *   - cross-agent OPT-REPORT recent list (no per-agent endpoint needed)
 *   - week-window workflow KPI counts
 *   - failed-run anomalies (simplified anomaly feed)
 *
 * Field names mirror the BE Jackson default camelCase serialization. Keep this
 * file in lock-step with the BE DTO (java.md known footgun #6 / #6b — FE-BE
 * contract grep + outer-envelope shape + roundtrip IT + live curl).
 *
 * Envelope shape (footgun #6b):
 *   GET /api/autoevolving/overview → AutoEvolvingOverview (single object, NOT
 *   enveloped — FE reads `r.data` directly, not `r.data.items`).
 */
import api from './index';

// ───────────────────────────── KPI strip ───────────────────────────────────

/** Top-row KPI counts. `autoResearchPending` is a V1 placeholder (always null
 *  until V2 wires the autoResearch signal — render "N/A"). */
export interface AutoEvolvingKpi {
  /** FlywheelRun loopKind=workflow, status=running. */
  workflowRunning: number;
  /** Workflow runs that reached `completed` inside the week window. */
  workflowCompletedThisWeek: number;
  /** `t_memory_proposal` rows in status=proposed. */
  memoryProposalPending: number;
  /** V1 placeholder — always null (autoResearch ships V2). */
  autoResearchPending: number | null;
}

// ──────────────────────── signal source: production ─────────────────────────

/** One cross-agent OPT-REPORT row in the production signal panel. */
export interface AutoEvolvingRecentReport {
  reportId: string;
  /** Owning agent id; null for orphaned / cross-agent rows. */
  agentId: number | null;
  /** Resolved agent display name; null when unresolved. */
  agentName: string | null;
  /** ISO-8601 report window end; null when the underlying Instant is null. */
  windowEnd: string | null;
  status: string;
  /** Count of `topIssues` parsed from the report summary; BE primitive `int`
   *  (0 when the report has no structured summary — never null). */
  topIssueCount: number;
}

// ──────────────────────── anomaly diagnostics feed ──────────────────────────

/** One failed-run anomaly row (simplified anomaly feed — failed workflow runs). */
export interface AutoEvolvingRecentAnomaly {
  runId: string;
  /** Workflow name; null when absent. */
  name: string | null;
  /** Typically `error`. */
  status: string;
  /** Failure reason; null when absent. */
  errorReason: string | null;
  /** ISO-8601; null when the underlying Instant is null. */
  updatedAt: string | null;
}

// ──────────────────────────── overview object ──────────────────────────────

/** `GET /api/autoevolving/overview` — single object (NOT enveloped). */
export interface AutoEvolvingOverview {
  kpi: AutoEvolvingKpi;
  recentReports: AutoEvolvingRecentReport[];
  recentAnomalies: AutoEvolvingRecentAnomaly[];
}

export interface GetOverviewParams {
  userId: number;
  /** Week-window size in days for the KPI count; BE default 7. */
  weekDays?: number;
  /** Cap on recentReports rows; BE default 8. */
  reportLimit?: number;
}

/** `GET /api/autoevolving/overview` — fetch the autoEvolving overview snapshot. */
export const getOverview = (params: GetOverviewParams) =>
  api.get<AutoEvolvingOverview>('/autoevolving/overview', {
    params: {
      userId: params.userId,
      weekDays: params.weekDays ?? 7,
      reportLimit: params.reportLimit ?? 8,
    },
  });
