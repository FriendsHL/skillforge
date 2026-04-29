import React from 'react';
import type { SpanSummary } from '../../../types/observability';

interface SessionTimelineRowProps {
  span: SpanSummary;
  selected: boolean;
  onSelect: (span: SpanSummary) => void;
}

function shortenLabel(span: SpanSummary): string {
  if (span.kind === 'llm') {
    const parts: string[] = [];
    if (span.provider) parts.push(span.provider);
    if (span.model) parts.push(span.model);
    if (parts.length === 0) parts.push('llm');
    return parts.join(' · ');
  }
  return span.toolName || 'tool';
}

function statusTone(span: SpanSummary): 'ok' | 'err' | 'warn' {
  if (span.kind === 'llm') return span.error ? 'err' : 'ok';
  return span.success ? 'ok' : 'err';
}

const SessionTimelineRow: React.FC<SessionTimelineRowProps> = ({ span, selected, onSelect }) => {
  const tone = statusTone(span);
  return (
    <button
      type="button"
      className={`obs-timeline-row obs-timeline-row--${span.kind} ${selected ? 'is-selected' : ''} obs-timeline-row--${tone}`}
      onClick={() => onSelect(span)}
      aria-pressed={selected}
    >
      <span className={`obs-kind-tag obs-kind-tag--${span.kind}`}>{span.kind}</span>
      <span className="obs-timeline-row-label mono-sm">{shortenLabel(span)}</span>
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
