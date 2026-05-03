/**
 * SessionWaterfallPanel — full-mode waterfall used by SessionDetail.
 *
 * OBS-3 (2026-05-03): switched data source from `getSessionSpans` to
 * `getTraceWithDescendants` and rendering to `NestedWaterfallRenderer mode='full'`.
 * The panel now accepts unified spans + descendants directly so the page
 * can manage `expandedSubtrees` state and a single fetch covers parent +
 * descendant traces.
 */
import React, { useMemo, useState } from 'react';
import type {
  SpanSummary,
  UnifiedSpan,
  DescendantTraceMeta,
} from '../../../types/observability';
import NestedWaterfallRenderer from '../../observability/NestedWaterfallRenderer';

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
  /** OBS-3 unified spans (parent depth=0 + descendants depth>0). */
  spans: UnifiedSpan[];
  /** OBS-3 descendant trace metadata. */
  descendants: DescendantTraceMeta[];
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
  /** Page-owned expanded sub-tree state. */
  expandedSubtrees: Set<string>;
  onToggleSubtree: (childTraceId: string) => void;
  /** BE-reported truncation flag (from TraceWithDescendants). */
  truncated?: boolean;
  /** Lazy-load more descendants under the given child traceId. */
  onLoadMore?: (childTraceId: string) => void;
}

interface FilterState {
  llm: boolean;
  tool: boolean;
  event: boolean;
  error: boolean;
}

function tsOf(iso: string | null | undefined): number {
  if (!iso) return 0;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : 0;
}

function getSpanError(span: SpanSummary): boolean {
  if (span.kind === 'llm') return span.errorType != null || span.error != null;
  if (span.kind === 'tool' || span.kind === 'event') return !span.success;
  return false;
}

interface FilterStats {
  llm: number;
  tool: number;
  event: number;
  error: number;
  total: number;
}

interface FilterResult {
  filtered: UnifiedSpan[];
  stats: FilterStats;
  totalMs: number;
}

function applyFilter(
  spans: UnifiedSpan[],
  messages: TimelineMessage[],
  filter: FilterState,
  turnIndex: number | null | undefined,
  traceRoot: TraceRoot | null | undefined,
): FilterResult {
  const allStats: FilterStats = {
    llm: spans.filter((u) => u.span.kind === 'llm').length,
    tool: spans.filter((u) => u.span.kind === 'tool').length,
    event: spans.filter((u) => u.span.kind === 'event').length,
    error: spans.filter((u) => getSpanError(u.span)).length,
    total: spans.length,
  };

  const traceStartMs = traceRoot ? tsOf(traceRoot.startTime) : 0;
  const traceTotalMs = traceRoot && traceRoot.totalMs > 0 ? traceRoot.totalMs : 0;

  if (spans.length === 0) {
    return { filtered: [], stats: allStats, totalMs: traceTotalMs > 0 ? traceTotalMs : 1 };
  }

  // Optional turn-window slice (parent-trace only — descendant spans bypass turn filter).
  let scoped = spans;
  if (turnIndex != null) {
    const userMsgTimes = messages.filter((m) => m.role === 'user').map((m) => tsOf(m.createdAt));
    if (userMsgTimes.length > 0) {
      const turnStart = userMsgTimes[turnIndex] ?? 0;
      const turnEnd = userMsgTimes[turnIndex + 1] ?? Infinity;
      scoped = spans.filter((u) => {
        if (u.depth > 0) return true; // descendants always included
        const ts = tsOf(u.span.startedAt);
        return ts >= turnStart && ts < turnEnd;
      });
    }
  }

  const filtered = scoped.filter((u) => {
    if (filter.error) return getSpanError(u.span);
    if (u.span.kind === 'llm') return filter.llm;
    if (u.span.kind === 'tool') return filter.tool;
    if (u.span.kind === 'event') return filter.event;
    return false;
  });

  let totalMs = traceTotalMs;
  if (totalMs <= 0) {
    const starts = scoped.map((u) => tsOf(u.span.startedAt));
    const base = starts.length > 0 && traceStartMs > 0 ? traceStartMs : Math.min(...starts);
    const ends = scoped.map((u) => tsOf(u.span.startedAt) + (u.span.latencyMs || 0));
    totalMs = Math.max(Math.max(...ends) - base, 1);
  }

  return { filtered, stats: allStats, totalMs };
}

const SessionWaterfallPanel: React.FC<SessionWaterfallPanelProps> = ({
  spans,
  descendants,
  messages,
  selectedSpanId,
  onSelectSpan,
  turnIndex,
  traceRoot,
  onSelectRoot,
  expandedSubtrees,
  onToggleSubtree,
  truncated = false,
  onLoadMore,
}) => {
  const [filter, setFilter] = useState<FilterState>({
    llm: true,
    tool: true,
    event: true,
    error: false,
  });

  const { filtered, stats, totalMs } = useMemo(
    () => applyFilter(spans, messages, filter, turnIndex, traceRoot),
    [spans, messages, filter, turnIndex, traceRoot],
  );

  const isRootSelected = traceRoot != null && selectedSpanId == null;

  const toggleFilter = (key: keyof FilterState) => {
    if (key === 'error') {
      setFilter((prev) => ({
        llm: prev.error ? true : false,
        tool: prev.error ? true : false,
        event: prev.error ? true : false,
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
    <div className="tr-waterfall-shell">
      {/* Filter chips header (kept above the renderer for SessionDetail context). */}
      <div className="tr-waterfall-h">
        <div className="tr-waterfall-h-name">
          {turnIndex != null ? `Turn ${turnIndex + 1}` : 'Spans'}
        </div>
        <div className="tr-waterfall-h-bar" />
      </div>
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
          className={`tr-chip sm ${filter.event && !filter.error ? 'on' : ''}`}
          onClick={() => toggleFilter('event')}
        >
          event · {stats.event}
        </button>
        <button
          type="button"
          className={`tr-chip sm err ${filter.error ? 'on' : ''}`}
          onClick={() => toggleFilter('error')}
        >
          err · {stats.error}
        </button>
        <span className="tr-waterfall-filter-count mono-sm">{filtered.length} shown</span>
      </div>

      <NestedWaterfallRenderer
        spans={filtered}
        descendants={descendants}
        totalMs={totalMs}
        selectedSpanId={selectedSpanId}
        onSelectSpan={onSelectSpan}
        mode="full"
        expandedSubtrees={expandedSubtrees}
        onToggleSubtree={onToggleSubtree}
        truncated={truncated}
        onLoadMore={onLoadMore}
        rootRow={
          traceRoot
            ? {
                label: traceRoot.name || 'Agent loop',
                totalMs: traceRoot.totalMs,
                status: traceRoot.status,
                selected: isRootSelected,
                onSelect: () => onSelectRoot?.(),
              }
            : null
        }
      />
    </div>
  );
};

export default SessionWaterfallPanel;
