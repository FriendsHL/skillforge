import React, { useMemo } from 'react';
import type { SpanSummary } from '../../../types/observability';
import SessionTimelineRow from './SessionTimelineRow';

export interface TimelineMessage {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  /** ISO-8601 — used to order vs span startedAt. */
  createdAt: string;
  /** Plain text preview rendered inline. */
  text: string;
}

interface SessionTimelinePanelProps {
  messages: TimelineMessage[];
  spans: SpanSummary[];
  selectedSpanId: string | null;
  onSelectSpan: (span: SpanSummary) => void;
}

interface MergedItem {
  ts: number;
  kind: 'message' | 'span';
  message?: TimelineMessage;
  span?: SpanSummary;
}

function tsOf(iso: string | null | undefined): number {
  if (!iso) return 0;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : 0;
}

/**
 * Merges chat messages with observability spans by `createdAt` / `startedAt`.
 *
 * Per plan §8.3: backend already returns spans sorted by `started_at ASC`; the
 * frontend's only job is to interleave them with chat messages and let the
 * user click any span to load its detail view in the right pane.
 */
const SessionTimelinePanel: React.FC<SessionTimelinePanelProps> = ({
  messages,
  spans,
  selectedSpanId,
  onSelectSpan,
}) => {
  const items = useMemo<MergedItem[]>(() => {
    const merged: MergedItem[] = [];
    for (const m of messages) {
      merged.push({ ts: tsOf(m.createdAt), kind: 'message', message: m });
    }
    for (const s of spans) {
      merged.push({ ts: tsOf(s.startedAt), kind: 'span', span: s });
    }
    merged.sort((a, b) => {
      if (a.ts !== b.ts) return a.ts - b.ts;
      // Stable: messages before spans on tie so a tool_use bubble groups visually.
      if (a.kind !== b.kind) return a.kind === 'message' ? -1 : 1;
      return 0;
    });
    return merged;
  }, [messages, spans]);

  if (items.length === 0) {
    return (
      <div className="obs-timeline-panel obs-timeline-panel--empty">
        <div className="obs-empty-state">No timeline events yet.</div>
      </div>
    );
  }

  return (
    <div className="obs-timeline-panel">
      {items.map((it, idx) => {
        if (it.kind === 'message' && it.message) {
          const m = it.message;
          return (
            <div key={`m-${m.id}-${idx}`} className={`obs-timeline-msg obs-timeline-msg--${m.role}`}>
              <div className="obs-timeline-msg-head">
                <span className="obs-timeline-msg-role">{m.role}</span>
                <span className="obs-timeline-msg-when mono-sm">{m.createdAt}</span>
              </div>
              <div className="obs-timeline-msg-text">{m.text}</div>
            </div>
          );
        }
        if (it.kind === 'span' && it.span) {
          const s = it.span;
          return (
            <SessionTimelineRow
              key={`s-${s.spanId}`}
              span={s}
              selected={s.spanId === selectedSpanId}
              onSelect={onSelectSpan}
            />
          );
        }
        return null;
      })}
    </div>
  );
};

export default SessionTimelinePanel;
