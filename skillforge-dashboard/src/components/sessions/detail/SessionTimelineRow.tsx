import React from 'react';
import type { SpanSummary } from '../../../types/observability';

interface SessionTimelineRowProps {
  span: SpanSummary;
  selected: boolean;
  onSelect: (span: SpanSummary) => void;
  /** Timeline bounds for time bar rendering */
  timeRange?: { startMs: number; totalMs: number };
}

function shortenLabel(span: SpanSummary): string {
  if (span.kind === 'llm') {
    const parts: string[] = [];
    if (span.provider) parts.push(span.provider);
    if (span.model) parts.push(span.model);
    if (parts.length === 0) parts.push('llm');
    return parts.join(' · ');
  }
  if (span.kind === 'event') {
    return span.name || span.eventType || 'event';
  }
  return span.toolName || 'tool';
}

function statusTone(span: SpanSummary): 'ok' | 'err' | 'warn' {
  if (span.kind === 'llm') return span.error ? 'err' : 'ok';
  // tool & event both expose `success`
  return span.success ? 'ok' : 'err';
}

function tsOf(iso: string | null | undefined): number {
  if (!iso) return 0;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : 0;
}

const SessionTimelineRow: React.FC<SessionTimelineRowProps> = ({
  span,
  selected,
  onSelect,
  timeRange,
}) => {
  const tone = statusTone(span);

  // Calculate time bar position if timeRange is provided
  let barLeft = 0;
  let barWidth = 0;
  if (timeRange && timeRange.totalMs > 0) {
    const spanStart = tsOf(span.startedAt);
    barLeft = Math.max(0, Math.min(100, ((spanStart - timeRange.startMs) / timeRange.totalMs) * 100));
    // Scale latency to visual width (min 2%, max based on actual proportion)
    const latencyRatio = span.latencyMs / timeRange.totalMs;
    barWidth = Math.max(2, Math.min(100 - barLeft, latencyRatio * 100 * 3)); // *3 for visibility
  }

  return (
    <button
      type="button"
      className={`obs-timeline-row obs-timeline-row--${span.kind} ${selected ? 'is-selected' : ''} obs-timeline-row--${tone}`}
      onClick={() => onSelect(span)}
      aria-pressed={selected}
    >
      <span className={`obs-kind-tag obs-kind-tag--${span.kind}`}>{span.kind}</span>
      <span className="obs-timeline-row-label mono-sm">{shortenLabel(span)}</span>
      
      {/* Time bar visualization */}
      {timeRange && timeRange.totalMs > 0 && (
        <div className="obs-timeline-row-bar-track">
          <div
            className={`obs-timeline-row-bar obs-timeline-row-bar--${span.kind} ${tone === 'err' ? 'obs-timeline-row-bar--err' : ''}`}
            style={{ left: `${barLeft}%`, width: `${barWidth}%` }}
          />
        </div>
      )}
      
      <span className="obs-timeline-row-meta mono-sm">{span.latencyMs}ms</span>
      {span.kind === 'llm' && (
        <span className="obs-timeline-row-meta mono-sm">
          {(span.inputTokens + span.outputTokens).toLocaleString()} tok
        </span>
      )}
      {span.kind === 'tool' && span.toolName === 'SubAgent' && span.subagentSessionId && (
        <span className="obs-timeline-row-badge">→ subagent</span>
      )}
    </button>
  );
};

export default SessionTimelineRow;