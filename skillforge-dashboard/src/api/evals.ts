import api from './client';
import type { ImprovementStartResult } from './improve';

// ─── Scenario Drafts ─────────────────────────────────────────────────────────

export interface EvalScenarioDraft {
  id: string;
  agentId: string;
  name: string;
  description?: string;
  category: string;
  split: string;
  task: string;
  oracleType: string;
  oracleExpected?: string;
  status: 'draft' | 'active' | 'discarded';
  extractionRationale?: string;
  createdAt: string;
  reviewedAt?: string;
}

export const getScenarioDrafts = (agentId: string | number) =>
  api.get<EvalScenarioDraft[]>(`/agents/${agentId}/scenario-drafts`).then(r => r.data);

export const triggerScenarioExtraction = (agentId: string | number) =>
  api.post(`/agents/${agentId}/scenario-drafts`).then(r => r.data);

export const reviewScenarioDraft = (
  id: string,
  action: 'approve' | 'discard',
  edits?: { name?: string; task?: string; oracleExpected?: string },
) =>
  api.patch<EvalScenarioDraft>(`/agents/scenario-drafts/${id}`, { action, ...edits }).then(r => r.data);

// ─── Eval Pipeline ────────────────────────────────────────────────────────────
export interface EvalTaskSummary {
  id: string;
  agentDefinitionId: string;
  status: string;
  scenarioCount?: number | null;
  totalScenarios?: number | null;
  passCount?: number | null;
  failCount?: number | null;
  compositeAvg?: number | null;
  qualityAvg?: number | null;
  efficiencyAvg?: number | null;
  latencyAvg?: number | null;
  costAvg?: number | null;
  overallPassRate?: number | null;
  avgOracleScore?: number | null;
  primaryAttribution?: string | null;
  attributionSummary?: string | null;
  improvementSuggestion?: string | null;
  analysisSessionId?: string | null;
  datasetFilter?: string | null;
  itemCount?: number | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
}

export interface EvalTaskItem {
  id: number;
  taskId: string;
  scenarioId: string;
  scenarioSource?: string | null;
  sessionId?: string | null;
  rootTraceId?: string | null;
  compositeScore?: number | null;
  qualityScore?: number | null;
  efficiencyScore?: number | null;
  latencyScore?: number | null;
  costScore?: number | null;
  /**
   * EVAL-V2 M4_V2 — per-dimension measurement status. Sub-dims can return
   * `'not_measured'` when no threshold/baseline was configured (latency
   * without `latency_threshold_ms`, cost without `cost_threshold_usd`),
   * in which case the score field is `null` and composite is normalized
   * over the *measured* dims only. Optional for backward compat with
   * pre-M4_V2 payloads (BE migration / legacy rows).
   */
  dimensionStatus?: Record<string, 'measured' | 'not_measured'>;
  costUsd?: number | null;
  scoreFormulaVersion?: string | null;
  scoreBreakdownJson?: string | null;
  status: string;
  loopCount?: number | null;
  toolCallCount?: number | null;
  latencyMs?: number | null;
  attribution?: string | null;
  judgeRationale?: string | null;
  agentFinalOutput?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt?: string | null;
}

export interface EvalTaskAnalysisSession {
  sessionId: string;
  analysisType: string;
  taskId: string;
  itemId?: number | null;
  scenarioId?: string | null;
  title?: string | null;
  runtimeStatus?: string | null;
  messageCount: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface EvalTaskCompareEntry {
  taskId: string;
  status: string;
  compositeScore?: number | null;
  qualityScore?: number | null;
  efficiencyScore?: number | null;
  latencyScore?: number | null;
  costScore?: number | null;
  /** See {@link EvalTaskItem.dimensionStatus} — same M4_V2 semantics. */
  dimensionStatus?: Record<string, 'measured' | 'not_measured'>;
  costUsd?: number | null;
  scoreFormulaVersion?: string | null;
  attribution?: string | null;
  latencyMs?: number | null;
  loopCount?: number | null;
  toolCallCount?: number | null;
  rootTraceId?: string | null;
  agentFinalOutput?: string | null;
}

export interface EvalTaskCompareRow {
  scenarioId: string;
  entries: EvalTaskCompareEntry[];
  scoreDelta: number;
  outputDiffers: boolean;
}

export interface EvalTaskCompareResult {
  taskCount: number;
  scenarioCount: number;
  tasks: EvalTaskSummary[];
  rows: EvalTaskCompareRow[];
}

export interface EvalAnnotation {
  id: number;
  taskItemId: number;
  annotatorId: number;
  originalScore?: number | null;
  correctedScore?: number | null;
  correctedExpected?: string | null;
  status: 'pending' | 'applied';
  createdAt?: string | null;
  appliedAt?: string | null;
  taskId?: string | null;
  scenarioId?: string | null;
  scenarioSource?: string | null;
  itemStatus?: string | null;
  attribution?: string | null;
  rootTraceId?: string | null;
  judgeRationale?: string | null;
  agentFinalOutput?: string | null;
  agentDefinitionId?: string | null;
  taskStatus?: string | null;
}

export const getEvalTasks = () => api.get<EvalTaskSummary[]>('/eval/tasks');
export const getEvalTask = (id: string) => api.get<EvalTaskSummary>(`/eval/tasks/${id}`);
export const getEvalTaskItems = (id: string) => api.get<EvalTaskItem[]>(`/eval/tasks/${id}/items`);
export const getEvalTaskAnalysisSessions = (id: string, userId: number) =>
  api.get<EvalTaskAnalysisSession[]>(`/eval/tasks/${id}/analysis-sessions`, { params: { userId } });
export const compareEvalTasks = (ids: string[]) =>
  api.post<EvalTaskCompareResult>('/eval/tasks/compare', null, { params: { ids: ids.join(',') } });
export const getEvalAnnotations = (status?: 'pending' | 'applied') =>
  api.get<EvalAnnotation[]>('/eval/annotations', { params: status ? { status } : undefined });
export const createEvalAnnotation = (data: {
  taskItemId: number;
  annotatorId: number;
  correctedScore?: number | null;
  correctedExpected?: string | null;
}) => api.post<EvalAnnotation>('/eval/annotations', data);
export const updateEvalAnnotation = (
  id: number,
  data: { status: 'pending' | 'applied'; correctedScore?: number | null; correctedExpected?: string | null },
) => api.patch<EvalAnnotation>(`/eval/annotations/${id}`, data);
export const triggerEvalTask = (agentId: string, userId = 1) =>
  api.post('/eval/tasks', { agentId, userId });
export const applyEvalTaskImprovement = (taskId: string, userId = 1) =>
  api.post<ImprovementStartResult>(`/eval/tasks/${taskId}/apply-improvement`, { userId });

// Legacy run endpoints retained while the remaining FE surfaces migrate.
export const getEvalRuns = () => api.get('/eval/runs');
export const getEvalRun = (id: string) => api.get(`/eval/runs/${id}`);
export const triggerEvalRun = (agentId: string, userId = 1) =>
  api.post('/eval/runs', { agentId, userId });
export const getEvalScenarios = () => api.get('/eval/scenarios');

// EVAL-V2 M2: one row in a multi-turn conversation case.
//   role: user | assistant | system | tool
//   content: turn text. Assistant turns store the literal '<placeholder>'
//   in the spec; runtime fills in the agent's actual response in-memory only.
export interface ConversationTurn {
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
}

// EVAL-V2 M0: per-agent dataset = EvalScenarioEntity rows for that agent.
export interface EvalDatasetScenario {
  id: string;
  agentId: string;
  version?: number;
  parentScenarioId?: string | null;
  name: string;
  description?: string;
  category: string;
  split: string;
  task: string;
  oracleType: string;
  oracleExpected?: string;
  status: 'draft' | 'active' | 'discarded';
  sourceSessionId?: string;
  extractionRationale?: string;
  createdAt: string;
  reviewedAt?: string;
  /**
   * EVAL-V2 Q3: where this scenario was loaded from. Only populated for the
   * "Base" tab projection (synthesized from {@link BaseScenario.source}).
   * Per-agent EvalScenarioEntity rows always come from the DB.
   */
  source?: 'classpath' | 'home' | 'db';
  /**
   * EVAL-V2 M2: when present and non-empty, this case is multi-turn — the
   * detail drawer renders a conversation transcript instead of the single
   * task / oracleExpected view. Wire format on disk is the snake_case key
   * `conversation_turns`; the BE projection currently uses camelCase here
   * for FE consumption (the BE may add this field as it surfaces it).
   */
  conversationTurns?: ConversationTurn[];
  /**
   * EVAL-V2 M3b: optional rich-detail fields. Populated for "Base" scenarios
   * (classpath / home dir JSON via {@link BaseScenario}) where the on-disk
   * spec carries this metadata. Per-agent EvalScenarioEntity rows currently
   * don't persist these (M3b doesn't touch the entity schema), so they
   * remain undefined on the Agent tab — the drawer renders sections only
   * when the field is non-empty.
   */
  toolsHint?: string[];
  tags?: string[];
  /** File names only (no content). Surfaces what the scenario sets up. */
  setupFiles?: string[];
  maxLoops?: number;
  performanceThresholdMs?: number;
  /**
   * EVAL-DATASET-LAYER V1 (V109): closed-enum data source classifier.
   * Populated for rows post-V109 retroactive backfill; legacy data may
   * still arrive null until the BE projection surfaces the column.
   * Used by the source_type tab + Dataset composition policy.
   */
  sourceType?: 'benchmark' | 'session_derived' | 'manual' | null;
  /**
   * EVAL-DATASET-LAYER V1 (V109): source identifier (e.g. `gaia/lv1#001`,
   * `session:5f3f1923-...`, `manual:user-1/...`). Nullable; only newly
   * seeded scenarios carry this populated.
   */
  sourceRef?: string | null;
  /**
   * EVAL-DATASET-LAYER V1 (V109, wiki r2): closed-enum purpose tag —
   * orthogonal to sourceType. Aligns with SWE-bench regression-aware
   * categories.
   */
  purpose?: 'baseline_anchor' | 'regression' | 'ablation' | null;
  /**
   * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (V117) — closed-enum JSONB tag list
   * declaring which agent roles this scenario applies to (e.g.
   * {@code ['design']} for Design Agent dogfood, {@code ['general']} for
   * benchmark seeds). Consumed by {@code DatasetBrowser}'s role filter tab.
   *
   * <p>Optional in the TS interface for forward-compat: BE may omit the
   * field on older deploys before V117 lands, and the legacy per-agent
   * {@code /eval/scenarios} response did not surface it. Always access via
   * the optional-chain pattern {@code s.applicableAgentRoles?.includes(role)}
   * so missing data degrades to "no match" instead of TypeError.
   *
   * <p>Wire shape: bare string array (BE Jackson JSONB → JsonNode → List).
   * Typed as the open {@code string[]} not the closed {@code AgentRole[]}
   * so the FE renders correctly even if BE rolls out a new role before the
   * union type is updated.
   */
  applicableAgentRoles?: string[] | null;
}
export const getEvalDatasetScenarios = (
  agentId: string | number,
  params: {
    sourceType?: 'benchmark' | 'session_derived' | 'manual';
    purpose?: 'baseline_anchor' | 'regression' | 'ablation';
    sourceRef?: string;
  } = {},
) =>
  api.get<EvalDatasetScenario[]>('/eval/scenarios', { params: { agentId, ...params } });
export const createEvalScenarioFromTrace = (payload: {
  rootTraceId: string;
  scenarioId?: string;
  name?: string;
}) => api.post<EvalDatasetScenario>('/eval/scenarios/from-trace', payload);

export interface TraceImportCandidate {
  traceId: string;
  rootTraceId: string;
  sessionId: string;
  agentId?: string | null;
  agentName?: string | null;
  preview?: string | null;
  status: 'ok' | 'error' | 'running' | 'cancelled';
  tokenCount: number;
  llmCallCount: number;
  toolCallCount: number;
  reasonCodes: string[];
  startedAt?: string | null;
}

// Creates reviewable scenario drafts from selected production trace roots.
export const batchImportTracesToDataset = (payload: {
  rootTraceIds: string[];
  agentId?: string;
  category?: string;
}) => api.post<{ count: number; scenarios: EvalDatasetScenario[] }>('/eval/scenarios/batch-import', payload);

export interface TraceSuggestionFilter {
  minTokens?: number;
  hasToolCalls?: boolean;
  status?: 'ok' | 'error' | 'running' | 'cancelled';
  limit?: number;
}
export const suggestTracesForDataset = (filter: TraceSuggestionFilter) =>
  api.get<TraceImportCandidate[]>('/eval/traces/suggestions', { params: filter });

export const getEvalScenarioVersions = (scenarioId: string) =>
  api.get<EvalDatasetScenario[]>(`/eval/scenarios/${scenarioId}/versions`);
export const createEvalScenarioVersion = (
  scenarioId: string,
  payload: {
    name?: string;
    description?: string;
    task?: string;
    oracleExpected?: string;
    oracleType?: string;
    category?: string;
    split?: string;
    status?: 'draft' | 'active' | 'discarded';
  },
) => api.post<EvalDatasetScenario>(`/eval/scenarios/${scenarioId}/version`, payload);

// EVAL-V2 M0: recent eval runs in which a given scenario participated.
export interface ScenarioRecentRun {
  evalRunId: string;
  completedAt: string | null;
  startedAt: string | null;
  compositeScore: number | null;
  status: string;
  attribution?: string;
}
export const getScenarioRecentRuns = (scenarioId: string, limit = 10) =>
  api.get<ScenarioRecentRun[]>(`/eval/scenarios/${scenarioId}/recent-runs`, { params: { limit } });

// EVAL-V2 Q1: list chat sessions opened to analyze a given eval scenario.
// Filtered by userId on the BE so users only see their own analysis sessions.
export interface AnalysisSession {
  id: string;
  agentId: number | null;
  title: string | null;
  status: string | null;
  runtimeStatus: string | null;
  messageCount: number;
  createdAt: string | null;
  updatedAt: string | null;
}
export const analyzeScenario = (scenarioId: string, data: { userId: number; agentId: number }) =>
  api.post<{ sessionId: string; analysisType: string; scenarioId: string }>(
    `/eval/scenarios/${scenarioId}/analyze`,
    data,
  );
export const analyzeEvalTask = (taskId: string, data: { userId: number; agentId: number }) =>
  api.post<{ sessionId: string; analysisType: string; taskId: string }>(
    `/eval/tasks/${taskId}/analyze`,
    data,
  );
export const analyzeEvalTaskItem = (
  taskId: string,
  itemId: number,
  data: { userId: number; agentId: number },
) =>
  api.post<{ sessionId: string; analysisType: string; taskId: string; itemId: number; scenarioId: string }>(
    `/eval/tasks/${taskId}/items/${itemId}/analyze`,
    data,
  );
export const getAnalysisSessions = (scenarioId: string, userId: number) =>
  api.get<AnalysisSession[]>(`/eval/scenarios/${scenarioId}/analysis-sessions`, { params: { userId } });

// EVAL-V2 Q2: write a base eval scenario JSON to ~/.skillforge/eval-scenarios/.
// Required: id (slug), name, task. Oracle / setup / etc. optional.
export interface BaseScenarioInput {
  /**
   * Stable scenario id. BE auto-generates a UUID when this is omitted /
   * empty / blank — most operator-driven adds let the BE pick the id;
   * the explicit form is only useful when porting a known id from
   * elsewhere.
   */
  id?: string;
  name: string;
  task: string;
  description?: string;
  category?: string;
  split?: string;
  oracle?: { type?: string; expected?: string; expectedList?: string[] };
  setup?: { files?: Record<string, string> };
  toolsHint?: string[];
  maxLoops?: number;
  performanceThresholdMs?: number;
  tags?: string[];
  /**
   * EVAL-V2 M2: optional multi-turn conversation. When present and non-empty,
   * the BE writes the case as multi-turn; otherwise it stays single-turn
   * (uses `task`). camelCase here matches FE convention; BaseScenarioService
   * accepts both camelCase and the canonical snake_case `conversation_turns`
   * and normalizes to snake_case on disk.
   */
  conversationTurns?: ConversationTurn[];
}
export interface BaseScenarioWriteResult {
  id: string;
  status: string;
  path: string;
}
export const addBaseScenario = (payload: BaseScenarioInput) =>
  api.post<BaseScenarioWriteResult>('/eval/scenarios/base', payload);

// EVAL-V2 Q2: "Base" dataset = classpath seeds ∪ home dir scenarios. Each
// entry's `source` ('classpath' | 'home') lets the UI tag system vs
// user-added. Backed by GET /eval/scenarios/base. The BE flattens oracle
// to oracleType / oracleExpected (matches the per-agent endpoint shape) so
// FE renderers can consume both via the same EvalDatasetScenario projection.
export interface BaseScenario {
  id: string;
  version?: number;
  parentScenarioId?: string | null;
  name: string;
  description?: string | null;
  category?: string | null;
  split?: string | null;
  task: string;
  oracleType?: string | null;
  oracleExpected?: string | null;
  source?: 'classpath' | 'home';
  /** EVAL-V2 M2: multi-turn turns when the on-disk JSON has them; NULL/undef = single-turn. */
  conversationTurns?: ConversationTurn[];
  /**
   * EVAL-V2 M3b: rich-detail fields surfaced by the BE for the drawer.
   * Lists are only emitted when non-empty (BE-side guard); maxLoops /
   * performanceThresholdMs always present (primitive defaults 10 / 30000).
   */
  toolsHint?: string[];
  tags?: string[];
  /** File names only (no content). Setup file content stays server-side. */
  setupFiles?: string[];
  maxLoops?: number;
  performanceThresholdMs?: number;
  /**
   * EVAL-DATASET-LAYER V1 (V109): closed-enum data source classifier.
   * For base (classpath / home dir JSON) scenarios this typically arrives
   * as `benchmark` (seeded benchmark packs) or `manual` (user-added).
   * Null when the on-disk JSON doesn't yet declare it.
   */
  sourceType?: 'benchmark' | 'session_derived' | 'manual' | null;
  /** EVAL-DATASET-LAYER V1 (V109): origin identifier — see EvalDatasetScenario.sourceRef. */
  sourceRef?: string | null;
  /** EVAL-DATASET-LAYER V1 (V109): purpose enum — see EvalDatasetScenario.purpose. */
  purpose?: 'baseline_anchor' | 'regression' | 'ablation' | null;
  /**
   * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (V117): closed-enum JSONB tag list —
   * see {@link EvalDatasetScenario.applicableAgentRoles}. For base
   * (classpath / home dir JSON) scenarios this typically arrives as
   * {@code ['general']} unless the on-disk spec declares otherwise. Null /
   * undefined when the JSON pre-dates the V117 field.
   */
  applicableAgentRoles?: string[] | null;
}
export const getBaseScenarios = () => api.get<BaseScenario[]>('/eval/scenarios/base');
