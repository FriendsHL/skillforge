import api from './index';
import type { AgentRole } from './behaviorRule';

/**
 * EVAL-DATASET-LAYER V1 — REST client for `/api/eval/datasets` +
 * `/api/eval/dataset-versions/*` + the extended `/api/eval/scenarios` filter.
 *
 * Keep this file in lock-step with the BE controller / DTO definitions per
 * `.claude/rules/java.md` footgun #6 (FE-BE contract):
 *
 *   skillforge-server/.../eval/dataset/EvalDatasetController.java
 *   skillforge-server/.../eval/dataset/EvalDatasetService.java
 *   skillforge-server/.../eval/dataset/EvalDatasetEntity.java
 *   skillforge-server/.../eval/dataset/EvalDatasetVersionEntity.java
 *
 * BE → FE type mapping reminder (java.md table):
 *   Java Long/Integer/int → number
 *   Java Instant          → ISO-8601 string
 *   Java Double           → number
 *   Java nullable boxed   → T | null
 *   Java jsonb            → Record<string, …> or arbitrary JSON
 *
 * Field naming convention: BE uses Jackson default camelCase, so all FE
 * interfaces below are camelCase. snake_case in SQL (`source_type`,
 * `dataset_version_id`) maps to camelCase JSON (`sourceType`,
 * `datasetVersionId`).
 *
 * Outer envelope shape (footgun #6b — DO NOT assume):
 *   Most endpoints return a bare list / single object (no `{items: ...}`
 *   wrapping); the BE-Dev javadoc is the source of truth. If you see
 *   `r.data` typed as `T[]`, treat that as the BE returning a naked array.
 *   When in doubt, run `curl` and grep `r.data` shape before shipping.
 */

// ─── EvalScenario (extended with source_type / source_ref / purpose) ──────

/**
 * EVAL-DATASET-LAYER V1 — new enum on `t_eval_scenario.source_type` (V109).
 * Reviewer / dataset composition / A/B run uses this closed enum to decide
 * baseline-vs-regression treatment.
 *
 * Note: This is **orthogonal** to the existing free-form `category` VARCHAR
 * which remains an open user-tag. See tech-design §1.1 for the rationale.
 */
export type EvalScenarioSourceType = 'benchmark' | 'session_derived' | 'manual';

/**
 * EVAL-DATASET-LAYER V1 — new enum on `t_eval_scenario.purpose` (V109,
 * wiki r2). Orthogonal to source_type. Aligns with SWE-bench regression-aware
 * categorization (F2P / FAIL_TO_FAIL / PASS_TO_PASS / PASS_TO_FAIL).
 */
export type EvalScenarioPurpose = 'baseline_anchor' | 'regression' | 'ablation';

/**
 * Extended EvalScenario shape returned by `/api/eval/scenarios` with the
 * new V109 fields. The existing {@link import('./index').EvalDatasetScenario}
 * type lives in `api/index.ts` and remains the canonical browser-tab
 * projection; this `EvalScenarioDto` is the lean shape used by the
 * Dataset / DatasetVersion endpoints so that listings don't drag the
 * heavy multi-turn / setup file metadata.
 */
export interface EvalScenarioDto {
  id: string;
  agentId: string | null;
  name: string;
  description?: string | null;
  category: string | null;
  split: string | null;
  task: string;
  oracleType: string | null;
  oracleExpected?: string | null;
  status: 'draft' | 'active' | 'discarded';
  /** V109 new — closed enum, NOT NULL after V109. */
  sourceType: EvalScenarioSourceType;
  /** V109 new — nullable; identifier like `gaia/lv1#001` or `session:5f3f...`. */
  sourceRef?: string | null;
  /** V109 new — closed enum, NOT NULL. */
  purpose: EvalScenarioPurpose;
  createdAt: string;
  /**
   * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (V117) — JSONB tag list declaring
   * which agent roles this scenario applies to. Used by
   * BehaviorRuleAbEvalService to split a dataset into target (matches owner
   * agent role) vs regression ('general') subsets, and by the DatasetBrowser
   * role filter tab.
   *
   * <p>r2-FE-1 (ts-reviewer W1): typed {@code string[] | null} — same open
   * shape as {@link import('./index').EvalDatasetScenario.applicableAgentRoles}
   * — for two reasons:
   * <ol>
   *   <li>When BE adds a 6th role, the {@link AgentRole} union doesn't
   *       break compilation here (avoids forcing FE redeploy on every BE
   *       enum extension)</li>
   *   <li>BE may serialize {@code null} (not just omit) for legacy rows;
   *       open {@code | null} matches the wire shape exactly</li>
   * </ol>
   * Closed {@link AgentRole} type is still useful at the call site (e.g.
   * {@code s.applicableAgentRoles?.includes(roleTab as AgentRole)}) but
   * the interface keeps the wire shape open.
   *
   * <p>Always access via {@code s.applicableAgentRoles?.includes(role)} with
   * the optional chain (a missing field — undefined — returns false instead
   * of throwing TypeError).
   */
  applicableAgentRoles?: string[] | null;
}

/** Filter params for the extended `/api/eval/scenarios` endpoint. */
export interface EvalScenarioListParams {
  /** Existing param; still honored by the BE. */
  agentId?: string | number;
  /** V109 new — server-side filter on closed enum. */
  sourceType?: EvalScenarioSourceType;
  /** V109 new — server-side filter on closed enum. */
  purpose?: EvalScenarioPurpose;
  /** V109 new — exact match on `source_ref`. */
  sourceRef?: string;
  /**
   * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (tech-design §4) — OR-semantics
   * filter: rows whose {@code applicable_agent_roles} JSONB array contains
   * ANY of the supplied roles are returned. Mirrors the BE
   * {@code jsonb_exists_any(applicable_agent_roles, CAST(:roles AS text[]))}
   * query. Serialized as a comma-joined string on the wire — empty array
   * (or undefined) → no filter applied (matches BE behaviour).
   *
   * <p>Combines with {@link agentId} / {@link sourceType} / {@link purpose}
   * via AND on the BE side (each filter narrows the result set).
   */
  roles?: AgentRole[];
}

/**
 * Extended scenario list. Note: this is a SEPARATE call from
 * {@link import('./index').getEvalDatasetScenarios} which targets the legacy
 * per-agent browser projection. Callers that already use that projection
 * should keep using it; this one is for the new Datasets surface where the
 * full source_type / purpose taxonomy matters.
 *
 * Outer envelope: bare array (BE controller returns `ResponseEntity.ok(list)`).
 *
 * <p>FLYWHEEL-AB-AGENT-AWARE-DATASET V1: when {@code params.roles} is a
 * non-empty array, serialize as a comma-joined string per the BE
 * {@code @RequestParam String roles} contract. Empty / undefined arrays are
 * dropped from the request entirely (axios skips undefined keys) so the BE
 * sees no {@code roles} param and falls back to its no-filter branch.
 */
export const listEvalScenarios = (params: EvalScenarioListParams = {}) => {
  const { roles, ...rest } = params;
  const wireParams: Record<string, unknown> = { ...rest };
  if (roles && roles.length > 0) {
    wireParams.roles = roles.join(',');
  }
  return api.get<EvalScenarioDto[]>('/eval/scenarios', { params: wireParams });
};

// ─── EvalDataset (V110) ───────────────────────────────────────────────────

export interface EvalDataset {
  id: string;
  name: string;
  description?: string | null;
  ownerId: number;
  /** Nullable: null = cross-agent / generic dataset. */
  agentId?: string | null;
  /** Free-form tags (e.g. ["gaia", "lv1", "baseline"]). */
  tags?: string[] | null;
  isPublic: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Convenience enrichment fields the BE adds to the dataset list row
 * (`EvalDatasetController.listDatasets` → enriched via a single batch
 * query + per-latest-version scenario-id lookup; no N+1).
 *
 * All fields are optional in the TS interface so the FE degrades
 * gracefully if a future BE deploy omits them; in practice V1 BE always
 * emits all six.
 */
export interface EvalDatasetSummary extends EvalDataset {
  /** Count of {@link EvalDatasetVersion} rows owned by this dataset. */
  versionCount?: number;
  /** Latest version_number (1, 2, 3, …); null when no version has been published. */
  latestVersionNumber?: number | null;
  /** Latest version's id (UUID) — handy for a one-click drill-down. */
  latestVersionId?: string | null;
  /** Latest version's scenario count (0 when no versions). */
  latestScenarioCount?: number;
  /**
   * Latest version's expected_baseline_pass_rate (decimal 0..1) from the
   * heuristic. Null when no version published yet.
   */
  latestExpectedBaselinePassRate?: number | null;
  /**
   * Latest version's actual_baseline_pass_rate (decimal 0..1). Null until
   * the first A/B run completes and writes back (tech-design §3.2 D1).
   * FE displays this with priority over the expected value.
   */
  latestActualBaselinePassRate?: number | null;
}

export interface CreateDatasetRequest {
  name: string;
  description?: string;
  ownerId: number;
  agentId?: string | null;
  tags?: string[];
  isPublic?: boolean;
}

/** GET /api/eval/datasets?ownerId=&agentId= — bare array. */
export const listDatasets = (params: { ownerId?: number; agentId?: string } = {}) =>
  api.get<EvalDatasetSummary[]>('/eval/datasets', { params });

/** GET /api/eval/datasets/{id} — single object. */
export const getDataset = (id: string) =>
  api.get<EvalDataset>(`/eval/datasets/${id}`);

/** POST /api/eval/datasets — returns created EvalDataset. */
export const createDataset = (req: CreateDatasetRequest) =>
  api.post<EvalDataset>('/eval/datasets', req);

// ─── EvalDatasetVersion (V110) ────────────────────────────────────────────

/**
 * Composition stats jsonb shape. Computed by the BE on publishVersion and
 * stored verbatim in `composition_stats`. FE reads it to render the
 * dataset-health badge + composition warnings.
 *
 * The `expected_baseline_pass_rate` value is a decimal in [0, 1] — the
 * BaselinePassRateHeuristic estimate (tech-design §1.3). FE shows it with
 * a ±30% confidence band.
 */
export interface CompositionStats {
  benchmark?: number;
  session_derived?: number;
  manual?: number;
  total?: number;
  purpose_baseline_anchor?: number;
  purpose_regression?: number;
  purpose_ablation?: number;
  expected_baseline_pass_rate?: number;
  /** Allow forward-compat / extra fields without losing them. */
  [key: string]: unknown;
}

export interface EvalDatasetVersion {
  id: string;
  datasetId: string;
  versionNumber: number;
  compositionStats?: CompositionStats | null;
  /** SHA256 of sorted scenario_ids, 64-char hex string. */
  compositionHash?: string | null;
  /**
   * tech-design §3.2 D1 fix: written back after at least one A/B run
   * completes. FE shows this with priority over the estimated value when
   * non-null. Decimal in [0, 1].
   */
  actualBaselinePassRate?: number | null;
  createdAt: string;
  createdBy?: number | null;
}

/**
 * Lightweight scenario shape returned inside the `/dataset-versions/{id}`
 * envelope. Matches `EvalDatasetController.toScenarioBriefMap` — BE returns
 * this trimmed projection (8 fields) to keep the version detail payload
 * small. Heavier fields (description / task / oracleExpected / etc.) require
 * a separate `/eval/scenarios/{id}` call when needed.
 */
export interface EvalScenarioBrief {
  id: string;
  name: string;
  agentId: string | null;
  sourceType: EvalScenarioSourceType | null;
  sourceRef: string | null;
  purpose: EvalScenarioPurpose | null;
  oracleType: string | null;
  status: 'draft' | 'active' | 'discarded';
}

/**
 * Version + scenarios envelope returned by `GET /eval/dataset-versions/{id}`.
 *
 * **IMPORTANT — outer envelope (java.md footgun #6b)**: this endpoint does
 * NOT flatten version fields to the top level. The BE returns:
 *
 * ```json
 * {
 *   "version":     { id, datasetId, versionNumber, compositionStats, ... },
 *   "scenarioIds": ["s1", "s2", ...],
 *   "scenarios":   [{ id, name, agentId, sourceType, ... }, ...]
 * }
 * ```
 *
 * Read `data.version.versionNumber` (not `data.versionNumber`), and
 * `data.scenarios` for the brief list. `scenarioIds` is provided separately
 * so callers that only need ids can avoid walking the scenarios array.
 *
 * Source: `EvalDatasetController.getVersionWithScenarios` (line 134-147).
 */
export interface EvalDatasetVersionDetail {
  version: EvalDatasetVersion;
  scenarioIds: string[];
  scenarios: EvalScenarioBrief[];
}

export interface PublishVersionRequest {
  scenarioIds: string[];
  /** Optional user-id for `created_by` attribution. */
  createdBy?: number;
}

/**
 * Health assessment surfaced by GET `/api/eval/dataset-versions/{id}/health`.
 * See tech-design §3.1 — V1 returns warnings, doesn't block (composition
 * policy V1 is advisory not enforcing per design decision D5).
 *
 * **Outer envelope (java.md footgun #6b)**: BE returns plain
 * `{isHealthy, warnings}` — warnings is `string[]`, NOT structured
 * `{code, message, severity}` objects. Source: `EvalDatasetService.
 * DatasetHealthAssessment(boolean isHealthy, List<String> warnings)` record
 * (service line 330) + `EvalDatasetController.assessHealth` (controller
 * line 150-158). FE renders warnings as a plain list / banner.
 */
export interface DatasetHealthAssessment {
  isHealthy: boolean;
  warnings: string[];
}

/** GET /api/eval/datasets/{id}/versions — bare array, ordered desc by versionNumber. */
export const listVersions = (datasetId: string) =>
  api.get<EvalDatasetVersion[]>(`/eval/datasets/${datasetId}/versions`);

/** POST /api/eval/datasets/{id}/versions — publish new version. */
export const publishVersion = (datasetId: string, req: PublishVersionRequest) =>
  api.post<EvalDatasetVersion>(`/eval/datasets/${datasetId}/versions`, req);

/** GET /api/eval/dataset-versions/{id} — version + scenarios bundle. */
export const getDatasetVersion = (versionId: string) =>
  api.get<EvalDatasetVersionDetail>(`/eval/dataset-versions/${versionId}`);

/** GET /api/eval/dataset-versions/{id}/health — composition health assessment. */
export const getDatasetVersionHealth = (versionId: string) =>
  api.get<DatasetHealthAssessment>(`/eval/dataset-versions/${versionId}/health`);

// ─── Helpers ──────────────────────────────────────────────────────────────

/**
 * Format an expected/actual baseline pass rate (decimal in [0, 1]) for
 * display. Returns "—" for null / NaN so the dataset list table doesn't
 * collapse rows. Pluggable suffix is for "actual" vs "estimated" rendering
 * (see tech-design §1.3 FE display priority table).
 */
export function formatBaselinePassRate(
  rate: number | null | undefined,
  suffix = '',
): string {
  if (rate == null || !Number.isFinite(rate)) return '—';
  const pct = Math.round(rate * 100);
  return suffix ? `${pct}% ${suffix}` : `${pct}%`;
}

/**
 * Pick the display rate per the tech-design §1.3 priority table:
 *   actual > expected > null
 * Returns `{ value, kind }` so callers can also pick a label/badge color.
 */
export function pickBaselineDisplay(
  actual: number | null | undefined,
  expected: number | null | undefined,
): { value: number | null; kind: 'actual' | 'expected' | 'none' } {
  if (actual != null && Number.isFinite(actual)) return { value: actual, kind: 'actual' };
  if (expected != null && Number.isFinite(expected)) return { value: expected, kind: 'expected' };
  return { value: null, kind: 'none' };
}
