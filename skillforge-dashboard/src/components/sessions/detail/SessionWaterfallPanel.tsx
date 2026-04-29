import React, { useMemo, useState } from 'react';
import type { SpanSummary, LlmSpanSummary, ToolSpanSummary } from '../../../types/observability';

interface TimelineMessage {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  createdAt: string;
  text: string;
}

interface SessionWaterfallPanelProps {
  spans: SpanSummary[];
  messages: TimelineMessage[];
  selectedSpanId: string | null;
  onSelectSpan: (span: SpanSummary) => void;
  /** Only show spans from this turn index. If null, show all. */
  turnIndex?: number | null;
}

interface WaterfallRow {
  span: SpanSummary;
  startOffsetMs: number;
  durationMs: number;
  kind: 'llm' | 'tool';
  label: string;
  hasError: boolean;
}

interface FilterState {
  llm: boolean;
  tool: boolean;
  error: boolean;
}

function tsOf(iso: string | null | undefined): number {
  if (!iso) return 0;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : 0;
}

function fmtMs(ms: number): string {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's';
  return (ms / 60000).toFixed(1) + 'm';
}

function isLlmSpan(s: SpanSummary): s is LlmSpanSummary {
  return s.kind === 'llm';
}

function isToolSpan(s: SpanSummary): s is ToolSpanSummary {
  return s.kind === 'tool';
}

function getSpanLabel(span: SpanSummary): string {
  if (isLlmSpan(span)) {
    return span.modelId || 'LLM';
  }
  if (isToolSpan(span)) {
    return span.toolName || 'Tool';
  }
  return 'Span';
}

function getSpanError(span: SpanSummary): boolean {
  if (isLlmSpan(span)) {
    return span.errorType != null || span.error != null;
  }
  if (isToolSpan(span)) {
    return !span.success;
  }
  return false;
}

function calculateRows(
  spans: SpanSummary[],
  messages: TimelineMessage[],
  filter: FilterState,
  turnIndex?: number | null
): { rows: WaterfallRow[]; totalMs: number; stats: { llm: number; tool: number; error: number; total: number } } {
  // Stats from all spans (before filtering)
  const allStats = {
    llm: spans.filter(s => s.kind === 'llm').length,
    tool: spans.filter(s => s.kind === 'tool').length,
    error: spans.filter(s => getSpanError(s)).length,
    total: spans.length,
  };

  if (spans.length === 0) {
    return { rows: [], totalMs: 0, stats: allStats };
  }

  // Find turn boundaries from user messages
  const userMsgs = messages.filter(m => m.role === 'user');
  const userMsgTimes = userMsgs.map(m => tsOf(m.createdAt));

  // Filter spans by turn if specified
  let spansInTurn = spans;
  if (turnIndex != null && userMsgTimes.length > 0) {
    const turnStart = userMsgTimes[turnIndex] ?? 0;
    const turnEnd = userMsgTimes[turnIndex + 1] ?? Infinity;
    
    spansInTurn = spans.filter(span => {
      const spanStart = tsOf(span.startedAt);
      return spanStart >= turnStart && spanStart < turnEnd;
    });
  }

  if (spansInTurn.length === 0) {
    // Still return stats from all spans for filter chips
    return { rows: [], totalMs: 100, stats: allStats };
  }

  // Calculate time range for this turn
  const starts = spansInTurn.map(s => tsOf(s.startedAt));
  const minStart = Math.min(...starts);
  const maxEnd = Math.max(...starts.map((s, i) => s + spansInTurn[i].latencyMs));
  const totalTime = Math.max(maxEnd - minStart, 100);

  // Build rows
  const allRows: WaterfallRow[] = spansInTurn.map(span => ({
    span,
    startOffsetMs: tsOf(span.startedAt) - minStart,
    durationMs: span.latencyMs,
    kind: span.kind as 'llm' | 'tool',
    label: getSpanLabel(span),
    hasError: getSpanError(span),
  }));

  // Apply kind/error filter
  const filteredRows = allRows.filter(row => {
    if (filter.error) return row.hasError;
    return (filter.llm && row.kind === 'llm') || (filter.tool && row.kind === 'tool');
  });

  return { rows: filteredRows, totalMs: totalTime, stats: allStats };
}

const SessionWaterfallPanel: React.FC<SessionWaterfallPanelProps> = ({
  spans,
  messages,
  selectedSpanId,
  onSelectSpan,
  turnIndex,
}) => {
  const [filter, setFilter] = useState<FilterState>({ llm: true, tool: true, error: false });

  const { rows, totalMs, stats } = useMemo(
    () => calculateRows(spans, messages, filter, turnIndex),
    [spans, messages, filter, turnIndex]
  );

  const toggleFilter = (key: keyof FilterState) => {
    if (key === 'error') {
      setFilter(prev => ({
        llm: prev.error ? true : false,
        tool: prev.error ? true : false,
        error: !prev.error,
      }));
    } else {
      setFilter(prev => ({
        ...prev,
        [key]: !prev[key],
        error: false,
      }));
    }
  };

  if (spans.length === 0) {
    return (
      <div className="obs-waterfall-panel obs-waterfall-panel--empty">
        <div className="obs-empty-state">No spans in this session.</div>
      </div>
    );
  }

  return (
    <div className="obs-waterfall-panel">
      {/* Filter chips */}
      <div className="obs-waterfall-filter">
        <button
          type="button"
          className={`obs-filter-chip obs-filter-chip--llm ${filter.llm && !filter.error ? 'is-active' : ''}`}
          onClick={() => toggleFilter('llm')}
        >
          <span className="obs-filter-chip-dot obs-filter-chip-dot--llm" />
          LLM · {stats.llm}
        </button>
        <button
          type="button"
          className={`obs-filter-chip obs-filter-chip--tool ${filter.tool && !filter.error ? 'is-active' : ''}`}
          onClick={() => toggleFilter('tool')}
        >
          <span className="obs-filter-chip-dot obs-filter-chip-dot--tool" />
          Tool · {stats.tool}
        </button>
        <button
          type="button"
          className={`obs-filter-chip obs-filter-chip--error ${filter.error ? 'is-active' : ''}`}
          onClick={() => toggleFilter('error')}
        >
          <span className="obs-filter-chip-dot obs-filter-chip-dot--error" />
          Error · {stats.error}
        </button>
        <span className="obs-filter-chip-count mono-sm">
          {rows.length} shown
        </span>
      </div>

      {/* Header with time scale */}
      <div className="obs-waterfall-header">
        <div className="obs-waterfall-header-label">
          {turnIndex != null ? `Turn ${turnIndex + 1}` : 'Waterfall'}
        </div>
        <div className="obs-waterfall-timescale">
          {[0, 0.25, 0.5, 0.75, 1].map(t => (
            <div key={t} className="obs-waterfall-tick" style={{ left: `${t * 100}%` }}>
              <span className="mono-sm">{fmtMs(totalMs * t)}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Rows */}
      <div className="obs-waterfall-body">
        {rows.length === 0 ? (
          <div className="obs-empty-state">
            {turnIndex != null ? 'No spans in this turn.' : 'No spans match the filter.'}
          </div>
        ) : (
          rows.map(row => {
            const pct = Math.max(0.5, (row.durationMs / totalMs) * 100);
            const left = (row.startOffsetMs / totalMs) * 100;
            const isSelected = row.span.spanId === selectedSpanId;

            return (
              <button
                key={row.span.spanId}
                className={`obs-waterfall-row ${isSelected ? 'is-selected' : ''} ${row.hasError ? 'is-error' : ''}`}
                onClick={() => onSelectSpan(row.span)}
                type="button"
              >
                {/* Label column */}
                <div className="obs-waterfall-row-label">
                  <span className={`obs-kind-tag obs-kind-tag--${row.kind}`}>
                    {row.kind}
                  </span>
                  <span className="obs-waterfall-row-name mono-sm">{row.label}</span>
                </div>

                {/* Bar track */}
                <div className="obs-waterfall-row-bar-track">
                  <div
                    className={`obs-waterfall-bar obs-waterfall-bar--${row.kind} ${row.hasError ? 'is-error' : ''}`}
                    style={{ left: `${left}%`, width: `${pct}%` }}
                    title={`${row.label} · ${fmtMs(row.durationMs)}`}
                  />
                  {/* Duration label */}
                  {(() => {
                    const endPct = left + pct;
                    const isNearEdge = endPct > 70;
                    return (
                      <span
                        className="obs-waterfall-row-dur mono-sm"
                        style={isNearEdge
                          ? { left: `calc(${endPct}% - 4px)`, transform: 'translateX(-100%)', color: 'rgba(255,255,255,0.9)' }
                          : { left: `calc(${endPct}% + 4px)` }
                        }
                      >
                        {fmtMs(row.durationMs)}
                      </span>
                    );
                  })()}
                </div>
              </button>
            );
          })
        )}
      </div>
    </div>
  );
};

export default SessionWaterfallPanel;