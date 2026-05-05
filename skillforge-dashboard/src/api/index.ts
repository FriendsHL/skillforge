import axios from 'axios';
import type { ModelOption } from '../constants/models';

const api = axios.create({ baseURL: '/api' });

// Request interceptor: inject Bearer token from localStorage
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('sf_token');
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor: redirect to /login on 401
api.interceptors.response.use(
  (res) => res,
  (error: unknown) => {
    const status =
      error &&
      typeof error === 'object' &&
      'response' in error &&
      (error as { response?: { status?: number } }).response?.status;
    const requestUrl =
      (error &&
      typeof error === 'object' &&
      'config' in error &&
      (error as { config?: { url?: string } }).config?.url) ?? '';
    // Skip auto-redirect for auth endpoints — let the caller handle the error directly
    const isAuthEndpoint = typeof requestUrl === 'string' && requestUrl.startsWith('/auth/');
    if (status === 401 && !isAuthEndpoint) {
      if (!window.location.pathname.includes('/login')) {
        localStorage.removeItem('sf_token');
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  },
);

/** Unwrap a paginated-or-direct array response from the backend. */
export function extractList<T>(res: { data: T[] | { data: T[] } | unknown }): T[] {
  const d = (res as { data: unknown }).data;
  if (Array.isArray(d)) return d as T[];
  if (d && typeof d === 'object' && Array.isArray((d as { data?: unknown }).data)) {
    return (d as { data: T[] }).data;
  }
  return [];
}

// Agent API

export interface CreateAgentRequest {
  name: string;
  description?: string;
  role?: string;
  systemPrompt?: string;
  soulPrompt?: string;
  toolsPrompt?: string;
  modelId?: string;
  executionMode?: 'ask' | 'auto';
  maxLoops?: number;
  skillIds?: string;
  toolIds?: string;
  public?: boolean;
  behaviorRules?: string;
  lifecycleHooks?: string;
  /** Thinking Mode v1 — `auto` preserves the provider's default behaviour. */
  thinkingMode?: 'auto' | 'enabled' | 'disabled';
  /** Only honoured by models whose protocolFamily exposes reasoning effort (see ModelOption). */
  reasoningEffort?: 'low' | 'medium' | 'high' | 'max';
  /** P1 Skill Control Plane: JSON-array string of system-skill names this agent has opted out of. */
  disabledSystemSkills?: string;
}

export interface UpdateAgentRequest extends Partial<CreateAgentRequest> {
  id?: number;
  // `public` mirrors AgentEntity#isPublic; omitted values preserve existing visibility.
  public?: boolean;
}

export const getAgents = () => api.get('/agents');
export const getAgent = (id: number) => api.get(`/agents/${id}`);
export const createAgent = (data: CreateAgentRequest) => api.post('/agents', data);
export const updateAgent = (id: number, data: UpdateAgentRequest) => api.put(`/agents/${id}`, data);
export const deleteAgent = (id: number) => api.delete(`/agents/${id}`);

// LLM models
export const getLlmModels = () => api.get<ModelOption[]>('/llm/models');

// Session API
// EVAL-V2 Q1: `sourceScenarioId` is optional and only set by the Analyze-case
// flow on the eval drawer — the BE links the new chat session back to the
// eval scenario being analyzed so the scenario detail drawer can list prior
// analysis sessions for the same case.
export const createSession = (data: { userId: number; agentId: number; sourceScenarioId?: string }) =>
  api.post('/chat/sessions', data);
export const getSessions = (userId: number) => api.get(`/chat/sessions?userId=${userId}`);
export const getSessionMessages = (id: string, userId: number) =>
  api.get(`/chat/sessions/${id}/messages`, { params: { userId } });

export interface DeleteSessionsSkippedEntry {
  id: string;
  reason: string;
}
export interface DeleteSessionsResponse {
  deleted: number;
  skipped: DeleteSessionsSkippedEntry[];
}

export const deleteSessions = (ids: string[], userId: number) =>
  api.delete<DeleteSessionsResponse>('/chat/sessions', { data: { ids }, params: { userId } });

// Chat API
export const sendMessage = (sessionId: string, data: { message: string; userId: number }) =>
  api.post(`/chat/${sessionId}`, data);
export const cancelChat = (sessionId: string, userId: number) =>
  api.post(`/chat/${sessionId}/cancel`, null, { params: { userId } });
export const answerAsk = (sessionId: string, askId: string, answer: string, userId: number) =>
  api.post(`/chat/${sessionId}/answer`, { askId, answer, userId });

// ─── Install Confirmation ──────────────────────────────────────────────────
// Payload for WS event `confirmation_required` (mirrors backend `ConfirmationPromptPayload`).
export interface ConfirmationChoice {
  id?: string;
  value?: string;
  label: string;
  /** Semantic variant so the UI can style approve/deny distinctly. */
  variant?: 'approve' | 'deny';
  style?: string;
  description?: string;
}

export interface ConfirmationPromptPayload {
  confirmationId: string;
  sessionId: string;
  /** Operation/tool name, for example 'clawhub' or 'CreateAgent'. */
  installTool: string;
  /** Operation target: package name for install, agent name for CreateAgent. */
  installTarget: string;
  commandPreview: string;
  title: string;
  description: string;
  choices: ConfirmationChoice[];
  /** ISO-8601 timestamp for when the prompt expires. */
  expiresAt: string;
}

export type ConfirmationDecision = 'APPROVED' | 'DENIED';

export const submitConfirmation = (
  sessionId: string,
  confirmationId: string,
  decision: ConfirmationDecision,
  userId: number,
) => api.post(`/chat/${sessionId}/confirmation`, { confirmationId, decision, userId });

export const setSessionMode = (sessionId: string, mode: 'ask' | 'auto', userId: number) =>
  api.patch(`/chat/sessions/${sessionId}/mode`, { mode }, { params: { userId } });
export const getSession = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}`, { params: { userId } });
export const getChildSessions = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}/children`, { params: { userId } });
export const getSubAgentRuns = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}/subagent-runs`, { params: { userId } });
export const compactSession = (sessionId: string, level: 'full', userId: number, reason?: string) =>
  api.post(`/chat/sessions/${sessionId}/compact`, { level, reason }, { params: { userId } });
export const getCompactions = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}/compactions`, { params: { userId } });

export interface ContextBreakdownSegment {
  key: string;
  label: string;
  tokens: number;
  children?: ContextBreakdownSegment[];
}
export interface ContextBreakdown {
  sessionId: string;
  total: number;
  windowLimit: number;
  pct: number;
  segments: ContextBreakdownSegment[];
}
interface RawBreakdownSegment {
  key: string;
  label: string;
  tokens: number;
  children?: RawBreakdownSegment[] | null;
}
interface RawBreakdown {
  sessionId: string;
  total: number;
  windowLimit: number;
  pct: number;
  segments: RawBreakdownSegment[];
}

function normalizeSegments(segs: RawBreakdownSegment[]): ContextBreakdownSegment[] {
  return segs.map((s) => ({
    key: s.key,
    label: s.label,
    tokens: s.tokens,
    children: s.children == null ? undefined : normalizeSegments(s.children),
  }));
}

export const getContextBreakdown = async (
  sessionId: string,
  userId: number,
): Promise<{ data: ContextBreakdown }> => {
  const res = await api.get<RawBreakdown>(
    `/chat/sessions/${sessionId}/context-breakdown`,
    { params: { userId } },
  );
  const raw = res.data;
  return {
    data: {
      sessionId: raw.sessionId,
      total: raw.total,
      windowLimit: raw.windowLimit,
      pct: raw.pct,
      segments: normalizeSegments(raw.segments),
    },
  };
};
export interface SessionCompactionCheckpoint {
  id: string;
  sessionId: string;
  boundarySeqNo: number;
  summarySeqNo?: number | null;
  reason: string;
  preRangeStartSeqNo?: number | null;
  preRangeEndSeqNo?: number | null;
  postRangeStartSeqNo?: number | null;
  postRangeEndSeqNo?: number | null;
  snapshotRef?: string | null;
  createdAt: string;
}

export type SessionRuntimeStatus = 'idle' | 'running' | 'error' | 'waiting_user' | 'compacting';
export type SessionStatus = 'active' | 'archived';

export interface SessionCheckpointMutationResult {
  id: string;
  userId: number;
  agentId: number;
  title: string | null;
  status: SessionStatus;
  runtimeStatus: SessionRuntimeStatus;
  messageCount: number;
  parentSessionId?: string | null;
  updatedAt?: string;
}

export interface PruneToolOutputsResult {
  sessionId: string;
  prunedCount: number;
  limit: number;
}

export const getSessionCheckpoints = (sessionId: string, userId: number, size = 20) =>
  api.get<SessionCompactionCheckpoint[]>(`/chat/sessions/${sessionId}/checkpoints`, { params: { userId, size } });
export const getSessionCheckpoint = (sessionId: string, checkpointId: string, userId: number) =>
  api.get<SessionCompactionCheckpoint>(`/chat/sessions/${sessionId}/checkpoints/${checkpointId}`, { params: { userId } });
export const branchFromCheckpoint = (sessionId: string, checkpointId: string, userId: number, title?: string) =>
  api.post<SessionCheckpointMutationResult>(
    `/chat/sessions/${sessionId}/checkpoints/${checkpointId}/branch`,
    title !== undefined ? { title } : {},
    { params: { userId } },
  );
export const restoreFromCheckpoint = (sessionId: string, checkpointId: string, userId: number) =>
  api.post<SessionCheckpointMutationResult>(
    `/chat/sessions/${sessionId}/checkpoints/${checkpointId}/restore`,
    null,
    { params: { userId } },
  );
export const pruneSessionToolOutputs = (sessionId: string, userId: number, limit = 200) =>
  api.post<PruneToolOutputsResult>(`/chat/sessions/${sessionId}/prune-tools`, { limit }, { params: { userId } });
export const getSessionReplay = (sessionId: string, userId: number) =>
  api.get(`/chat/sessions/${sessionId}/replay`, { params: { userId } });

// Traces API
export const getTraces = (sessionId?: string) =>
  sessionId ? api.get(`/traces`, { params: { sessionId } }) : api.get('/traces');
export const getTraceSpans = (traceId: string) => api.get(`/traces/${traceId}/spans`);

// ─── Observability (OBS-1) ────────────────────────────────────────────────
// See src/types/observability.ts for DTO shapes; mirrors backend records frozen
// behind the `obs-1-dto-freeze` git tag (plan §11 M1).
import type {
  SessionSpansResponse,
  LlmSpanDetail,
  ToolSpanDetail,
  EventSpanDetail,
  BlobPart,
  TraceTreeDto,
} from '../types/observability';

/**
 * OBS-4 M3 — fetch the full unified trace tree for a `root_trace_id`.
 * Backend returns every trace (and its spans) sharing the same
 * `root_trace_id` across sessions, depth-annotated for nested rendering.
 * 404 when no trace exists with this rootTraceId.
 */
export const getTraceTree = (rootTraceId: string) =>
  api.get<TraceTreeDto>(`/traces/${rootTraceId}/tree`);

export interface GetSessionSpansParams {
  /**
   * OBS-2 M3: when set, backend filters `t_llm_span` to only this trace_id.
   * Used by SessionDetail to fetch spans of the currently selected trace
   * (replaces the legacy "fetch all session spans + client-side filter" path).
   */
  traceId?: string;
  since?: string;
  limit?: number;
  /**
   * R3-WN4: array of span kinds to include. Axios serialises a `string[]`
   * param as repeated `?kinds=llm&kinds=tool`, which the Spring controller
   * deserialises into `Set<String>`. Comma-form (e.g. `kinds=llm,tool`) is
   * also accepted on the wire. Omit / empty array → backend defaults to all.
   *
   * OBS-2 M3 adds `'event'` for the 4 lifecycle event spans.
   */
  kinds?: Array<'llm' | 'tool' | 'event'>;
}

// W6 fix: OBS-1 controllers now require `userId` to enforce session ownership;
// without it the request returns 400. The dashboard's userId comes from useAuth().
export const getSessionSpans = (
  sessionId: string,
  userId: number,
  params?: GetSessionSpansParams,
) =>
  api.get<SessionSpansResponse>(`/observability/sessions/${sessionId}/spans`, {
    params: { ...(params ?? {}), userId },
  });

export const getLlmSpanDetail = (spanId: string, userId: number) =>
  api.get<LlmSpanDetail>(`/observability/spans/${spanId}`, { params: { userId } });

export const getToolSpanDetail = (spanId: string, userId: number) =>
  api.get<ToolSpanDetail>(`/observability/tool-spans/${spanId}`, { params: { userId } });

/**
 * OBS-2 M3 — fetch a single event span's detail (full input / output / error).
 * Backend reads from `t_llm_span` where `kind='event'`; 404 when the span
 * exists but is a different kind.
 */
export const getEventSpanDetail = (spanId: string, userId: number) =>
  api.get<EventSpanDetail>(`/observability/event-spans/${spanId}`, { params: { userId } });

/**
 * Fetch the controlled blob payload (request / response / sse). Backend streams
 * the file via StreamingResponseBody and may return 429 when concurrency cap is
 * saturated; callers should surface that as a transient retry hint.
 */
export const getLlmSpanBlob = (spanId: string, part: BlobPart, userId: number) =>
  api.get<string>(`/observability/spans/${spanId}/blob`, {
    params: { part, userId },
    responseType: 'text',
    transformResponse: [(d: unknown) => (typeof d === 'string' ? d : String(d ?? ''))],
  });

// Tool API (Java function-calling tools: Bash, Read, etc.)
export const getTools = () => api.get('/tools');

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
  skipReason?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface StartAbTestRequest {
  candidateSkillId: number;
  agentId?: string;
  baselineEvalRunId?: string;
  triggeredByUserId?: number;
}

export const startSkillAbTest = (parentSkillId: number | string, req: StartAbTestRequest) =>
  api.post<SkillAbRun>(`/skills/${parentSkillId}/abtest`, req);

export const getSkillAbTests = (skillId: number | string) =>
  api.get<SkillAbRun[]>(`/skills/${skillId}/abtest`);

export const getSkillAbTest = (abRunId: string) =>
  api.get<SkillAbRun>(`/skills/abtest/${abRunId}`);

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

// Memory API
export type MemoryLifecycleStatus = 'ACTIVE' | 'STALE' | 'ARCHIVED';

export interface MemoryStats {
  active: number;
  stale: number;
  archived: number;
  capacityCap: number;
}

export const getMemories = (userId: number, type?: string, status?: MemoryLifecycleStatus) =>
  api.get('/memories', { params: { userId, type, status } });
export const searchMemories = (userId: number, keyword: string) =>
  api.get('/memories/search', { params: { userId, keyword } });
export interface CreateMemoryRequest {
  userId: number;
  type: string;
  content: string;
  source?: string;
}

export interface UpdateMemoryRequest {
  content?: string;
  type?: string;
  source?: string;
}

export const createMemory = (data: CreateMemoryRequest) => api.post('/memories', data);
export const updateMemory = (id: number, data: UpdateMemoryRequest) => api.put(`/memories/${id}`, data);
export const deleteMemory = (id: number) => api.delete(`/memories/${id}`);
export const updateMemoryStatus = (id: number, userId: number, status: MemoryLifecycleStatus) =>
  api.patch(`/memories/${id}/status`, { status }, { params: { userId } });
export const batchArchiveMemories = (userId: number, ids: number[]) =>
  api.post('/memories/batch-archive', { ids }, { params: { userId } });
export const batchRestoreMemories = (userId: number, ids: number[]) =>
  api.post('/memories/batch-restore', { ids }, { params: { userId } });
export const batchUpdateMemoryStatus = (userId: number, ids: number[], status: MemoryLifecycleStatus) =>
  api.post('/memories/batch-status', { ids, status }, { params: { userId } });
export const batchDeleteMemories = (userId: number, ids: number[]) =>
  api.delete('/memories/batch', { data: { ids }, params: { userId } });
export const getMemoryStats = (userId: number) =>
  api.get<MemoryStats>('/memories/stats', { params: { userId } });

// User Config API
export const getClaudeMd = (userId: number) => api.get('/user-config/claude-md', { params: { userId } });
export const saveClaudeMd = (userId: number, claudeMd: string) =>
  api.put('/user-config/claude-md', { claudeMd }, { params: { userId } });

// Collab Run API
export const getCollabRuns = () => api.get('/collab-runs');
export const getCollabRunMembers = (collabRunId: string) => api.get(`/collab-runs/${collabRunId}/members`);
export const getCollabRunSummary = (collabRunId: string) => api.get(`/collab-runs/${collabRunId}/summary`);
export const getCollabRunTraces = (collabRunId: string) => api.get(`/collab-runs/${collabRunId}/traces`);

// Dashboard API
export const getDashboardOverview = () => api.get('/dashboard/overview');
export const getDailyUsage = (days = 30) => api.get(`/dashboard/usage/daily?days=${days}`);
export const getUsageByModel = () => api.get('/dashboard/usage/by-model');
export const getUsageByAgent = () => api.get('/dashboard/usage/by-agent');

// ─── Behavior Rules ─────────────────────────────────────────────────────────

export interface BehaviorRule {
  id: string;
  category: string;
  severity: 'must' | 'should' | 'may';
  label: string;
  labelZh: string;
  deprecated: boolean;
  replacedBy: string | null;
  presets: string[];
}

export interface BehaviorRulesResponse {
  version: string;
  rules: BehaviorRule[];
}

export interface BehaviorRulePresetResponse {
  presetName: string;
  ruleIds: string[];
}

export type CustomRuleSeverity = 'MUST' | 'SHOULD' | 'MAY';

export interface CustomBehaviorRule {
  severity: CustomRuleSeverity;
  text: string;
}

export interface BehaviorRuleConfig {
  builtinRuleIds: string[];
  customRules: CustomBehaviorRule[];
}

export const getBehaviorRules = () =>
  api.get<BehaviorRulesResponse>('/behavior-rules');

export const getBehaviorRulesPreset = (executionMode: string) =>
  api.get<BehaviorRulePresetResponse>('/behavior-rules/presets', {
    params: { executionMode },
  });

// ─── Lifecycle Hooks (N3) ────────────────────────────────────────────────────

export interface LifecycleHookEventDto {
  id: string;
  displayName: string;
  description: string;
  inputSchema: Record<string, string>;
  canAbort: boolean;
}

export interface LifecycleHookPresetDto {
  id: string;
  name: string;
  description: string;
  /** Parsed config object (backend returns structured JSON, not a string). */
  config: unknown;
}

interface LifecycleHookEventsEnvelope {
  version: string;
  events: LifecycleHookEventDto[];
}

interface LifecycleHookPresetsEnvelope {
  version: string;
  presets: LifecycleHookPresetDto[];
}

// Backend wraps list in {version, events|presets}; unwrap here so consumers get the array directly.
export const getLifecycleHookEvents = () =>
  api
    .get<LifecycleHookEventsEnvelope>('/lifecycle-hooks/events')
    .then((r) => ({ ...r, data: r.data?.events ?? [] }));

export const getLifecycleHookPresets = () =>
  api
    .get<LifecycleHookPresetsEnvelope>('/lifecycle-hooks/presets')
    .then((r) => ({ ...r, data: r.data?.presets ?? [] }));

// ─── Built-in Methods (N3 P2) ──────────────────────────────────────────────

export interface BuiltInMethodDto {
  ref: string;
  displayName: string;
  description: string;
  argsSchema: Record<string, string>;
}

export const getLifecycleHookMethods = () =>
  api.get<BuiltInMethodDto[]>('/lifecycle-hooks/methods');

// ─── Effective Hook Sources (SEC-2) ────────────────────────────────────────

export type LifecycleHookSource = 'system' | 'user' | 'agent';
export type AgentHookReviewState = 'PENDING' | 'APPROVED' | 'REJECTED' | 'RETIRED';

export interface HookViewDto {
  event: string;
  source: LifecycleHookSource;
  sourceId: string;
  entry?: unknown;
  displayName?: string | null;
  timeoutSeconds?: number;
  failurePolicy?: string;
  async?: boolean;
  handlerSummary?: {
    type?: string;
    name?: string;
    scriptLength?: number;
  };
  agentAuthoredHookId?: number | null;
  authorAgentId?: number | null;
  reviewState?: AgentHookReviewState | null;
  readOnly?: boolean;
  dispatchEnabled?: boolean;
}

export interface AgentAuthoredHookViewDto {
  id: number;
  source: 'agent';
  sourceId: string;
  targetAgentId: number;
  authorAgentId: number;
  authorSessionId?: string | null;
  event: string;
  methodKind: string;
  methodId?: number | null;
  methodRef: string;
  displayName?: string | null;
  description?: string | null;
  timeoutSeconds: number;
  failurePolicy: string;
  async: boolean;
  reviewState: AgentHookReviewState;
  reviewNote?: string | null;
  reviewedByUserId?: number | null;
  reviewedAt?: string | null;
  parentHookId?: number | null;
  enabled: boolean;
  dispatchEnabled: boolean;
  readOnly: boolean;
  stats?: {
    usageCount: number;
    successCount: number;
    failureCount: number;
  };
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface EffectiveLifecycleHooksResponse {
  version: string;
  agentId: number;
  mergeOrder: LifecycleHookSource[];
  counts: {
    system: number;
    user: number;
    agentAuthored: Record<AgentHookReviewState, number>;
    dispatchable: number;
  };
  system: { entries: HookViewDto[] };
  user: { rawJson: string | null; entries: HookViewDto[] };
  agentAuthored: { entries: AgentAuthoredHookViewDto[] };
  effectiveByEvent: Record<string, HookViewDto[]>;
}

export const getAgentHooks = (agentId: number) =>
  api.get<EffectiveLifecycleHooksResponse>(`/agents/${agentId}/hooks`);

export const updateAgentUserHooks = (agentId: number, rawJson: string) =>
  api.put<{ rawJson?: string; entries?: HookViewDto[] }>(`/agents/${agentId}/hooks/user`, { rawJson });

export const approveAgentAuthoredHook = (id: number, reviewNote?: string) =>
  api.post<AgentAuthoredHookViewDto>(`/agent-authored-hooks/${id}/approve`, { reviewNote });

export const rejectAgentAuthoredHook = (id: number, reviewNote?: string) =>
  api.post<AgentAuthoredHookViewDto>(`/agent-authored-hooks/${id}/reject`, { reviewNote });

export const retireAgentAuthoredHook = (id: number, reviewNote?: string) =>
  api.post<AgentAuthoredHookViewDto>(`/agent-authored-hooks/${id}/retire`, { reviewNote });

export const setAgentAuthoredHookEnabled = (id: number, enabled: boolean) =>
  api.patch<AgentAuthoredHookViewDto>(`/agent-authored-hooks/${id}/enabled`, { enabled });

// ─── Hook History (N3 P2) ──────────────────────────────────────────────────

export interface HookHistoryEntry {
  id: string;
  sessionId: string;
  spanType: string;
  name: string;
  input: string;
  output: string | null;
  startTime: string;
  endTime: string | null;
  durationMs: number;
  success: boolean;
  error: string | null;
}

export const getHookHistory = (agentId: string, limit = 50) =>
  api.get<HookHistoryEntry[]>(`/agents/${agentId}/hook-history`, {
    params: { limit },
  });

// ─── Hook Dry-Run (N3 P2) ──────────────────────────────────────────────────

export interface DryRunRequest {
  event: string;
  entryIndex: number;
}

export interface DryRunResponse {
  success: boolean;
  output: string | null;
  errorMessage: string | null;
  durationMs: number;
  chainDecision: string;
}

export const dryRunHook = (agentId: string, body: DryRunRequest) =>
  api.post<DryRunResponse>(`/agents/${agentId}/hooks/test`, body);

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
}
export const getEvalDatasetScenarios = (agentId: string | number) =>
  api.get<EvalDatasetScenario[]>('/eval/scenarios', { params: { agentId } });

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
}
export const getBaseScenarios = () => api.get<BaseScenario[]>('/eval/scenarios/base');

// ─── Self-Improve Pipeline ───────────────────────────────────────────────────

export interface ImprovementStartResult {
  abRunId: string;
  promptVersionId: string;
  status: string;
}

export interface AbScenarioResult {
  scenarioId: string;
  scenarioName: string;
  baseline: { status: 'PASS' | 'FAIL' | 'TIMEOUT'; oracleScore: number };
  candidate: { status: 'PASS' | 'FAIL' | 'TIMEOUT'; oracleScore: number };
}

export interface AbRunDetail {
  abRunId: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  deltaPassRate: number | null;
  candidatePassRate: number | null;
  baselinePassRate: number | null;
  promoted: boolean;
  completedScenarios: number;
  scenarioResults: AbScenarioResult[];
  failureReason?: string;
}

export interface PromptVersion {
  id: string;
  versionNumber: number;
  status: 'candidate' | 'active' | 'deprecated' | 'failed';
  source: 'manual' | 'auto_improve';
  deltaPassRate: number | null;
  baselinePassRate: number | null;
  improvementRationale: string | null;
  createdAt: string;
  promotedAt: string | null;
  deprecatedAt: string | null;
  content?: string;
}

export const triggerPromptImprove = (agentId: string, evalRunId: string) =>
  api.post<ImprovementStartResult>(`/agents/${agentId}/prompt-improve`, { evalRunId });

export const getAbRunDetail = (agentId: string, abRunId: string) =>
  api.get<AbRunDetail>(`/agents/${agentId}/prompt-improve/${abRunId}`);

export const getActiveImprovement = (agentId: string) =>
  api.get<AbRunDetail | null>(`/agents/${agentId}/prompt-improve/active`);

export const getPromptVersions = (agentId: string) =>
  api.get<PromptVersion[]>(`/agents/${agentId}/prompt-versions`);

export const getPromptVersionDetail = (agentId: string, versionId: string) =>
  api.get<PromptVersion>(`/agents/${agentId}/prompt-versions/${versionId}`);

export const rollbackPromptVersion = (agentId: string, versionId: string) =>
  api.post(`/agents/${agentId}/prompt-versions/${versionId}/rollback`);

export const resumeAutoImprove = (agentId: string) =>
  api.post(`/agents/${agentId}/prompt-improve/resume`);

// ─── Script Methods (P4 Code Agent) ─────────────────────────────────────────

export interface ScriptMethodSummary {
  id: number;
  ref: string;
  displayName: string;
  description: string | null;
  lang: string;
  ownerId: number | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ScriptMethodDetail extends ScriptMethodSummary {
  scriptBody: string;
  argsSchema: string | null;
}

export interface CreateScriptMethodRequest {
  ref: string;
  displayName: string;
  description?: string;
  lang: string;
  scriptBody: string;
  argsSchema?: string;
  ownerId?: number;
}

export interface UpdateScriptMethodRequest {
  displayName?: string;
  description?: string;
  lang?: string;
  scriptBody?: string;
  argsSchema?: string;
}

export const getScriptMethods = () => api.get<ScriptMethodSummary[]>('/script-methods');
export const getScriptMethod = (id: number) => api.get<ScriptMethodDetail>(`/script-methods/${id}`);
export const createScriptMethod = (data: CreateScriptMethodRequest) =>
  api.post<ScriptMethodDetail>('/script-methods', data);
export const updateScriptMethod = (id: number, data: UpdateScriptMethodRequest) =>
  api.put<ScriptMethodDetail>(`/script-methods/${id}`, data);
export const toggleScriptMethod = (id: number, enabled: boolean) =>
  api.post<ScriptMethodDetail>(`/script-methods/${id}/enable`, { enabled });
export const deleteScriptMethod = (id: number) => api.delete(`/script-methods/${id}`);

// ─── Compiled Methods (P4 Code Agent) ───────────────────────────────────────

export interface CompiledMethodSummary {
  id: number;
  ref: string;
  displayName: string;
  description: string | null;
  status: 'pending_review' | 'compiled' | 'active' | 'rejected';
  compileError: string | null;
  generatedBySessionId: string | null;
  generatedByAgentId: number | null;
  reviewedByUserId: number | null;
  hasCompiledBytes: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CompiledMethodDetail extends CompiledMethodSummary {
  sourceCode: string;
  argsSchema: string | null;
  compiledBytesLength: number;
}

export interface SubmitCompiledMethodRequest {
  ref: string;
  displayName: string;
  description?: string;
  sourceCode: string;
  argsSchema?: string;
  sessionId?: string;
  agentId?: number;
}

export const getCompiledMethods = () => api.get<CompiledMethodSummary[]>('/compiled-methods');
export const getCompiledMethod = (id: number) => api.get<CompiledMethodDetail>(`/compiled-methods/${id}`);
export const submitCompiledMethod = (data: SubmitCompiledMethodRequest) =>
  api.post<CompiledMethodDetail>('/compiled-methods', data);
export const compileCompiledMethod = (id: number) =>
  api.post<CompiledMethodDetail>(`/compiled-methods/${id}/compile`);
export const approveCompiledMethod = (id: number, reviewerUserId?: number) =>
  api.post<CompiledMethodDetail>(`/compiled-methods/${id}/approve`, { reviewerUserId });
export const rejectCompiledMethod = (id: number, reviewerUserId?: number) =>
  api.post<CompiledMethodDetail>(`/compiled-methods/${id}/reject`, { reviewerUserId });
export const deleteCompiledMethod = (id: number) => api.delete(`/compiled-methods/${id}`);

// ─── Skill Drafts ───────────────────────────────────────────────────────────

export interface SkillDraft {
  id: string;
  sourceSessionId?: string;
  ownerId: number;
  name: string;
  description?: string;
  triggers?: string;
  requiredTools?: string;
  promptHint?: string;
  extractionRationale?: string;
  status: 'draft' | 'approved' | 'discarded';
  skillId?: number;
  createdAt: string;
  reviewedAt?: string;
  reviewedBy?: number;
  // P1 §9 dedup signal: 0..1 jaccard/levenshtein blend; populated when BE
  // detects a candidate that is likely a duplicate of an existing skill.
  similarity?: number;
  // Existing skill name/id this draft is most similar to, when similarity is set.
  mergeCandidateId?: string;
  mergeCandidateName?: string;
}

export interface SkillExtractionStartResult {
  status: string;
  count?: number;
  message?: string;
}

// P1 (B-1): all skill-draft endpoints take `userId` query param.
export const triggerSkillExtraction = (agentId: number | string, userId: number) =>
  api.post<SkillExtractionStartResult>(`/agents/${agentId}/skill-drafts`, null, {
    params: { userId },
  });

export const getSkillDrafts = (userId: number) =>
  api.get<SkillDraft[]>('/skill-drafts', { params: { userId } });

/**
 * Review a draft. `forceCreate=true` is required by the backend when the
 * candidate has high similarity (≥0.85) to an existing skill — the modal
 * confirmation flow sets it after the operator explicitly acknowledges
 * the duplicate (P1-C-8).
 *
 * NOTE on body field naming: BE reads `reviewedBy` (legacy stable contract,
 * see SkillDraftController.reviewDraft) — keep the FE field name as
 * `reviewedBy` even though semantically it is the acting user id. Renaming
 * to `userId` here would 400 every approve/discard until BE catches up
 * (Code Judge r1 B-FE-1).
 */
export const reviewSkillDraft = (
  id: string,
  action: 'approve' | 'discard',
  userId: number,
  options?: { forceCreate?: boolean },
) =>
  api.patch<SkillDraft>(`/skill-drafts/${id}`, {
    action,
    reviewedBy: userId,
    forceCreate: options?.forceCreate ?? false,
  });

export default api;
