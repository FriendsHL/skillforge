/**
 * OBS-1 Session Trace — observability DTOs (mirrors backend records frozen at git
 * tag `obs-1-dto-freeze`).
 *
 * Backend source of truth:
 *   skillforge-server/src/main/java/com/skillforge/server/controller/observability/dto/
 *     - SpanSummaryDto.java        sealed, @JsonTypeInfo property="kind"
 *     - LlmSpanSummaryDto.java     kind="llm"
 *     - ToolSpanSummaryDto.java    kind="tool", subagentSessionId on tool only (R3-WN2)
 *     - LlmSpanDetailDto.java      blob meta nested under `blobs: BlobMetaDto`
 *     - ToolSpanDetailDto.java     full input/output text + subagentSessionId
 *     - BlobMetaDto.java           hasRawRequest/hasRawResponse/hasRawSse + sizes
 *
 * Any backend DTO change must SendMessage Frontend Dev and re-tag (plan §10.3).
 */

/** Blob status for an LLM span's raw payload. */
export type BlobStatus = 'ok' | 'legacy' | 'write_failed' | 'truncated';

/** Source of an LLM span row. */
export type LlmSpanSource = 'live' | 'legacy';

/** What part of a blob to fetch from the controlled blob endpoint. */
export type BlobPart = 'request' | 'response' | 'sse';

/** Discriminator value for {@link SpanSummary}. */
export type SpanKind = 'llm' | 'tool' | 'event';

/**
 * OBS-2 M3 — discriminator value for event spans (`kind='event'`). Mirrors the
 * 4 lifecycle event types the engine emits via `t_trace_span` today
 * (ASK_USER / INSTALL_CONFIRM / COMPACT / AGENT_CONFIRM); on the new path each
 * row is a `t_llm_span where kind='event'` carrying `event_type` lowercase.
 *
 * The `EventSpanType` literal union is derived from {@link VALID_EVENT_TYPES}
 * so adding a new type touches a single source of truth + the runtime guard
 * {@link normalizeEventType} catches BE drift at parse time.
 */
export const VALID_EVENT_TYPES = [
  'ask_user',
  'install_confirm',
  'compact',
  'agent_confirm',
] as const;

export type EventSpanType = typeof VALID_EVENT_TYPES[number];

/**
 * Runtime guard for `EventSpanSummaryDto.eventType` / `EventSpanDetailDto.eventType`.
 *
 * Catches BE drift: a future backend that ships a 5th event type (e.g.
 * `tool_confirm`) without a coordinated FE update would otherwise be silently
 * cast through. Logs once via `console.warn` and falls back to `'ask_user'`
 * so the UI keeps rendering instead of throwing.
 */
export function normalizeEventType(raw: unknown): EventSpanType {
  if (typeof raw === 'string'
      && (VALID_EVENT_TYPES as readonly string[]).includes(raw)) {
    return raw as EventSpanType;
  }
  // eslint-disable-next-line no-console
  console.warn('[obs-2] Unknown eventType from backend; falling back to ask_user:', raw);
  return 'ask_user';
}

export interface LlmSpanSummary {
  kind: 'llm';
  spanId: string;
  traceId: string;
  parentSpanId: string | null;
  startedAt: string;        // ISO-8601
  endedAt: string | null;
  latencyMs: number;
  provider: string | null;  // ProviderName.CANONICAL value or null
  model: string | null;
  inputTokens: number;
  outputTokens: number;
  source: LlmSpanSource;
  stream: boolean;
  hasRawRequest: boolean;
  hasRawResponse: boolean;
  hasRawSse: boolean;
  blobStatus: BlobStatus | null;
  finishReason?: string | null;
  error?: string | null;
  errorType?: string | null;
}

export interface ToolSpanSummary {
  kind: 'tool';
  spanId: string;
  traceId: string;
  parentSpanId: string | null;
  startedAt: string;
  endedAt: string | null;
  latencyMs: number;
  toolName: string;
  toolUseId: string | null;
  success: boolean;
  error?: string | null;
  inputPreview?: string | null;
  outputPreview?: string | null;
  /** R3-WN2: present only when `toolName === 'SubAgent'` and the resolver locates
   *  the child sessionId. UI renders SubagentJumpLink iff this is non-null. */
  subagentSessionId?: string | null;
}

/**
 * OBS-2 M3 — event span summary. Mirrors backend `EventSpanSummaryDto`
 * (`kind='event'`). 4 event types emitted by AgentLoopEngine:
 *  - ask_user (interactive prompt)
 *  - install_confirm (skill install confirmation)
 *  - compact (context compaction trigger)
 *  - agent_confirm (CreateAgent confirmation)
 *
 * Field layout intentionally mirrors {@link ToolSpanSummary} (preview-only) —
 * full input/output is fetched on demand via `getEventSpanDetail`.
 */
export interface EventSpanSummary {
  kind: 'event';
  spanId: string;
  traceId: string;
  parentSpanId: string | null;
  startedAt: string;
  endedAt: string | null;
  latencyMs: number;
  eventType: EventSpanType;
  name: string;
  success: boolean;
  error: string | null;
  inputPreview: string | null;
  outputPreview: string | null;
}

export type SpanSummary = LlmSpanSummary | ToolSpanSummary | EventSpanSummary;

export function isLlmSpanSummary(s: SpanSummary): s is LlmSpanSummary {
  return s.kind === 'llm';
}

export function isToolSpanSummary(s: SpanSummary): s is ToolSpanSummary {
  return s.kind === 'tool';
}

export function isEventSpanSummary(s: SpanSummary): s is EventSpanSummary {
  return s.kind === 'event';
}

export interface SessionSpansResponse {
  spans: SpanSummary[];
  hasMore: boolean;
}

/**
 * Mirror of backend `BlobMetaDto`. The `*Size` fields are `Long` on the JVM
 * side and may be null when the blob is absent. Sizes are reported in bytes.
 */
export interface BlobMeta {
  hasRawRequest: boolean;
  hasRawResponse: boolean;
  hasRawSse: boolean;
  rawRequestSize?: number | null;
  rawResponseSize?: number | null;
  rawSseSize?: number | null;
}

/**
 * Mirror of backend `LlmSpanDetailDto`.
 *
 * Notes vs the summary DTO:
 *   - blob presence is nested under `blobs` (not flat hasRawXxx fields)
 *   - per-call token counts are exposed via the summary DTO; the detail DTO
 *     surfaces only `cacheReadTokens` + the raw provider `usage` payload
 *   - `usage` is opaque (Java `Object`) — keep `unknown` and let the UI
 *     stringify on render
 *   - inputSummary/outputSummary are truncated to ≤ 32KB by backend; full
 *     bytes accessible via the blob endpoint
 */
export interface LlmSpanDetail {
  spanId: string;
  traceId: string;
  parentSpanId: string | null;
  sessionId: string;
  provider: string | null;
  model: string | null;
  iterationIndex: number;
  stream: boolean;
  inputSummary: string | null;
  outputSummary: string | null;
  cacheReadTokens?: number | null;
  usage?: unknown;
  costUsd?: number | null;
  latencyMs: number;
  startedAt: string;
  endedAt: string | null;
  finishReason?: string | null;
  requestId?: string | null;
  reasoningContent?: string | null;
  error?: string | null;
  errorType?: string | null;
  source: LlmSpanSource;
  blobStatus: BlobStatus | null;
  blobs: BlobMeta;
}

/** Mirror of backend `ToolSpanDetailDto`. */
export interface ToolSpanDetail {
  spanId: string;
  traceId: string;
  parentSpanId: string | null;
  sessionId: string;
  toolName: string;
  toolUseId: string | null;
  success: boolean;
  error?: string | null;
  /** Full text up to backend truncation (~65k). */
  input?: string | null;
  /** Full text up to backend truncation (~65k). */
  output?: string | null;
  startedAt: string;
  endedAt: string | null;
  latencyMs: number;
  iterationIndex: number;
  /** R3-WN2: present iff toolName='SubAgent' AND resolver located child. */
  subagentSessionId?: string | null;
}

/**
 * OBS-2 M3 — mirror of backend `EventSpanDetailDto`.
 *
 * Simpler than {@link ToolSpanDetail} because event spans don't have
 * tool_use_id pairing or subagent linkage. Full input/output preserved up to
 * backend truncation (≈ 65k).
 */
export interface EventSpanDetail {
  spanId: string;
  traceId: string;
  parentSpanId: string | null;
  sessionId: string;
  eventType: EventSpanType;
  name: string;
  success: boolean;
  error?: string | null;
  /** Full text up to backend truncation (~65k). */
  input?: string | null;
  /** Full text up to backend truncation (~65k). */
  output?: string | null;
  startedAt: string;
  endedAt: string | null;
  latencyMs: number;
  iterationIndex: number;
}

/** Mirror of backend `LlmTraceDto`. */
export interface LlmTrace {
  traceId: string;
  sessionId: string;
  agentId: number | null;
  userId: number | null;
  rootName: string | null;
  startedAt: string;
  endedAt: string | null;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCostUsd: number | null;
  source: LlmSpanSource;
  spans: LlmSpanSummary[];
}

/* ─── OBS-3 Unified Trace Tree ───────────────────────────────────────────────
 * Mirror of backend OBS-3 DTOs (TraceWithDescendantsDto, DescendantTraceDto,
 * UnifiedSpanDto). See docs/requirements/active/2026-05-03-OBS-3-unified-trace-tree
 * tech-design §1.2 for the canonical Java records.
 *
 * Contract: any backend DTO change must SendMessage Frontend Dev (mirrors the
 * obs-1-dto-freeze pattern at the top of this file).
 */

/** OBS-3 — descendant trace lifecycle status. Mirrors `t_llm_trace.status`. */
export type DescendantTraceStatus = 'running' | 'ok' | 'error' | 'cancelled';

/**
 * OBS-3 — slim trace summary (no spans). Mirrors BE `LlmTraceSummaryDto`.
 * Distinct from {@link LlmTrace} above (which carries inline spans) — the
 * unified-tree response splits trace metadata from spans because spans are
 * returned as a single cross-trace timeline (`UnifiedSpan[]`).
 */
export interface LlmTraceSummary {
  traceId: string;
  sessionId: string;
  agentId: number | null;
  userId: number | null;
  rootName: string | null;
  agentName: string | null;
  status: DescendantTraceStatus;
  error: string | null;
  startedAt: string;
  endedAt: string | null;
  totalDurationMs: number;
  toolCallCount: number;
  eventCount: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCostUsd: number | null;
  source: LlmSpanSource;
}

/**
 * OBS-3 — descendant trace metadata used to render nested sub-tree headers
 * (TeamCreate / SubAgent rows) and child status badges. One entry per
 * descendant trace in DFS order.
 */
export interface DescendantTraceMeta {
  traceId: string;
  sessionId: string;
  /** 1 = direct child, 2 = grandchild, 3 = great-grandchild (BE caps at 3). */
  depth: number;
  /** trace_id of the parent in the tree (depth-1 layer). */
  parentTraceId: string;
  /**
   * span_id of the dispatch span (TeamCreate / SubAgent tool span) that
   * triggered this descendant. May be null when the resolver fails to
   * associate (`tech-design §1.4 #5`); UI then renders the sub-tree at the
   * end of the parent.
   */
  parentSpanId: string | null;
  /**
   * Agent name. BE may return null when the child session lacks an agent
   * binding (rare but possible per BE contract 2026-05-03). UI falls back
   * to a short trace_id display.
   */
  agentName: string | null;
  status: DescendantTraceStatus;
  totalDurationMs: number;
  toolCallCount: number;
  eventCount: number;
}

/**
 * OBS-3 — span row tagged with depth + parent trace, for unified rendering.
 * `depth=0` means the span belongs to the root trace; `depth>0` means it
 * belongs to a descendant trace (use `parentTraceId` to look up the
 * `DescendantTraceMeta`).
 */
export interface UnifiedSpan {
  span: SpanSummary;
  depth: number;
  /** null for root-trace spans, non-null for descendant spans. */
  parentTraceId: string | null;
}

/**
 * OBS-3 — response of `GET /api/traces/{traceId}/with_descendants`. Mirrors
 * backend `TraceWithDescendantsDto`. `rootTrace` is the LlmTrace summary;
 * descendants/spans are pre-sorted by the BE (DFS for descendants, started_at
 * ASC for spans across all traces).
 */
export interface TraceWithDescendants {
  rootTrace: LlmTraceSummary;
  descendants: DescendantTraceMeta[];
  spans: UnifiedSpan[];
  /** True when descendants were truncated to `max_descendants`. */
  truncated: boolean;
}
