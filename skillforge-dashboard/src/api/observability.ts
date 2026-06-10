import api from './client';

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
