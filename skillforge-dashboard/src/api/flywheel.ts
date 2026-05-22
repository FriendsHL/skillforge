/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 (FE) — REST client wrappers for the 3 new
 * BE endpoints the FlywheelObservabilityPanel consumes.
 *
 * Field names mirror the BE Jackson default camelCase serialization. Keep
 * this file in lock-step with the BE record / DTO definitions (per
 * `.claude/rules/java.md` known footgun #6 — FE-BE contract grep + roundtrip
 * IT verification).
 *
 *   skillforge-server/.../skill/SkillAbTestController.java          (new global)
 *   skillforge-server/.../canary/CanaryRolloutController.java       (agentId optional)
 *   skillforge-server/.../skill/SkillDraftController.java           (source filter)
 *
 * BE → FE type mapping reminder (java.md table):
 *   Java Long/Integer/int → number
 *   Java Instant          → ISO-8601 string
 *   Java BigDecimal       → number (Jackson default — numeric JSON token)
 *   Java nullable boxed   → T | null
 *
 * **BE-Dev parallel TODO**: when be-dev (task #7) lands its diff, verify:
 *   1. `GET /api/skills/abtest` query params + response shape match below
 *   2. `GET /api/canary/rollouts` accepts no agentId (returns all)
 *   3. `GET /api/skill-drafts?source=...` honours the source filter
 *
 * Until BE-Dev lands, FE callers tolerate empty arrays (panel renders
 * "0 in-flight" health state, not error).
 */
import api from './index';
import type { SkillAbRun } from './index';
import type { CanaryRolloutResponse } from './canary';
import type {
  FlywheelRunDto,
  FlywheelSurface,
} from '../components/flywheel/types';

// ───────────────────────── /api/flywheel/runs (per-run view) ─────────────
//
// FLYWHEEL-PER-RUN — list recent OptimizationEvent runs with stage/error
// metadata for the per-run mode of the flywheel observability panel.

export interface ListFlywheelRunsParams {
  /** Filter by agent type (user / system); absent = both. */
  agentType?: 'user' | 'system';
  /** Filter by surface; absent = all surfaces. */
  surface?: FlywheelSurface;
  /** BE default 20, clamped [1, 100]. */
  limit?: number;
  /**
   * When true (BE default), hide terminal-state runs (promoted / discarded).
   * Pass false to include them.
   */
  hideTerminal?: boolean;
}

/**
 * BE envelope for `/api/flywheel/runs` — Controller returns
 * `{items, limit, hideTerminal}` (LinkedHashMap, stable field order). Both
 * outer shape AND inner items must be locked in this TS interface — FE-BE
 * Jackson contract footgun #6 covers field NAMES inside DTO but NOT the
 * envelope shape; previous r1 review verified the 12 DTO fields but missed
 * that BE returned `{items:[…]}` not bare array, triggering "runs is not
 * iterable" at runtime.
 */
export interface FlywheelRunsResponse {
  items: FlywheelRunDto[];
  limit: number;
  hideTerminal: boolean;
}

/**
 * `GET /api/flywheel/runs` — returns up to `limit` recent OptimizationEvent
 * runs sorted by lastUpdatedAt DESC. See BE FlywheelController for the
 * canonical contract; FE TS interface lives in components/flywheel/types.ts
 * so it can be shared between API wrapper + hook + sidebar component
 * without circular imports.
 */
export const listFlywheelRuns = (params?: ListFlywheelRunsParams) =>
  api.get<FlywheelRunsResponse>('/flywheel/runs', { params });

// ───────────────────────── /api/skills/abtest (global) ─────────────────────

/**
 * Lifecycle status of a skill A/B run, mirroring `t_skill_ab_run.status`.
 *
 * code-WARN-5 fix — BE javadoc on `SkillController.listAbTestsGlobal`
 * Phase 2 documents 5 values; `SKIPPED` rows surface when the BE rejected
 * a candidate run before it executed (cooldown / no scenarios). The
 * legacy `SkillAbRun.status` union in `api/index.ts` stays narrower
 * because the per-skill endpoint doesn't emit SKIPPED today — keep the
 * two aliases independent so legacy callers don't get a forward-compat
 * variant they can't handle.
 */
export type SkillAbRunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'SKIPPED';

export interface ListAbTestRunsParams {
  /** BE-side filter; absent = no agent narrowing. */
  agentId?: string | number;
  status?: SkillAbRunStatus;
  /** Reserved for future migration; BE rejects non-`skill` values today. */
  surfaceType?: string;
  /** 1-based page; BE default 1. */
  page?: number;
  /** Page size; BE clamps [1, 100], default 20. */
  pageSize?: number;
}

/**
 * BE-Dev paged response envelope (mirrors `listDraftsPaged`):
 *   { items: [...], page, pageSize, total, totalPages }
 *
 * FE consumes the `items` array; pagination metadata is exposed for future
 * use but FlywheelObservability today does not paginate (read "in-flight
 * count" + "24h today" from page 1 only).
 */
export interface PagedAbTestRunsResponse {
  items: SkillAbRun[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}

/**
 * `GET /api/skills/abtest` — paginated global list of A/B runs across all
 * skills / agents (BE-Dev added this in task #7).
 *
 * Returns the paged envelope; FlywheelObservability reads `.items` to get
 * the run array (max 100 per page). Sorting is `started_at DESC` (BE-side).
 */
export const listAbTestRunsGlobal = (params?: ListAbTestRunsParams) =>
  api.get<PagedAbTestRunsResponse>('/skills/abtest', { params });

// ───────────────────── /api/canary/rollouts (agentId optional) ─────────────

/**
 * `GET /api/canary/rollouts` — agentId now optional (BE-Dev task #7 change).
 * When agentId is absent, BE returns all rollouts across agents. Note this
 * differs from {@link import('./canary').listCanaries} which requires
 * agentId — we keep that wrapper untouched for callers (CanaryPanel) that
 * legitimately need per-agent scoping, and add this separate wrapper for
 * the global / cross-agent flywheel observability view.
 */
export interface ListCanariesGlobalParams {
  agentId?: number;
  surfaceType?: string;
  stage?: 'disabled' | 'canary' | 'production' | 'rolled_back';
}

export const listCanariesGlobal = (params?: ListCanariesGlobalParams) =>
  api.get<CanaryRolloutResponse[]>('/canary/rollouts', { params });

// ───────────────────── /api/skill-drafts?source= (filter) ─────────────────

/**
 * Known `source` enum values (from tech-design D8 + currently-seen rows).
 * Free-form on the BE so an unknown value is tolerated (FE bucket = "other").
 */
export type SkillDraftSource =
  | 'upload'
  | 'marketplace'
  | 'natural-language'
  | 'extract-from-sessions'
  | 'attribution'
  | 'manual'
  | 'skill-creator-eval';

export interface ListSkillDraftsBySourceParams {
  userId: number;
  /** Single literal source value, or comma-separated list (BE intent). */
  source?: string;
  status?: string;
}

/**
 * `GET /api/skill-drafts?userId=&source=&status=` — extends the existing
 * `getSkillDrafts(userId)` wrapper with the new `source` filter BE-Dev is
 * adding. We export a separate function so existing callers (SkillList /
 * SkillDrafts pages) don't accidentally start sending the new param via
 * a shared signature change.
 */
import type { SkillDraft } from './index';

export const listSkillDraftsBySource = (params: ListSkillDraftsBySourceParams) =>
  api.get<SkillDraft[]>('/skill-drafts', { params });

// ───────────────── /api/flywheel/agents/{agentId}/run-loop (on-demand) ───────
//
// FLYWHEEL-PER-AGENT-RUN-NOW Round 1 BE (commit 004689d) — on-demand per-agent
// opt loop trigger. POST returns 202 immediately; downstream chain runs async.
//
// Error cases FE handles:
//   404 — agent not found
//   503 — session-annotator / attribution-dispatcher system agent not seeded
//   400 — agentId invalid

export interface RunFlywheelLoopParams {
  /** Number of hours back to look for sessions (BE default: 24). */
  windowHours?: number;
  /** Max sessions to annotate in this run (BE default: 10). */
  max?: number;
}

/**
 * 202 ACCEPTED response body from
 * `POST /api/flywheel/agents/{agentId}/run-loop`.
 *
 * Mirrors BE `FlywheelPerAgentRunResponse` record — field names are Jackson
 * camelCase defaults. Java Long → number, Java int → number, String → string.
 */
export interface RunFlywheelLoopResponse {
  agentId: number;
  agentName: string;
  /** UUID string — the newly created session-annotator session. */
  annotatorSessionId: string;
  windowHours: number;
  max: number;
  /** Always `"triggered"` on 202. */
  status: 'triggered';
  note: string;
}

/**
 * `POST /api/flywheel/agents/{agentId}/run-loop` — triggers an on-demand
 * opt loop for the given agent. Returns 202 immediately; the downstream
 * chain (session-annotator → attribution-dispatcher) runs async (~30s-2min).
 *
 * Query params `windowHours` and `max` are optional; BE defaults apply when
 * absent. Body is intentionally empty (params are query-string only).
 */
export const runFlywheelLoopForAgent = (
  agentId: number,
  params?: RunFlywheelLoopParams,
) => api.post<RunFlywheelLoopResponse>(`/flywheel/agents/${agentId}/run-loop`, null, { params });

// ───────────────── /api/flywheel/chain-runs (chain visibility) ─────────────
//
// FLYWHEEL-CHAIN-VISIBILITY — list recent annotator → dispatcher chain runs.
// Each row represents one on-demand "Run Opt Loop" invocation and surfaces
// the per-step state (annotator status / dispatcher status / opt event count)
// so operators can see the chain progress without polling sessions.
//
// BE returns a bare array of `ChainRunResult` (sorted startedAt DESC, BE
// applies its own default limit when `limit` is absent). FE handles 404 /
// empty response by rendering "no recent runs" — never throws.

/**
 * One annotator → dispatcher chain run. Field names mirror BE Jackson default
 * camelCase serialization; keep in lock-step with the BE record (FE-BE
 * contract footgun #6 per `.claude/rules/java.md`).
 *
 * Status mapping:
 *   annotatorStatus: `running` while annotator session live; `idle` once
 *     completed; `error` if the session ended in error state.
 *   dispatcherStatus: `not_fired` when annotator produced no eligible patterns
 *     (chain stops, dispatcher never spawned); `pending` when annotator still
 *     running so dispatcher hasn't been triggered yet; `running` / `idle` /
 *     `error` mirror its session state once spawned. `null` covers the
 *     pre-dispatch window where BE has emitted the row but not yet classified
 *     the dispatcher state — FE treats `null` the same as `pending`.
 *
 * `optEventCount`: -1 = unknown (chain still running / dispatcher session
 * not yet inspected); 0 = no OptimizationEvents produced; N>0 = N events.
 */
export interface ChainRunResult {
  agentId: number;
  agentName: string;
  annotatorSessionId: string;
  dispatcherSessionId: string | null;
  annotatorStatus: 'idle' | 'error' | 'running';
  dispatcherStatus:
    | 'idle'
    | 'error'
    | 'running'
    | 'not_fired'
    | 'pending'
    | null;
  optEventCount: number;
  /** ISO-8601. */
  startedAt: string;
  /** ISO-8601; null while chain still running. */
  completedAt: string | null;
}

export interface ListChainRunsParams {
  /** Filter by agent id (optional; absent = all agents). */
  agentId?: number;
  /** Cap the number of rows returned (BE default applies when absent). */
  limit?: number;
}

/**
 * `GET /api/flywheel/chain-runs` — returns up to `limit` recent
 * annotator → dispatcher chain runs sorted by `startedAt` DESC.
 *
 * FE callers tolerate 404 / 5xx by rendering "no recent runs" — see
 * `FlywheelChainRuns` for the consumer.
 */
export const getFlywheelChainRuns = (params?: ListChainRunsParams) =>
  api.get<ChainRunResult[]>('/flywheel/chain-runs', { params });
