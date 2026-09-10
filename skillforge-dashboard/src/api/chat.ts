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
export interface SendMessageRequest {
  message: string;
  userId: number;
  attachmentIds?: string[];
  requestId?: string;
}

export interface SendMessageResponse {
  sessionId: string;
  status: string;
  requestId: string;
}

export const sendMessage = (sessionId: string, data: SendMessageRequest) =>
  api.post<SendMessageResponse>(`/chat/${sessionId}`, data);

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
export interface CancelChatResponse {
  status: string;
  requestId?: string;
  outcome?: string;
}

export const cancelChat = (
  sessionId: string,
  userId: number,
  requestId?: string,
) =>
  api.post<CancelChatResponse>(`/chat/${sessionId}/cancel`, null, {
    params: requestId ? { userId, requestId } : { userId },
  });
export const retryFailedChatTurn = (sessionId: string, userId: number) =>
  api.post(`/chat/${sessionId}/retry`, null, { params: { userId } });
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
  metadata?: ContextBreakdownMetadata;
}
export interface ContextBreakdownMetadata {
  sourceType?: string | null;
  placement?: string | null;
  stable?: boolean | null;
  cacheable?: boolean | null;
  contentHash?: string | null;
  kind?: string | null;
  source?: string | null;
  exposureReason?: string | null;
  authority?: string | null;
  trustLevel?: string | null;
  lifecycle?: string | null;
  compactPolicy?: string | null;
}
export interface ContextBreakdown {
  sessionId: string;
  total: number;
  windowLimit: number;
  pct: number;
  segments: ContextBreakdownSegment[];
  observation?: ContextObservationSummary;
}
export interface ContextObservationSummary {
  stablePrefixHash: string;
  assemblyHash: string;
  toolSchemasHash: string;
  durationMicros: number;
}
interface RawBreakdownSegment {
  key: string;
  label: string;
  tokens: number;
  children?: RawBreakdownSegment[] | null;
  metadata?: ContextBreakdownMetadata | null;
}
interface RawBreakdown {
  sessionId: string;
  total: number;
  windowLimit: number;
  pct: number;
  segments: RawBreakdownSegment[];
  observation?: ContextObservationSummary | null;
}

function normalizeSegments(segs: RawBreakdownSegment[]): ContextBreakdownSegment[] {
  return segs.map((s) => ({
    key: s.key,
    label: s.label,
    tokens: s.tokens,
    children: s.children == null ? undefined : normalizeSegments(s.children),
    metadata: s.metadata ?? undefined,
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
      observation: raw.observation ?? undefined,
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

// ─── Unknown Tool outcome resolution ──────────────────────────────────────
// These DTOs intentionally mirror the backend's closed request/discovery/ACK
// records. There is no generic response envelope on either endpoint.
export type UnknownOutcomeAction =
  | 'CONTINUE_CURRENT_TIMELINE'
  | 'PREPARE_RESTORE';

export type UnknownOutcomeInboxDispositionKind =
  | 'KEEP_FOR_CONTINUE'
  | 'KEEP_FOR_RESTORE'
  | 'DISCARD_FOR_RESTORE';

export interface UnknownOutcomeInboxDisposition {
  inboxId: string;
  disposition: UnknownOutcomeInboxDispositionKind;
}

export interface UnknownOutcomeToolCall {
  providerOrdinal: number;
  toolUseId: string;
  toolName: string;
  /** Exact JSON display projection created from the server-verified immutable manifest. */
  input: string;
}

export interface UnknownOutcomeTarget {
  sessionId: string;
  attemptId: number;
  historyEpoch: number;
  executionGeneration: number;
  executionFence: number;
  state: 'UNCERTAIN_PENDING_RESOLUTION';
  actorAuthority: 'OWNER' | 'ADMIN';
  calls: UnknownOutcomeToolCall[];
  inboxIds: string[];
}

export function isUnknownOutcomeTarget(
  value: unknown,
  expectedSessionId: string,
): value is UnknownOutcomeTarget {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  const row = value as Record<string, unknown>;
  const expectedKeys = [
    'sessionId', 'attemptId', 'historyEpoch', 'executionGeneration',
    'executionFence', 'state', 'actorAuthority', 'calls', 'inboxIds',
  ];
  if (Object.keys(row).length !== expectedKeys.length
      || !expectedKeys.every((key) => Object.hasOwn(row, key))) return false;
  if (row.sessionId !== expectedSessionId
    || !Number.isSafeInteger(row.attemptId) || (row.attemptId as number) <= 0
    || !Number.isSafeInteger(row.historyEpoch) || (row.historyEpoch as number) < 0
    || !Number.isSafeInteger(row.executionGeneration) || (row.executionGeneration as number) <= 0
    || !Number.isSafeInteger(row.executionFence) || (row.executionFence as number) < 0
    || row.state !== 'UNCERTAIN_PENDING_RESOLUTION'
    || (row.actorAuthority !== 'OWNER' && row.actorAuthority !== 'ADMIN')
    || !Array.isArray(row.calls) || row.calls.length === 0
    || !Array.isArray(row.inboxIds)
    || !row.inboxIds.every((id) => typeof id === 'string' && id.length > 0)
    || new Set(row.inboxIds).size !== row.inboxIds.length) return false;
  const toolUseIds = new Set<string>();
  return row.calls.every((candidate, index) => {
    if (typeof candidate !== 'object' || candidate === null || Array.isArray(candidate)) {
      return false;
    }
    const call = candidate as Record<string, unknown>;
    if (Object.keys(call).length !== 4
      || !Object.hasOwn(call, 'providerOrdinal')
      || !Object.hasOwn(call, 'toolUseId')
      || !Object.hasOwn(call, 'toolName')
      || !Object.hasOwn(call, 'input')
      || call.providerOrdinal !== index
      || typeof call.toolUseId !== 'string' || call.toolUseId.length === 0
      || typeof call.toolName !== 'string' || call.toolName.length === 0
      || typeof call.input !== 'string' || call.input.length === 0
      || toolUseIds.has(call.toolUseId)) return false;
    try {
      const input = JSON.parse(call.input) as unknown;
      if (typeof input !== 'object' || input === null || Array.isArray(input)) return false;
    } catch {
      return false;
    }
    toolUseIds.add(call.toolUseId);
    return true;
  });
}

export interface UnknownOutcomeResolutionRequest {
  resolutionRequestId: string;
  expectedHistoryEpoch: number;
  expectedExecutionGeneration: number;
  expectedExecutionFence: number;
  action: UnknownOutcomeAction;
  reason: string;
  inboxDispositions: UnknownOutcomeInboxDisposition[];
}

export interface UnknownOutcomeResolutionAck {
  resolutionRequestId: string;
  sessionId: string;
  attemptId: number;
  stepId: string;
  historyEpoch: number;
  executionGeneration: number;
  executionFence: number;
  actorAuthority: 'OWNER' | 'ADMIN';
  action: UnknownOutcomeAction;
  resultBatchId: string;
  outcomeState: 'RESOLVED_UNKNOWN';
  inboxDispositions: UnknownOutcomeInboxDisposition[];
  postActionState: 'PENDING' | 'NONE';
  restorePreparing: boolean;
  auditId: number;
  resolvedAt: string;
}

export function isUnknownOutcomeResolutionAck(
  value: unknown,
  target: UnknownOutcomeTarget,
  command: UnknownOutcomeResolutionRequest,
): value is UnknownOutcomeResolutionAck {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  const row = value as Record<string, unknown>;
  const expectedKeys = [
    'resolutionRequestId', 'sessionId', 'attemptId', 'stepId', 'historyEpoch',
    'executionGeneration', 'executionFence', 'actorAuthority', 'action',
    'resultBatchId', 'outcomeState', 'inboxDispositions', 'postActionState',
    'restorePreparing', 'auditId', 'resolvedAt',
  ];
  if (Object.keys(row).length !== expectedKeys.length
      || !expectedKeys.every((key) => Object.hasOwn(row, key))) return false;
  if (row.resolutionRequestId !== command.resolutionRequestId
      || row.sessionId !== target.sessionId
      || row.attemptId !== target.attemptId
      || row.historyEpoch !== target.historyEpoch
      || row.executionGeneration !== target.executionGeneration
      || row.executionFence !== target.executionFence
      || (row.actorAuthority !== 'OWNER' && row.actorAuthority !== 'ADMIN')
      || row.action !== command.action
      || row.outcomeState !== 'RESOLVED_UNKNOWN'
      || !Number.isSafeInteger(row.auditId) || (row.auditId as number) <= 0
      || typeof row.stepId !== 'string' || row.stepId.length === 0
      || typeof row.resultBatchId !== 'string' || row.resultBatchId.length === 0
      || typeof row.resolvedAt !== 'string' || Number.isNaN(Date.parse(row.resolvedAt))
      || !Array.isArray(row.inboxDispositions)) return false;
  const continueAction = command.action === 'CONTINUE_CURRENT_TIMELINE';
  if (row.postActionState !== (continueAction ? 'PENDING' : 'NONE')
      || row.restorePreparing !== !continueAction
      || row.inboxDispositions.length !== command.inboxDispositions.length) return false;
  return row.inboxDispositions.every((candidate, index) => {
    if (typeof candidate !== 'object' || candidate === null || Array.isArray(candidate)) return false;
    const disposition = candidate as Record<string, unknown>;
    const expected = command.inboxDispositions[index];
    return Object.keys(disposition).length === 2
      && Object.hasOwn(disposition, 'inboxId')
      && Object.hasOwn(disposition, 'disposition')
      && disposition.inboxId === expected.inboxId
      && disposition.disposition === expected.disposition;
  });
}

export const getUnknownOutcomeTarget = (sessionId: string) =>
  api.get<UnknownOutcomeTarget>(
    `/sessions/${sessionId}/tool-attempts/unknown-outcome`,
  );

export const resolveUnknownOutcome = (
  sessionId: string,
  attemptId: number,
  command: UnknownOutcomeResolutionRequest,
) => api.post<UnknownOutcomeResolutionAck>(
  `/sessions/${sessionId}/tool-attempts/${attemptId}/resolve-unknown`,
  command,
);
