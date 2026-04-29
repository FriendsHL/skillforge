import React, { useMemo, useState } from 'react';
import type { SpanSummary, LlmSpanSummary, ToolSpanSummary } from '../../../types/observability';

interface TimelineMessage {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  createdAt: string;
  text: string;
}

interface TraceRoot {
  id: string;
  name: string;
  totalMs: number;
  startTime: string;
  status: 'ok' | 'error';
}

interface SessionWaterfallPanelProps {
  spans: SpanSummary[];
  messages: TimelineMessage[];
  selectedSpanId: string | null;
  onSelectSpan: (span: SpanSummary) => void;
  /** Only show spans from this turn index. If null, show all. */
  turnIndex?: number | null;
  /**
   * If provided, render a root row at the top representing the whole agent
   * call. Used as the time-scale basis so child spans render proportional to
   * the full agent loop duration. Click acts as `onSelectRoot`.
   */
  traceRoot?: TraceRoot | null;
  /** Called when the root row is clicked (clears any selected child span). */
  onSelectRoot?: () => void;
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
  if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
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
    return span.model || 'LLM';
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
  turnIndex?: number | null,
  traceRoot?: TraceRoot | null,
): {
  rows: WaterfallRow[];
  totalMs: number;
  stats: { llm: number; tool: number; error: number; total: number };
  baseStartMs: number;
} {
  const allStats = {
    llm: spans.filter((s) => s.kind === 'llm').length,
    tool: spans.filter((s) => s.kind === 'tool').length,
    error: spans.filter((s) => getSpanError(s)).length,
    total: spans.length,
  };

  const traceStartMs = traceRoot ? tsOf(traceRoot.startTime) : 0;
  const traceTotalMs = traceRoot && traceRoot.totalMs > 0 ? traceRoot.totalMs : 0;

  if (spans.length === 0) {
    return {
      rows: [],
      totalMs: traceTotalMs > 0 ? traceTotalMs : 0,
      stats: allStats,
      baseStartMs: traceStartMs,
    };
  }

  const userMsgs = messages.filter((m) => m.role === 'user');
  const userMsgTimes = userMsgs.map((m) => tsOf(m.createdAt));

  let spansInTurn = spans;
  if (turnIndex != null && userMsgTimes.length > 0) {
    const turnStart = userMsgTimes[turnIndex] ?? 0;
    const turnEnd = userMsgTimes[turnIndex + 1] ?? Infinity;
    spansInTurn = spans.filter((span) => {
      const spanStart = tsOf(span.startedAt);
      return spanStart >= turnStart && spanStart < turnEnd;
    });
  }

  if (spansInTurn.length === 0) {
    return {
      rows: [],
      totalMs: traceTotalMs > 0 ? traceTotalMs : 100,
      stats: allStats,
      baseStartMs: traceStartMs,
    };
  }

  const starts = spansInTurn.map((s) => tsOf(s.startedAt));
  const baseStartMs = traceRoot && traceStartMs > 0 ? traceStartMs : Math.min(...starts);
  let totalTime: number;
  if (traceTotalMs > 0) {
    totalTime = traceTotalMs;
  } else {
    const maxEnd = Math.max(...starts.map((s, i) => s + spansInTurn[i].latencyMs));
    totalTime = Math.max(maxEnd - baseStartMs, 100);
  }

  const allRows: WaterfallRow[] = spansInTurn.map((span) => ({
    span,
    startOffsetMs: Math.max(0, tsOf(span.startedAt) - baseStartMs),
    durationMs: span.latencyMs,
    kind: span.kind as 'llm' | 'tool',
    label: getSpanLabel(span),
    hasError: getSpanError(span),
  }));

  const filteredRows = allRows.filter((row) => {
    if (filter.error) return row.hasError;
    return (filter.llm && row.kind === 'llm') || (filter.tool && row.kind === 'tool');
  });

  return { rows: filteredRows, totalMs: totalTime, stats: allStats, baseStartMs };
}

const SessionWaterfallPanel: React.FC<SessionWaterfallPanelProps> = ({
  spans,
  messages,
  selectedSpanId,
  onSelectSpan,
  turnIndex,
  traceRoot,
  onSelectRoot,
}) => {
  const [filter, setFilter] = useState<FilterState>({ llm: true, tool: true, error: false });

  const { rows, totalMs, stats } = useMemo(
    () => calculateRows(spans, messages, filter, turnIndex, traceRoot),
    [spans, messages, filter, turnIndex, traceRoot],
  );

  const isRootSelected = traceRoot != null && selectedSpanId == null;

  const toggleFilter = (key: keyof FilterState) => {
    if (key === 'error') {
      setFilter((prev) => ({
        llm: prev.error ? true : false,
        tool: prev.error ? true : false,
        error: !prev.error,
      }));
    } else {
      setFilter((prev) => ({
        ...prev,
        [key]: !prev[key],
        error: false,
      }));
    }
  };

  if (spans.length === 0 && traceRoot == null) {
    return (
      <div className="tr-waterfall">
        <div className="tr-empty">No spans in this session.</div>
      </div>
    );
  }

  return (
    <div className="tr-waterfall">
      {/* Header: name column + time scale aligned with bar track */}
      <div className="tr-waterfall-h">
        <div className="tr-waterfall-h-name">
          {turnIndex != null ? `Turn ${turnIndex + 1}` : 'Spans'}
        </div>
        <div className="tr-waterfall-h-bar">
          <div className="tr-timescale">
            {[0, 0.25, 0.5, 0.75, 1].map((t) => (
              <div key={t} className="tr-timescale-tick" style={{ left: `${t * 100}%` }}>
                <span>{fmtMs(totalMs * t)}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Filter chips */}
      <div className="tr-waterfall-filters">
        <button
          type="button"
          className={`tr-chip sm ${filter.llm && !filter.error ? 'on' : ''}`}
          onClick={() => toggleFilter('llm')}
        >
          llm · {stats.llm}
        </button>
        <button
          type="button"
          className={`tr-chip sm ${filter.tool && !filter.error ? 'on' : ''}`}
          onClick={() => toggleFilter('tool')}
        >
          tool · {stats.tool}
        </button>
        <button
          type="button"
          className={`tr-chip sm err ${filter.error ? 'on' : ''}`}
          onClick={() => toggleFilter('error')}
        >
          err · {stats.error}
        </button>
        <span className="tr-waterfall-filter-count mono-sm">{rows.length} shown</span>
      </div>

      {/* Body: root row + child span rows */}
      <div className="tr-waterfall-body">
        {traceRoot && (
          <button
            type="button"
            className={`tr-span-row ${isRootSelected ? 'sel' : ''} ${traceRoot.status === 'error' ? 'err' : ''}`}
            onClick={() => onSelectRoot?.()}
          >
            <div className="tr-span-name" style={{ paddingLeft: 4 }}>
              <span className="tr-kind-tag k-agent">agent</span>
              <span className="tr-span-label mono-sm">{traceRoot.name || 'Agent loop'}</span>
            </div>
            <div className="tr-span-bar-track">
              <div
                className={`tr-span-bar k-agent ${traceRoot.status === 'error' ? 'err' : ''}`}
                style={{ left: '0%', width: '100%' }}
                title={`Total · ${fmtMs(traceRoot.totalMs)}`}
              />
              <span
                className="tr-span-dur mono-sm"
                style={{
                  left: 'calc(100% - 4px)',
                  transform: 'translateY(-50%) translateX(-100%)',
                  color: 'rgba(255,255,255,0.92)',
                }}
              >
                {fmtMs(traceRoot.totalMs)}
              </span>
            </div>
          </button>
        )}

        {rows.length === 0 ? (
          <div className="tr-empty">
            {turnIndex != null ? 'No spans in this turn.' : 'No spans match the filter.'}
          </div>
        ) : (
          rows.map((row) => {
            const pct = Math.max(0.5, (row.durationMs / totalMs) * 100);
            const left = (row.startOffsetMs / totalMs) * 100;
            const isSelected = row.span.spanId === selectedSpanId;
            const endPct = left + pct;
            const isNearEdge = endPct > 75;

            return (
              <button
                key={row.span.spanId}
                type="button"
                className={`tr-span-row ${isSelected ? 'sel' : ''} ${row.hasError ? 'err' : ''}`}
                onClick={() => onSelectSpan(row.span)}
              >
                <div className="tr-span-name" style={{ paddingLeft: 4 + 18 }}>
                  <span className="tr-tree-line" aria-hidden="true">└─</span>
                  <span className={`tr-kind-tag k-${row.kind}`}>{row.kind}</span>
                  <span className="tr-span-label mono-sm">{row.label}</span>
                </div>
                <div className="tr-span-bar-track">
                  <div
                    className={`tr-span-bar k-${row.kind} ${row.hasError ? 'err' : ''}`}
                    style={{ left: `${left}%`, width: `${pct}%` }}
                    title={`${row.label} · ${fmtMs(row.durationMs)}`}
                  />
                  <span
                    className="tr-span-dur mono-sm"
                    style={
                      isNearEdge
                        ? {
                            left: `calc(${endPct}% - 4px)`,
                            transform: 'translateY(-50%) translateX(-100%)',
                            color: 'rgba(255,255,255,0.88)',
                          }
                        : { left: `calc(${endPct}% + 4px)` }
                    }
                  >
                    {fmtMs(row.durationMs)}
                  </span>
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
