import api from './client';

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
