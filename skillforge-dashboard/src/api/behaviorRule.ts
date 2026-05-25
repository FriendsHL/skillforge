import api from './index';

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — REST client for behavior_rule A/B run / promote /
 * latest-run endpoints (tech-design §4).
 *
 * <p>Mirrors the BE {@code BehaviorRuleAbRunResponse} record field-by-field
 * (Jackson default camelCase). Per {@code .claude/rules/java.md} footgun #6:
 * keep grep-able with the BE DTO so the FE-BE contract stays auditable.
 *
 * <p>BE → FE type mapping reminder:
 * <pre>
 *   Java String           → string
 *   Java Long/Integer/int → number
 *   Java Double           → number
 *   Java Boolean (boxed)  → boolean | null
 *   Java Instant          → ISO-8601 string
 *   Java nullable boxed   → T | null
 * </pre>
 *
 * <p>URL note: paths here are RELATIVE to {@code api.baseURL = '/api'} (see
 * src/api/index.ts line 4). BE routes are mounted at
 * {@code /api/behavior-rules/versions/*} — the FE wrappers therefore omit the
 * {@code /api} prefix to avoid {@code /api/api/...} doubling.
 */

/**
 * Mirrors BE {@code BehaviorRuleAbRunEntity.STATUS_*} constants:
 *   PENDING / RUNNING / COMPLETED / FAILED / SUPERSEDED
 */
export type BehaviorRuleAbRunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'SUPERSEDED';

/**
 * Mirrors BE {@code BehaviorRuleAbRunEntity.KIND_*} constants:
 *   with_vs_without — V1 default (rule on vs rule off)
 *   variant_a_vs_b  — V2 backlog (rule_text variant A vs B)
 */
export type BehaviorRuleAbRunKind = 'with_vs_without' | 'variant_a_vs_b';

/**
 * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 — closed enum of 5 agent roles used by
 * {@code BehaviorRuleAbEvalService} to partition the dataset into target
 * (matches owner agent role) vs regression (general subset) buckets.
 *
 * <p>Mirrors BE {@code AgentRoleConstants}:
 * <pre>
 *   general        — fallback / generic benchmark scenarios
 *   code           — Code Agent
 *   design         — Design Agent
 *   research       — Research Agent
 *   main_assistant — Main Assistant
 * </pre>
 *
 * <p>Note: {@link BehaviorRuleAbRun#ownerAgentRole} is typed {@code string | null}
 * (not {@code AgentRole | null}) so the FE degrades gracefully if BE adds a
 * new role before the FE union is updated. The {@link roleLabel} / {@link roleColor}
 * helpers also accept {@code string} for the same reason.
 */
export type AgentRole =
  | 'general'
  | 'code'
  | 'design'
  | 'research'
  | 'main_assistant';

/**
 * Human-readable label for an agent role tag. Accepts {@code string} (not
 * {@link AgentRole}) so callers can pipe through {@code run.ownerAgentRole}
 * without a type assertion when BE returns a role the FE union doesn't know
 * yet — unknown values render verbatim as a fallback.
 */
export function roleLabel(role: string | null | undefined): string {
  switch (role) {
    case 'general':
      return 'General';
    case 'code':
      return 'Code';
    case 'design':
      return 'Design';
    case 'research':
      return 'Research';
    case 'main_assistant':
      return 'Main Assistant';
    default:
      return role ?? '';
  }
}

/**
 * AntD preset color for an agent role Tag. Map per tech-design §5.2:
 * <pre>
 *   design         → magenta
 *   code           → cyan
 *   research       → orange
 *   main_assistant → blue
 *   general        → default
 * </pre>
 *
 * <p>Unknown / null / undefined → 'default'. Same defensive {@code string}
 * signature as {@link roleLabel}.
 */
export function roleColor(role: string | null | undefined): string {
  switch (role) {
    case 'design':
      return 'magenta';
    case 'code':
      return 'cyan';
    case 'research':
      return 'orange';
    case 'main_assistant':
      return 'blue';
    case 'general':
      return 'default';
    default:
      return 'default';
  }
}

/**
 * Per-scenario A/B row — mirrors BE {@code AbScenarioResult} record
 * (tech-design §3.5). BE serializes
 * {@code BehaviorRuleAbRunEntity.abScenarioResultsJson} into this list and
 * exposes it on the response DTO as {@code scenarioResults}. Status mirrors
 * {@code ScenarioRunResult.status} enum; {@code oracleScore} is the judge
 * composite score 0..1.
 *
 * <p>{@code isTarget} is an optional discriminator that BE may set when the
 * scenario's {@code rule_trigger_hints} intersected the candidate version's
 * {@code target_trigger_tags} (D1 subset split). Omitted by BE → treated as
 * regression by the drawer's row-highlight logic.
 */
export interface BehaviorRuleAbScenarioResult {
  scenarioId: string;
  scenarioName: string;
  baseline: {
    status: 'PASS' | 'FAIL' | 'TIMEOUT';
    oracleScore: number;
  };
  candidate: {
    status: 'PASS' | 'FAIL' | 'TIMEOUT';
    oracleScore: number;
  };
  isTarget?: boolean;
}

/**
 * Wire shape of {@code BehaviorRuleAbRunResponse} (tech-design §4). Every
 * scoring / counting field is nullable on the wire because PENDING / RUNNING
 * runs have not yet populated them, and {@code targetDeltaPp} is also null in
 * fallback mode (INV-4 — target subset empty → only regression delta is
 * meaningful).
 *
 * <p>{@code dualCriteriaSatisfied} is a server-derived boolean per INV-5
 * (FE does NOT recompute the gate — single source of truth on BE so we don't
 * drift when V2 changes thresholds).
 */
export interface BehaviorRuleAbRun {
  id: string;
  agentId: string;
  candidateVersionId: string;
  status: BehaviorRuleAbRunStatus;
  abRunKind: BehaviorRuleAbRunKind;
  /** Overall baseline pass-rate weighted across target + regression subsets. */
  baselinePassRate: number | null;
  /** Overall candidate pass-rate weighted across target + regression subsets. */
  candidatePassRate: number | null;
  /** Legacy single delta retained for FE backwards-compat (V4 era). */
  deltaPassRate: number | null;
  /**
   * Per-subset deltas (D4 dual-criteria). Both are nullable until the run is
   * COMPLETED; {@code targetDeltaPp} stays null in fallback mode (INV-4).
   */
  targetDeltaPp: number | null;
  regressionDeltaPp: number | null;
  targetCount: number | null;
  regressionCount: number | null;
  /** Soft FK to {@code t_eval_dataset_version.id}. */
  datasetVersionId: string | null;
  /** True after manual promote landed (mirrors entity column). */
  promoted: boolean | null;
  /** Populated when status=FAILED; surfaced in the drawer / row tooltip. */
  failureReason: string | null;
  /** ISO-8601 (BE Instant) — null while still PENDING. */
  startedAt: string | null;
  /** ISO-8601 (BE Instant) — null until status transitions to terminal. */
  completedAt: string | null;
  /**
   * Server-derived per INV-5 — `(targetDelta >= +10pp OR targetDelta IS NULL)
   * AND regressionDelta >= -3pp`. Null while status is non-terminal.
   */
  dualCriteriaSatisfied: boolean | null;
  /**
   * Per-scenario A/B detail (tech-design §3.5). Null when the run hasn't
   * completed yet, when {@code abScenarioResultsJson} fails to parse on BE,
   * or for legacy rows pre-{@code BehaviorRuleAbEvalService} that never
   * populated the column. Drawer renders the per-scenario table only when
   * the list is non-empty; otherwise falls back to aggregate-only view.
   */
  scenarioResults: BehaviorRuleAbScenarioResult[] | null;
  /**
   * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (D5 / AC-8) — the role resolved from
   * the candidate version's owner agent (via {@code AgentRoleResolver}) at
   * the moment {@code runAsync} executed. Used by FE row + drawer to render
   * an owner-role Tag so operators can tell at a glance which role's subset
   * split produced the deltas (target subset = scenarios tagged with this
   * role; regression subset = scenarios tagged 'general' minus target).
   *
   * <p>Wire shape: {@code String} (one of {@link AgentRole} closed values,
   * or any future role BE adds). Typed {@code string | null} not
   * {@code AgentRole | null} so the FE degrades gracefully if BE rolls out a
   * new role before the FE union is updated — the {@link roleLabel} /
   * {@link roleColor} helpers render unknown values verbatim with a default
   * color rather than crashing.
   *
   * <p>{@code null} for legacy rows pre-V1.1 that never resolved an owner
   * role (BE pure-mapper {@code from()} writes null when caller omits role).
   */
  ownerAgentRole: string | null;
}

/** Response shape for {@code POST /api/behavior-rules/versions/{id}/run-ab}. */
export interface BehaviorRuleAbRunStartResponse {
  abRunId: string;
}

/** Response shape for {@code POST /api/behavior-rules/versions/{id}/promote}. */
export interface BehaviorRuleAbPromoteResponse {
  status: 'promoted' | 'noop';
  reason?: string;
}

/**
 * Request body for {@code POST /api/behavior-rules/versions/{id}/run-ab}.
 * {@code datasetVersionId} is optional — BE resolves agent default when omitted
 * (tech-design §3.2 startAbForVersion). Empty string also accepted as "use
 * default" — FE passes {@code undefined} for the same effect.
 */
export interface RunAbRequest {
  datasetVersionId?: string;
}

/**
 * {@code POST /api/behavior-rules/versions/{versionId}/run-ab} — start a new
 * dual-criteria A/B run for a candidate behavior_rule version. Returns the
 * newly-created {@code abRunId} immediately; the run executes asynchronously
 * via {@code BehaviorRuleAbEvalService.runAsync} and emits WS progress events
 * (ab_running → ab_completed | ab_failed).
 *
 * <p>BE behaviour:
 * - 400 if version not in {@code candidate} status
 * - 400 if no default dataset for the version's agent and no override supplied
 * - 200 + new {@code abRunId} on success (any existing PENDING / RUNNING run
 *   for the same candidate is marked SUPERSEDED — INV-6)
 */
export const runBehaviorRuleAb = (versionId: string, datasetVersionId?: string) =>
  api.post<BehaviorRuleAbRunStartResponse>(
    `/behavior-rules/versions/${versionId}/run-ab`,
    { datasetVersionId } satisfies RunAbRequest,
  );

/**
 * {@code POST /api/behavior-rules/versions/{versionId}/promote} — manual
 * promote (dual-criteria gate). BE validates that the version's latest
 * COMPLETED ab_run satisfies INV-5 before flipping the version to {@code active}
 * and retiring the prior active version (INV-6: re-promote on active version
 * returns {@code {status: 'noop'}}).
 *
 * <p>BE behaviour:
 * - 400 if dual-criteria not satisfied (returns {@code reason} explaining
 *   which subset failed — surfaced in toast via the message.error path)
 * - 200 + {@code {status: 'promoted', reason: <versionId>}} on first promote
 * - 200 + {@code {status: 'noop', reason: 'already active'}} on re-promote
 */
export const promoteBehaviorRule = (versionId: string) =>
  api.post<BehaviorRuleAbPromoteResponse>(
    `/behavior-rules/versions/${versionId}/promote`,
    {},
  );

/**
 * {@code GET /api/behavior-rules/versions/{versionId}/latest-ab-run} — returns
 * the most-recent ab_run (any status) for the candidate version. Used by the
 * timeline row Actions column to decide which badge / button set to render:
 *
 * <pre>
 *   null              → no ab_run yet (auto-trigger may not have fired)  → Retry button
 *   status=PENDING    → "queued"
 *   status=RUNNING    → spinner "running A/B..."
 *   status=COMPLETED  → baseline/candidate/delta + Promote (or disabled+tooltip)
 *   status=FAILED     → "Retry A/B" + failureReason tooltip
 *   status=SUPERSEDED → fall back to next call (BE returns newest-first)
 * </pre>
 *
 * <p>BE behaviour:
 * - 404 with empty body or 200 + {@code null} when no ab_run exists for the
 *   version (BE may return either; callers must treat as "no run yet")
 * - 200 + {@code BehaviorRuleAbRunResponse} otherwise
 */
export const getLatestBehaviorRuleAbRun = (versionId: string) =>
  api.get<BehaviorRuleAbRun | null>(
    `/behavior-rules/versions/${versionId}/latest-ab-run`,
  );

/**
 * Convenience wrappers grouped as a namespace object so callers can do
 * {@code behaviorRuleApi.runAb(...)} / {@code behaviorRuleApi.promote(...)}
 * (mirrors the tech-design §5.1 example signatures).
 */
export const behaviorRuleApi = {
  runAb: runBehaviorRuleAb,
  promote: promoteBehaviorRule,
  latestAbRun: getLatestBehaviorRuleAbRun,
};

// ───────────────────────── WS event shape ─────────────────────────

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — WS payload pushed by
 * {@code BehaviorRuleAbEvalService.broadcastStage} (tech-design §3.2). Type
 * literal narrows the discriminator in the OptimizationEvents WS handler.
 *
 * <p>Subtypes (one per stage transition):
 * - {@code ab_running}    — emitted on RUNNING flip in runAsync()
 * - {@code ab_completed}  — emitted on COMPLETED flip (deltas populated)
 * - {@code ab_failed}     — emitted on FAILED flip (failureReason populated)
 *
 * <p>{@code candidateVersionId} is the key the FE keys ['behavior-rule-ab',
 * versionId] off; receiving any of the 3 sub-events triggers a refetch of
 * the latestAbRun query for that version.
 */
export type BehaviorRuleAbRunWsEvent = 'ab_running' | 'ab_completed' | 'ab_failed';

export interface BehaviorRuleAbRunUpdatedMessage {
  type: 'behavior_rule_ab_run_updated';
  event: BehaviorRuleAbRunWsEvent;
  abRunId: string;
  candidateVersionId: string;
  status: BehaviorRuleAbRunStatus;
}
