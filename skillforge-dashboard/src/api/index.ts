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
export const getAgents = () => api.get('/agents');
export const getAgent = (id: number) => api.get(`/agents/${id}`);
export const createAgent = (data: any) => api.post('/agents', data);
export const updateAgent = (id: number, data: any) => api.put(`/agents/${id}`, data);
export const deleteAgent = (id: number) => api.delete(`/agents/${id}`);

// Session API
export const createSession = (data: { userId: number; agentId: number }) => api.post('/chat/sessions', data);
export const getSessions = (userId: number) => api.get(`/chat/sessions?userId=${userId}`);
export const getSessionMessages = (id: string, userId: number) =>
  api.get(`/chat/sessions/${id}/messages`, { params: { userId } });

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

// Memory API
export const getMemories = (userId: number, type?: string) =>
  api.get('/memories', { params: { userId, type } });
export const searchMemories = (userId: number, keyword: string) =>
  api.get('/memories/search', { params: { userId, keyword } });
export const createMemory = (data: any) => api.post('/memories', data);
export const updateMemory = (id: number, data: any) => api.put(`/memories/${id}`, data);
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

export default api;
