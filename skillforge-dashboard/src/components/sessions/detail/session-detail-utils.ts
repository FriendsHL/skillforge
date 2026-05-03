/**
 * SessionDetail page utilities — formatters and DTO converters shared across
 * the trace sidebar, waterfall panel, fold-team / child-summary rows, and
 * detail panel.
 *
 * Originally each component re-defined `fmtMs` (5 copies) and the
 * `traceSpanToSummary` converter lived in `SessionWaterfallPanel.tsx`.
 * Re-exporting a non-component value from a component file violates
 * `react-refresh/only-export-components` (HMR degrades to full reload), so
 * OBS-4 M3 r1 follow-up consolidates them here.
 */

import {
  normalizeEventType,
  type SpanSummary,
  type TraceSpanDto,
} from '../../../types/observability';

/**
 * Human-readable millisecond formatter. <1s → "Nms", <1m → "N.NNs", else
 * "N.Nm". Used for waterfall durations, trace stats, fold-team child totals.
 */
export function fmtMs(ms: number): string {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
  return (ms / 60000).toFixed(1) + 'm';
}

/**
 * OBS-4 M3 — convert a `TraceSpanDto` (from `GET /api/traces/{rootId}/tree`)
 * to the `SpanSummary` discriminated union the panel + detail views expect.
 *
 * Fields not provided by the tree DTO (provider / blob meta / toolUseId /
 * subagentSessionId) default to nulls; deep-dive detail views re-fetch by
 * spanId via `getLlmSpanDetail` / `getToolSpanDetail` / `getEventSpanDetail`,
 * so the missing summary fields don't gate detail rendering.
 *
 * `s.kind` is a string-literal union BE-side so this function exhaustively
 * narrows via `if (s.kind === 'llm') / 'event'` and falls through to the
 * tool branch for the default case.
 */
export function traceSpanToSummary(s: TraceSpanDto, traceId: string): SpanSummary {
  if (s.kind === 'llm') {
    return {
      kind: 'llm',
      spanId: s.spanId,
      traceId,
      parentSpanId: s.parentSpanId,
      startedAt: s.startedAt,
      endedAt: s.endedAt,
      latencyMs: s.latencyMs,
      provider: null,
      model: s.model,
      inputTokens: s.inputTokens,
      outputTokens: s.outputTokens,
      source: 'live',
      stream: false,
      hasRawRequest: false,
      hasRawResponse: false,
      hasRawSse: false,
      blobStatus: null,
      finishReason: null,
      error: s.error,
      errorType: null,
    };
  }
  if (s.kind === 'event') {
    return {
      kind: 'event',
      spanId: s.spanId,
      traceId,
      parentSpanId: s.parentSpanId,
      startedAt: s.startedAt,
      endedAt: s.endedAt,
      latencyMs: s.latencyMs,
      eventType: normalizeEventType(s.eventType),
      name: s.name ?? '',
      success: s.status === 'ok',
      error: s.error,
      inputPreview: s.inputSummary,
      outputPreview: s.outputSummary,
    };
  }
  // Default: tool kind.
  return {
    kind: 'tool',
    spanId: s.spanId,
    traceId,
    parentSpanId: s.parentSpanId,
    startedAt: s.startedAt,
    endedAt: s.endedAt,
    latencyMs: s.latencyMs,
    toolName: s.name ?? '',
    toolUseId: null,
    success: s.status === 'ok',
    error: s.error,
    inputPreview: s.inputSummary,
    outputPreview: s.outputSummary,
    subagentSessionId: null,
  };
}
