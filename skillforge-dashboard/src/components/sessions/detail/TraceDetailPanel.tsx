import React from 'react';
import type { SpanSummary } from '../../../types/observability';
import LlmSpanDetailView from './LlmSpanDetailView';
import ToolSpanDetailView from './ToolSpanDetailView';

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

function fmtMs(ms: number): string {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  return (ms / 60000).toFixed(1) + 'm';
}

function fmtTime(iso: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Right pane that shows either:
 * - Trace overview (user input + assistant output) when no span selected
 * - Span detail (LLM/Tool input/output) when a span is selected
 */
const TraceDetailPanel: React.FC<TraceDetailPanelProps> = ({ trace, span }) => {
  // If span is selected, show span detail (components fetch their own data)
  if (span) {
    return (
      <aside className="obs-trace-detail-panel">
        <header className="obs-trace-detail-head">
          <span className={`obs-kind-tag obs-kind-tag--${span.kind}`}>{span.kind}</span>
          <span className="mono-sm obs-trace-detail-id">{span.spanId.slice(0, 12)}</span>
          <span className="obs-trace-detail-when mono-sm">{fmtTime(span.startedAt)}</span>
        </header>
        <div className="obs-trace-detail-body">
          {span.kind === 'llm' && (
            <LlmSpanDetailView key={span.spanId} span={span as any} />
          )}
          {span.kind === 'tool' && (
            <ToolSpanDetailView key={span.spanId} span={span as any} />
          )}
        </div>
      </aside>
    );
  }

  // If trace is selected but no span, show trace overview
  if (trace) {
    return (
      <aside className="obs-trace-detail-panel">
        <header className="obs-trace-detail-head">
          <span className={`obs-trace-status-tag ${trace.status === 'ok' ? 'is-ok' : 'is-error'}`}>
            {trace.status}
          </span>
          <span className="obs-trace-detail-name">{trace.name}</span>
          <span className="obs-trace-detail-when mono-sm">{fmtTime(trace.startTime)}</span>
        </header>

        {/* Stats bar */}
        <div className="obs-trace-stats-bar">
          <div className="obs-trace-stat">
            <span className="obs-trace-stat-label">Latency</span>
            <span className="obs-trace-stat-value mono-sm">{fmtMs(trace.totalMs)}</span>
          </div>
          <div className="obs-trace-stat">
            <span className="obs-trace-stat-label">Tokens</span>
            <span className="obs-trace-stat-value mono-sm">
              {trace.tokensIn + trace.tokensOut > 0 
                ? `${trace.tokensIn.toLocaleString()} in / ${trace.tokensOut.toLocaleString()} out`
                : '—'
              }
            </span>
          </div>
          <div className="obs-trace-stat">
            <span className="obs-trace-stat-label">Model</span>
            <span className="obs-trace-stat-value mono-sm">{trace.model || '—'}</span>
          </div>
        </div>

        {/* Input/Output sections */}
        <div className="obs-trace-detail-body">
          {/* User Input */}
          <div className="obs-trace-io-section">
            <div className="obs-trace-io-header">
              <span className="obs-trace-io-role obs-trace-io-role--user">User Input</span>
            </div>
            <div className="obs-trace-io-content">
              {trace.input ? (
                <pre className="obs-trace-io-pre">{trace.input}</pre>
              ) : (
                <div className="obs-trace-io-empty">No input recorded</div>
              )}
            </div>
          </div>

          {/* Assistant Output */}
          <div className="obs-trace-io-section">
            <div className="obs-trace-io-header">
              <span className="obs-trace-io-role obs-trace-io-role--assistant">Assistant Output</span>
            </div>
            <div className="obs-trace-io-content">
              {trace.output ? (
                <pre className="obs-trace-io-pre">{trace.output}</pre>
              ) : (
                <div className="obs-trace-io-empty">No output recorded</div>
              )}
            </div>
          </div>
        </div>
      </aside>
    );
  }

  // Nothing selected
  return (
    <aside className="obs-trace-detail-panel obs-trace-detail-panel--empty">
      <div className="obs-empty-state">Select a trace or span to view details.</div>
    </aside>
  );
};

export default TraceDetailPanel;
export type { TraceOverview };