import api from './client';

// Skill API
// P1 (B-1): all write endpoints take `userId` query param, mirroring chat API
// (no `ownerId` from the FE). BE writes SkillEntity.ownerId from the userId.
export const getSkills = (isSystem?: boolean) =>
  api.get('/skills', isSystem === undefined ? undefined : { params: { isSystem } });

/**
 * P1-D rescan report — counts produced by `POST /api/skills/rescan` after the
 * backend reconciles the on-disk skills directory with the registry.
 */
export interface RescanReport {
  /** Skills newly inserted into the registry from disk. */
  created: number;
  /** Existing skills whose metadata or path changed. */
  updated: number;
  /** Skills present in the registry but whose artifact directory is gone. */
  missing: number;
  /** Skills whose on-disk artifact failed validation (corrupt SKILL.md, etc.). */
  invalid: number;
  /** Skills shadowed by a same-name peer earlier in the resolution order. */
  shadowed: number;
  /** Same-name duplicates that the rescan auto-disabled to enforce uniqueness. */
  disabledDuplicates: number;
}

/** P1-D: trigger a synchronous filesystem rescan and return the reconciliation report. */
export const rescanSkills = () => api.post<RescanReport>('/skills/rescan');

/**
 * SKILL-IMPORT-BATCH — single item in any of the four
 * {@link BatchImportResult} buckets. Field optionality reflects which bucket
 * the item belongs to:
 *  - imported / updated → name + skillPath set
 *  - skipped → name + reason set
 *  - failed → name + error set
 */
export interface BatchImportResultItem {
  name: string;
  skillPath?: string;
  reason?: string;
  error?: string;
}

/**
 * SKILL-IMPORT-BATCH — response of {@code POST /api/skills/rescan-marketplace}.
 * One subdir failure does not abort the whole batch, so callers should render
 * partial-success state across all four buckets.
 */
export interface BatchImportResult {
  imported: BatchImportResultItem[];
  updated: BatchImportResultItem[];
  skipped: BatchImportResultItem[];
  failed: BatchImportResultItem[];
}

/**
 * SKILL-IMPORT-BATCH — trigger a marketplace rescan + batch register.
 * `userId` mirrors uploadSkill / forkSkill etc. — BE writes ownerId from the
 * validated userId and never accepts ownerId from FE.
 */
export const rescanMarketplace = (source: string, userId: number) =>
  api.post<BatchImportResult>(
    `/skills/rescan-marketplace?source=${encodeURIComponent(source)}`,
    null,
    { params: { userId } },
  );
export const getBuiltinSkills = () => api.get('/skills/builtin');
export const uploadSkill = (file: File, userId: number) => {
  const form = new FormData();
  form.append('file', file);
  // BE controller now reads userId via @RequestParam (B-1 收口) — see plan §8.
  return api.post('/skills/upload', form, { params: { userId } });
};
export const deleteSkill = (id: number, userId: number) =>
  api.delete(`/skills/${id}`, { params: { userId } });
export const getSkillDetail = (id: number | string) => api.get(`/skills/${id}/detail`);
export const toggleSkill = (id: number, enabled: boolean, userId: number) =>
  api.put(`/skills/${id}/toggle`, null, { params: { enabled, userId } });

export interface SkillVersionEntry {
  id: number;
  name: string;
  semver?: string;
  parentSkillId?: number;
  enabled: boolean;
  usageCount: number;
  successCount: number;
  source?: string;
  createdAt?: string;
}

export const getSkillVersionChain = (id: number | string) =>
  api.get<SkillVersionEntry[]>(`/skills/${id}/versions`);

/**
 * SKILL-DASHBOARD-POLISH-V2 §I — node in the per-skill version tree. The BE
 * walks `parentSkillId` upward (capped at depth 10) for ancestors and
 * `findByParentSkillId` recursively downward for descendants, attaching the
 * latest `t_skill_eval_history.composite_score` per node so the drawer can
 * render scores inline.
 */
export interface SkillVersionTreeNode {
  id: number;
  name: string;
  semver?: string;
  enabled: boolean;
  /** Latest `t_skill_eval_history.composite_score` (0-100); null when never evaluated. */
  latestScore?: number | null;
  /** ISO-8601 createdAt for the per-line "when created" caption. */
  createdAt?: string;
  parentSkillId?: number | null;
  /** BE flags the rooted-at node so the FE can mark "← current" in the tree. */
  isCurrent?: boolean;
}

/**
 * Tree response — `ancestors` runs root→parent (excluding `current`),
 * `descendants` is the recursive children list (each entry's
 * `parentSkillId` lets the FE re-build nesting). `current` is always
 * present and `isCurrent === true`.
 */
export interface SkillVersionTreeResponse {
  ancestors: SkillVersionTreeNode[];
  current: SkillVersionTreeNode;
  descendants: SkillVersionTreeNode[];
}

export const getSkillVersionTree = (skillId: number | string, userId: number) =>
  api.get<SkillVersionTreeResponse>(`/skills/${skillId}/version-tree`, {
    params: { userId },
  });

export const forkSkill = (id: number | string, userId: number) =>
  api.post<SkillVersionEntry>(`/skills/${id}/fork`, null, { params: { userId } });

export const recordSkillUsage = (id: number | string, success: boolean) =>
  api.post(`/skills/${id}/usage?success=${success}`);

export interface SkillAbRun {
  id: string;
  parentSkillId: number;
  candidateSkillId: number;
  agentId: string;
  baselineEvalRunId?: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  baselinePassRate?: number;
  candidatePassRate?: number;
  deltaPassRate?: number;
  promoted: boolean;
  /** SKILL-DASHBOARD-POLISH §D — set true when promoted via manual override
   *  (BE `promote-manual` endpoint). Lets the FE label the row distinct from
   *  auto-promote driven by threshold pass.
   */
  manuallyPromoted?: boolean;
  skipReason?: string;
  failureReason?: string;
  /** Raw JSON column from `t_skill_ab_run.ab_scenario_results_json` —
   *  serialized List<AbScenarioResult>. EvolutionDetailPanel parses + renders
   *  the per-scenario baseline-vs-candidate delta table from this. Absent
   *  while RUNNING/PENDING.
   */
  abScenarioResultsJson?: string;
  startedAt?: string;
  completedAt?: string;
  /**
   * EVAL-DATASET-LAYER **V2 forward-compat hook** — optional. Skill surface
   * is V2 backlog (PRD V2 §"不在范围": "skill / behavior_rule surface 的
   * dataset (本包 V1 只 prompt surface)"). SkillAbRunEntity does NOT
   * carry a `dataset_version_id` column in V1 and SkillController.toAbRunMap
   * does NOT emit this field — so this property is always undefined in V1.
   *
   * The interface field is preserved so that when V2 widens the entity +
   * controller, the FE picks up the value with zero TS changes.
   * Do NOT add UI that relies on this being populated in V1.
   */
  datasetVersionId?: string | null;
  /**
   * V2 forward-compat label (FE-composed via lazy fetch when the BE emits
   * datasetVersionId). Always undefined in V1; see datasetVersionId above.
   */
  datasetVersionLabel?: string | null;
}

export interface StartAbTestRequest {
  candidateSkillId: number;
  agentId?: string;
  baselineEvalRunId?: string;
  triggeredByUserId?: number;
  /**
   * EVAL-DATASET-LAYER **V2 forward-compat hook** — optional. Skill A/B
   * start endpoint (`POST /skills/{id}/abtest`) does NOT currently parse
   * this field; it's accepted in the TS type so that a future V2 BE widen
   * (when SkillController.startAbTest is rewired to honor it) doesn't
   * require a contemporaneous FE deploy.
   *
   * V1 behavior: sending this in the body is a no-op (Jackson ignores it),
   * and the run uses the legacy held_out + ephemeral scenarios path.
   */
  datasetVersionId?: string;
}

export const startSkillAbTest = (parentSkillId: number | string, req: StartAbTestRequest) =>
  api.post<SkillAbRun>(`/skills/${parentSkillId}/abtest`, req);

export const getSkillAbTests = (skillId: number | string) =>
  api.get<SkillAbRun[]>(`/skills/${skillId}/abtest`);

export const getSkillAbTest = (abRunId: string) =>
  api.get<SkillAbRun>(`/skills/abtest/${abRunId}`);

/**
 * SKILL-DASHBOARD-POLISH §D — manual override for an A/B run that did not
 * auto-promote (delta < 15pp or candidate < 40%). BE writes `manuallyPromoted=true`
 * and runs the same promotion side-effects as the auto path (parent disable +
 * candidate enable). 4xx if the run is not COMPLETED or already promoted.
 */
export const manualPromoteAbRun = (abRunId: string, triggeredByUserId: number) =>
  api.post<SkillAbRun>(`/skills/abrun/${abRunId}/promote-manual`, { triggeredByUserId });

/**
 * SKILL-DASHBOARD-POLISH §D — reverse promote. Disables the candidate and
 * re-enables the parent skill. BE returns the updated candidate `SkillEntity`
 * (semver / parentSkillId / enabled mutated). 4xx when the skill has no parent.
 */
export const rollbackSkill = (candidateSkillId: number, triggeredByUserId: number) =>
  api.post<SkillVersionEntry>(`/skills/${candidateSkillId}/rollback`, { triggeredByUserId });

/**
 * SKILL-DASHBOARD-POLISH §B — fetches the on-disk SKILL.md for the given
 * skill so the Evolution Detail tab can render a left-vs-right diff against
 * the candidate's `improvedSkillMd`. `path` is the backing file path and may
 * be null when the skill has no artifact directory yet.
 */
export interface SkillMdResponse {
  content: string;
  path: string | null;
}

export const getSkillMd = (skillId: number, userId: number) =>
  api.get<SkillMdResponse>(`/skills/${skillId}/skill-md`, { params: { userId } });

// V2.5 — generic file tree (recursive list under skillPath, .clawhub / _meta.json filtered).
export interface SkillFileEntry {
  path: string;        // relative to skillPath (forward-slash form)
  size: number;
  mtime?: string;
}
export interface SkillFilesResponse {
  path: string;        // absolute skill dir
  files: SkillFileEntry[];
  error?: string;
}
export const getSkillFiles = (skillId: number | string, userId: number) =>
  api.get<SkillFilesResponse>(`/skills/${skillId}/files`, { params: { userId } });

export interface SkillFileContentResponse {
  path: string;
  content: string;
  size?: number;
  mtime?: string;
  binary?: boolean;
}
export const getSkillFileContent = (
  skillId: number | string,
  filePath: string,
  userId: number
) =>
  api.get<SkillFileContentResponse>(`/skills/${skillId}/files/content`, {
    params: { path: filePath, userId },
  });

// ─── Skill Evolution (P1-4) ─────────────────────────────────────────────────

export interface SkillEvolutionRun {
  id: string;
  skillId: number;
  forkedSkillId?: number;
  abRunId?: string;
  agentId?: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED';
  successRateBefore?: number;
  usageCountBefore?: number;
  improvedSkillMd?: string;
  /** Not yet populated by the backend — reserved for future reasoning trace. */
  evolutionReasoning?: string;
  failureReason?: string;
  triggeredByUserId?: number;
  createdAt?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface StartEvolutionRequest {
  agentId: string;
  triggeredByUserId?: number;
}

export const startSkillEvolution = (skillId: number, req: StartEvolutionRequest) =>
  api.post<SkillEvolutionRun>(`/skills/${skillId}/evolve`, req);

export const getSkillEvolutions = (skillId: number) =>
  api.get<SkillEvolutionRun[]>(`/skills/${skillId}/evolution`);

// Reserved for future detail view
export const getSkillEvolution = (evolutionRunId: string) =>
  api.get<SkillEvolutionRun>(`/skills/evolution/${evolutionRunId}`);

// ─── SKILL-EVOLVE-LOOP Phase 6: per-skill eval history & manual evaluate ─────

/**
 * SKILL-EVOLVE-LOOP — one row in `t_skill_eval_history`.
 *
 * Wire shape mirrors the BE projection (5-dim score + triggered source).
 * BE returns rows ordered by `createdAt DESC`; the dashboard reverses to
 * ASC when feeding the time-series chart so the curve reads left → right.
 *
 * `triggeredBy` is constrained on the BE to `'manual'` (POST /evaluate)
 * or `'scheduled'` (Phase 3 cron).
 */
export interface EvalHistoryEntry {
  id: number;
  skillId: number;
  evalRunId: string | null;
  compositeScore: number;
  qualityScore: number | null;
  efficiencyScore: number | null;
  latencyScore: number | null;
  costScore: number | null;
  /**
   * EVAL-V2 M4_V2 — per-dimension measurement status. When a sub-dim is
   * `'not_measured'` (e.g. latency without a configured threshold), its
   * score is `null` and FE renders "not measured" rather than 100/0.
   * Optional for backward compat with M4_V1 payloads.
   */
  dimensionStatus?: Record<string, 'measured' | 'not_measured'>;
  triggeredBy: 'manual' | 'scheduled';
  createdAt: string;
}

/**
 * Result of a single-skill manual evaluation. Mirrors the BE response
 * (5-dim score + the `t_skill_eval_history` row id when persisted).
 */
export interface SkillEvaluateResult {
  historyId?: number;
  evalRunId?: string | null;
  compositeScore: number;
  qualityScore: number | null;
  efficiencyScore: number | null;
  latencyScore: number | null;
  costScore: number | null;
  /** See {@link EvalTaskItem.dimensionStatus} — same M4_V2 semantics. Mirrored
   *  here so the three score-bearing wire shapes (task item / compare entry /
   *  history entry / evaluate result) carry the field consistently, even
   *  though no current UI surface reads sub-dim status from this response. */
  dimensionStatus?: Record<string, 'measured' | 'not_measured'>;
}

/**
 * SKILL-EVOLVE-LOOP — Phase 2 single-skill direct evaluate.
 * Lands a row in `t_skill_eval_history` with `triggered_by='manual'`,
 * returns the resulting 5-dim score so the caller can flash it inline.
 *
 * Wire contract (per BE-1 finalized 2026-05-08):
 *   POST /api/skills/{id}/evaluate?userId=X&agentId=Y&datasetId=Z
 *   - `agentId` is **required** — blank yields 400 from the BE.
 *   - `datasetId` is optional in V1 (BE accepts null); reserved for V2
 *     when per-agent dataset selection ships.
 */
export const evaluateSkill = (
  skillId: number,
  userId: number,
  agentId: number | string,
  datasetId?: string,
) =>
  api.post<SkillEvaluateResult>(`/skills/${skillId}/evaluate`, null, {
    // Spread datasetId only when set so we don't send an empty string the
    // BE then has to special-case. `agentId` is always present.
    params: {
      userId,
      agentId,
      ...(datasetId ? { datasetId } : {}),
    },
  });

/**
 * Fetch the most-recent N eval history rows for a skill (BE returns
 * `createdAt DESC`). The dashboard reverses for charts.
 */
export const getSkillEvalHistory = (skillId: number, userId: number, limit = 20) =>
  api.get<EvalHistoryEntry[]>(`/skills/${skillId}/eval-history`, { params: { userId, limit } });
