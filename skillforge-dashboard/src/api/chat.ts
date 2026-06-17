import api from './client';

// Session API
// Ordinary session creation. Eval analysis flows use dedicated /api/eval/*/analyze
// endpoints so new code no longer writes t_session.source_scenario_id.
export const createSession = (data: { userId: number; agentId: number; sourceScenarioId?: string }) =>
  api.post('/chat/sessions', data);
// SYSTEM-AGENT-TYPING Phase 2 visibility (2026-05-18): optional `agentType`
// param. When 'system', BE switches to JOIN-by-agent_type path (userId-agnostic)
// so the dashboard operator (userId=1=admin) sees cron-owned system sessions
// (typically ownerId=0). Omitted or 'user' keeps the legacy userId-scoped path.
export const getSessions = (userId: number, agentType?: 'user' | 'system') => {
  const params = new URLSearchParams({ userId: String(userId) });
  if (agentType) params.set('agentType', agentType);
  return api.get(`/chat/sessions?${params.toString()}`);
};
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
export const sendMessage = (sessionId: string, data: { message: string; userId: number; attachmentIds?: string[] }) =>
  api.post(`/chat/${sessionId}`, data);

export interface ChatAttachmentResponse {
  id: string;
  sessionId: string;
  /** Wave 3 — adds word / excel / csv kinds (BE emits lowercase). */
  kind: 'image' | 'pdf' | 'word' | 'excel' | 'csv';
  mimeType: string;
  filename: string;
  sizeBytes: number;
  pageCount?: number;
  /** Excel only — sheet count populated by BE parser. */
  sheetCount?: number;
  status: string;
}

export const uploadChatAttachment = (sessionId: string, userId: number, file: File) => {
  const form = new FormData();
  form.append('file', file);
  return api.post<ChatAttachmentResponse>(
    `/chat/sessions/${sessionId}/attachments`,
    form,
    { params: { userId } },
  );
};

/**
 * MULTIMODAL-MVP Phase 2: fetch attachment bytes for inline rendering. Returns
 * the response as a {@link Blob} so callers can build a blob URL with
 * {@code URL.createObjectURL}. Auth flows through the standard Bearer
 * interceptor (the URL itself stays clean — no token leakage in browser
 * history / dev-tools network panel). Caller is responsible for revoking the
 * blob URL on unmount to avoid memory leaks.
 */
export const getChatAttachmentBlob = (
  attachmentId: string,
  userId: number,
  sessionId?: string,
) =>
  api.get<Blob>(`/chat/attachments/${attachmentId}/data`, {
    params: sessionId ? { userId, sessionId } : { userId },
    responseType: 'blob',
  });
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
