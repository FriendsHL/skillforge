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
export type SpanKind = 'llm' | 'tool';

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

export type SpanSummary = LlmSpanSummary | ToolSpanSummary;

export function isLlmSpanSummary(s: SpanSummary): s is LlmSpanSummary {
  return s.kind === 'llm';
}

export function isToolSpanSummary(s: SpanSummary): s is ToolSpanSummary {
  return s.kind === 'tool';
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
