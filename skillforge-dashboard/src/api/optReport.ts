/**
 * OPT-REPORT-V1 Sub-batch 2 (FE) — REST client wrappers for the three
 * OPT-REPORT endpoints (`OptReportController` on the BE side).
 *
 * Contract source of truth: `skillforge-server/src/main/java/com/skillforge/
 * server/optreport/OptReportController.java`. Field names mirror the BE
 * Jackson default camelCase serialization; keep this file in lock-step with
 * the controller's `toSummaryDto` / `toFullDto` helpers per
 * `.claude/rules/java.md` known footgun #6 / #6b (FE-BE Jackson shape grep
 * + roundtrip verification).
 *
 * BE → FE type mapping reminder:
 *   Java Long     → number
 *   Java Instant  → ISO-8601 string
 *   Java nullable → T | null
 *   Java Map<String,Object> with LinkedHashMap → object literal
 *
 * ⚠️ Discrepancy vs Sub-batch 2 brief (resolved by grepping the actual BE
 * Controller per footgun #6b "outer envelope shape" checklist):
 *
 *   - Brief said list endpoint returns a bare array; BE actually returns
 *     `{items: [...], limit: N}` envelope (LinkedHashMap). We type the
 *     envelope so consumers `r.data.items` correctly — not `r.data ?? []`
 *     which would treat the envelope object as `OptReport[]`.
 *   - Brief said the report DTO uses an `id` field; BE actually emits
 *     `reportId`. We use `reportId` so reads aren't silently undefined.
 *   - Brief listed `contentMd` / `summaryJson` on the list DTO; BE only
 *     emits those on the single-report detail endpoint. We split the two
 *     shapes into `OptReportSummary` (list) and `OptReport` (detail) so
 *     consumers can't read `contentMd` off a summary row by accident.
 */
import api from './index';

// ───────────────────────── DTO types ──────────────────────────────────────

/** Lifecycle states emitted by `OptReportEntity.status`. */
export type OptReportStatus = 'pending' | 'running' | 'completed' | 'error';

/**
 * Summary fields returned by `GET /api/flywheel/agents/{agentId}/reports`
 * (one row per item in the envelope `.items` array). Mirrors BE
 * `OptReportController#toSummaryDto`.
 */
export interface OptReportSummary {
  reportId: string;
  agentId: number;
  windowStart: string; // ISO-8601
  windowEnd: string;   // ISO-8601
  status: OptReportStatus;
  createdAt: string;   // ISO-8601
  updatedAt: string;   // ISO-8601
}

/**
 * Full report fields returned by `GET /api/flywheel/reports/{reportId}`.
 * Extends the summary shape with the rendered markdown / structured summary
 * / error / generator session linkage. Mirrors BE
 * `OptReportController#toFullDto`.
 *
 * `summaryJson` is a raw JSON **string** (BE stores `text` column verbatim);
 * the FE must `JSON.parse` before consuming. The renderer guards against
 * malformed JSON with a try/catch fallback.
 */
export interface OptReport extends OptReportSummary {
  contentMd: string | null;
  summaryJson: string | null;
  errorReason: string | null;
  generatorSessionId: string | null;
}

/**
 * Envelope returned by the list endpoint. Per footgun #6b, this is wrapped
 * in a `{items, limit}` LinkedHashMap on the BE — do NOT collapse to bare
 * array here; consumers must read `r.data.items`.
 */
export interface ListOptReportsResponse {
  items: OptReportSummary[];
  limit: number;
}

/**
 * 202 ACCEPTED response body from
 * `POST /api/flywheel/agents/{agentId}/generate-report`.
 *
 * Mirrors BE `OptReportController#generateReport`'s `body` LinkedHashMap
 * field order: `reportId, agentId, agentName, windowStart, windowEnd,
 * status, note`. `status` is `"running"` on the happy path (the BE
 * synchronously seeds the row in `pending` and `OptReportService` flips it
 * to `running` before returning).
 */
export interface GenerateOptReportResponse {
  reportId: string;
  agentId: number;
  agentName: string;
  windowStart: string;
  windowEnd: string;
  status: OptReportStatus;
  note: string;
}

// ───────────────────────── API wrappers ───────────────────────────────────

/**
 * `POST /api/flywheel/agents/{agentId}/generate-report?windowDays=14`.
 *
 * Returns 202 immediately with the freshly-created `reportId`. The actual
 * report generation runs asynchronously inside the `report-generator` agent
 * session; the dashboard listens for the `opt_report_completed` WS event
 * (broadcast by `OptReportService#onReportCompleted`) to refresh the list.
 *
 * V1.1: default raised from 7 → 14 to exercise SubAgent fan-out (batchSize=5).
 */
export const generateOptReport = (agentId: number, windowDays: number = 14) =>
  api.post<GenerateOptReportResponse>(
    `/flywheel/agents/${agentId}/generate-report`,
    null,
    { params: { windowDays } },
  );

/**
 * `GET /api/flywheel/agents/{agentId}/reports?limit=20` — list reports for
 * a single agent, newest first. BE clamps `limit` to [1, 100], default 20.
 *
 * Returns an envelope `{items, limit}` (NOT a bare array — see footgun #6b
 * notes at top of file).
 */
export const listOptReports = (agentId: number, limit: number = 20) =>
  api.get<ListOptReportsResponse>(
    `/flywheel/agents/${agentId}/reports`,
    { params: { limit } },
  );

/**
 * `GET /api/flywheel/reports/{reportId}` — single report with full
 * markdown + structured summary. Throws 404 if the reportId is unknown.
 */
export const getOptReport = (reportId: string) =>
  api.get<OptReport>(`/flywheel/reports/${reportId}`);
