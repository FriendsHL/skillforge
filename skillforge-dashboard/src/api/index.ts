import axios from 'axios';

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
  systemPrompt?: string;
  soulPrompt?: string;
  toolsPrompt?: string;
  modelId?: string;
  executionMode?: 'ask' | 'auto';
  maxLoops?: number;
  skillIds?: string;
  toolIds?: string;
  behaviorRules?: string;
  lifecycleHooks?: string;
}

export interface UpdateAgentRequest extends Partial<CreateAgentRequest> {
  id?: number;
}

export const getAgents = () => api.get('/agents');
export const getAgent = (id: number) => api.get(`/agents/${id}`);
export const createAgent = (data: CreateAgentRequest) => api.post('/agents', data);
export const updateAgent = (id: number, data: UpdateAgentRequest) => api.put(`/agents/${id}`, data);
export const deleteAgent = (id: number) => api.delete(`/agents/${id}`);

// Session API
export const createSession = (data: { userId: number; agentId: number }) => api.post('/chat/sessions', data);
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

// Tool API (Java function-calling tools: Bash, FileRead, etc.)
export const getTools = () => api.get('/tools');

// Skill API
export const getSkills = () => api.get('/skills');
export const getBuiltinSkills = () => api.get('/skills/builtin');
export const uploadSkill = (file: File, ownerId: number) => {
  const form = new FormData();
  form.append('file', file);
  form.append('ownerId', String(ownerId));
  return api.post('/skills/upload', form);
};
export const deleteSkill = (id: number) => api.delete(`/skills/${id}`);
export const getSkillDetail = (id: number | string) => api.get(`/skills/${id}/detail`);
export const toggleSkill = (id: number, enabled: boolean) =>
  api.put(`/skills/${id}/toggle?enabled=${enabled}`);

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

export const forkSkill = (id: number | string, ownerId: number) =>
  api.post<SkillVersionEntry>(`/skills/${id}/fork?ownerId=${ownerId}`);

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
export const getMemories = (userId: number, type?: string) =>
  api.get('/memories', { params: { userId, type } });
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

export interface BehaviorRuleConfig {
  builtinRuleIds: string[];
  customRules: string[];
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
}

export interface SkillExtractionStartResult {
  status: string;
  count?: number;
  message?: string;
}

export const triggerSkillExtraction = (agentId: number | string, ownerId: number) =>
  api.post<SkillExtractionStartResult>(`/agents/${agentId}/skill-drafts?ownerId=${ownerId}`);

export const getSkillDrafts = (ownerId: number) =>
  api.get<SkillDraft[]>(`/skill-drafts?ownerId=${ownerId}`);

export const reviewSkillDraft = (id: string, action: 'approve' | 'discard', reviewedBy: number) =>
  api.patch<SkillDraft>(`/skill-drafts/${id}`, { action, reviewedBy });

export default api;
