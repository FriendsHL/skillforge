import React from 'react';
import type { SpanSummary } from '../../../types/observability';
import LlmSpanDetailView from './LlmSpanDetailView';
import ToolSpanDetailView from './ToolSpanDetailView';
import EventSpanDetailView from './EventSpanDetailView';
import { fmtMs } from './session-detail-utils';

interface TraceOverview {
  id: string;
  name: string;
  input: string;
  output: string;
  status: 'ok' | 'error';
  totalMs: number;
  tokensIn: number;
  tokensOut: number;
  model: string;
  startTime: string;
}

interface TraceDetailPanelProps {
  /** Selected trace overview info */
  trace: TraceOverview | null;
  /** Selected span - if set, show span detail instead of trace overview */
  span: SpanSummary | null;
}

function fmtTime(iso: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  const now = Date.now();
  const diff = now - d.getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  return d.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function copyText(text: string) {
  if (typeof navigator !== 'undefined' && navigator.clipboard) {
    navigator.clipboard.writeText(text).catch(() => {});
  }
}

/**
 * Right pane that shows either:
 * - Trace overview (user input + assistant output) when no span selected
 * - Span detail (LLM/Tool input/output) when a span is selected
 *
 * Visual style mirrors the Traces tab `tr-span-detail` layout for consistency.
 */
const TraceDetailPanel: React.FC<TraceDetailPanelProps> = ({ trace, span }) => {
  // Span selected → delegate to existing data-fetching detail views,
  // wrapped in tr-span-detail so spacing/borders match the trace tab.
  if (span) {
    return (
      <aside className="tr-span-detail">
        <div className="tr-span-detail-h">
          <div className="tr-span-detail-title">
            <span className={`tr-kind-tag k-${span.kind}`}>{span.kind}</span>
            <b className="mono-sm">{span.spanId.slice(0, 12)}</b>
            <span className="kv-chip-sf">{fmtTime(span.startedAt)}</span>
          </div>
        </div>
        <div className="tr-span-detail-body">
          {span.kind === 'llm' && (
            <LlmSpanDetailView key={span.spanId} span={span} />
          )}
          {span.kind === 'tool' && (
            <ToolSpanDetailView key={span.spanId} span={span} />
          )}
          {span.kind === 'event' && (
            <EventSpanDetailView key={span.spanId} span={span} />
          )}
        </div>
      </aside>
    );
  }

  // Trace selected → show full agent call overview (query + output).
  if (trace) {
    const totalTokens = trace.tokensIn + trace.tokensOut;
    return (
      <aside className="tr-span-detail">
        <div className="tr-span-detail-h">
          <div className="tr-span-detail-title">
            <span className="tr-kind-tag k-agent">agent</span>
            <b className="mono-sm">{trace.name || 'Agent loop'}</b>
            <span
              className="kv-chip-sf"
              style={trace.status === 'error' ? { color: 'var(--color-err)' } : undefined}
            >
              {trace.status}
            </span>
            <span className="kv-chip-sf">{fmtMs(trace.totalMs)}</span>
            {totalTokens > 0 && (
              <span className="kv-chip-sf">{totalTokens.toLocaleString()} tok</span>
            )}
          </div>
          <div className="tr-run-header-sub">
            <span>{trace.id.slice(0, 16)}</span>
            <span>·</span>
            <span>{fmtTime(trace.startTime)}</span>
            {trace.model && trace.model !== '—' && (
              <>
                <span>·</span>
                <span className="mono-sm">{trace.model}</span>
              </>
            )}
          </div>
        </div>

        <div className="tr-span-detail-body">
          {/* User query */}
          <div className="tr-io-block">
            <div className="tr-io-label">
              <span>User query</span>
              <button type="button" className="mini-btn" onClick={() => copyText(trace.input)}>
                copy
              </button>
            </div>
            <pre className="tr-io-pre">{trace.input || '—'}</pre>
          </div>

          {/* Assistant result */}
          <div className="tr-io-block">
            <div className="tr-io-label">
              <span>Assistant result</span>
              <button type="button" className="mini-btn" onClick={() => copyText(trace.output)}>
                copy
              </button>
            </div>
            <pre className={`tr-io-pre ${trace.status === 'error' ? 'err' : ''}`}>
              {trace.output || '—'}
            </pre>
          </div>
        </div>
      </aside>
    );
  }

  return (
    <aside className="tr-span-detail">
      <div className="tr-empty">Select a trace or span to view details.</div>
    </aside>
  );
};

export default TraceDetailPanel;
export type { TraceOverview };
