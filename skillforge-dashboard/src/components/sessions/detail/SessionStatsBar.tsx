import React from 'react';
import type { SpanSummary } from '../../../types/observability';

interface SessionStatsBarProps {
  /**
   * OBS-2 M3 — spans for the currently selected trace (per-trace fetch).
   * Stats reflect the selected trace, not the whole session.
   */
  spans: SpanSummary[];
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60000).toFixed(1)}m`;
}

function formatTokens(n: number): string {
  if (n < 1000) return `${n}`;
  if (n < 10000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1000).toFixed(0)}k`;
}

/**
 * Per-trace stats summary shown in the SessionDetail header.
 *
 * OBS-2 M3 (PRD §4 Q7 decision C1): the legacy "Messages" field was removed
 * because per-trace mode makes it semantically ambiguous (which messages
 * "belong" to a trace?) and the remaining 4 columns already characterize the
 * trace. Re-introduce only with a clear semantic story.
 */
const SessionStatsBar: React.FC<SessionStatsBarProps> = ({ spans }) => {
  const llmSpans = spans.filter((s) => s.kind === 'llm');
  const toolSpans = spans.filter((s) => s.kind === 'tool');
  const eventSpans = spans.filter((s) => s.kind === 'event');

  const totalInputTokens = llmSpans.reduce((sum, s) => sum + s.inputTokens, 0);
  const totalOutputTokens = llmSpans.reduce((sum, s) => sum + s.outputTokens, 0);
  const totalTokens = totalInputTokens + totalOutputTokens;

  const totalLatencyMs = spans.reduce((sum, s) => sum + s.latencyMs, 0);

  const errorCount = spans.filter((s) => {
    if (s.kind === 'llm') return Boolean(s.error || s.errorType);
    return !s.success;
  }).length;

  return (
    <div className="obs-stats-bar">
      <div className="obs-stats-item">
        <span className="obs-stats-label">Spans</span>
        <span className="obs-stats-value">
          <span className="obs-stats-kind llm">{llmSpans.length}</span>
          <span className="obs-stats-sep">/</span>
          <span className="obs-stats-kind tool">{toolSpans.length}</span>
          {eventSpans.length > 0 && (
            <>
              <span className="obs-stats-sep">/</span>
              <span className="obs-stats-kind event">{eventSpans.length}</span>
            </>
          )}
        </span>
        <span className="obs-stats-hint">
          {eventSpans.length > 0 ? 'LLM / Tool / Event' : 'LLM / Tool'}
        </span>
      </div>

      <div className="obs-stats-item">
        <span className="obs-stats-label">Tokens</span>
        <span className="obs-stats-value mono-sm">{formatTokens(totalTokens)}</span>
        <span className="obs-stats-hint">
          {formatTokens(totalInputTokens)} in / {formatTokens(totalOutputTokens)} out
        </span>
      </div>

      <div className="obs-stats-item">
        <span className="obs-stats-label">Duration</span>
        <span className="obs-stats-value mono-sm">{formatMs(totalLatencyMs)}</span>
        <span className="obs-stats-hint">total latency</span>
      </div>

      {errorCount > 0 && (
        <div className="obs-stats-item obs-stats-item--error">
          <span className="obs-stats-label">Errors</span>
          <span className="obs-stats-value obs-stats-err-count">{errorCount}</span>
        </div>
      )}
    </div>
  );
};

export default SessionStatsBar;
