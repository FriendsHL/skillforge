import api from './client';

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
