import React, { useMemo, useState } from 'react';
import type {
  SpanSummary,
  LlmSpanSummary,
  ToolSpanSummary,
  EventSpanSummary,
  TraceTreeDto,
  TraceNodeDto,
} from '../../../types/observability';
import WaterfallFoldTeamRow from './WaterfallFoldTeamRow';
import WaterfallChildSummaryRow from './WaterfallChildSummaryRow';
import { fmtMs, traceSpanToSummary } from './session-detail-utils';

interface TraceRoot {
  id: string;
  name: string;
  totalMs: number;
  startTime: string;
  status: 'ok' | 'error';
}

interface SessionWaterfallPanelProps {
  /**
   * OBS-2 path: parent trace's spans (already SpanSummary). Used for the
   * filter-chip stats + the row-rendering of the parent main timeline. When
   * `traceTree` is also provided, this should be the spans of the depth=0
   * trace(s) extracted from the tree (caller's responsibility).
   */
  spans: SpanSummary[];
  /**
   * OBS-4 M3: full unified tree across sessions sharing the same root_trace_id.
   * When present + has depth>=1 children, the panel replaces team-spawn tool
   * rows in the parent main line with a {@link WaterfallFoldTeamRow} that's
   * collapsed by default (OBS-3 v1 fail mode: defaulting expanded buried the
   * parent main line under 100+ child spans).
   *
   * Old session: tree returns 1 trace, no children → no fold-team rows →
   * visual identical to OBS-2.
   */
  traceTree?: TraceTreeDto | null;
  selectedSpanId: string | null;
  onSelectSpan: (span: SpanSummary) => void;
  /**
   * If provided, render a root row at the top representing the whole agent
   * call. Used as the time-scale basis so child spans render proportional to
   * the full agent loop duration. Click acts as `onSelectRoot`.
   */
  traceRoot?: TraceRoot | null;
  /** Called when the root row is clicked (clears any selected child span). */
  onSelectRoot?: () => void;
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

function isLlmSpan(s: SpanSummary): s is LlmSpanSummary {
  return s.kind === 'llm';
}

function isToolSpan(s: SpanSummary): s is ToolSpanSummary {
  return s.kind === 'tool';
}

function isEventSpan(s: SpanSummary): s is EventSpanSummary {
  return s.kind === 'event';
}

function getSpanLabel(span: SpanSummary): string {
  if (isLlmSpan(span)) {
    return span.model || 'LLM';
  }
  if (isToolSpan(span)) {
    return span.toolName || 'Tool';
  }
  if (isEventSpan(span)) {
    return span.name || span.eventType || 'Event';
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
  if (isEventSpan(span)) {
    return !span.success;
  }
  return false;
}

const TEAM_TOOL_NAMES: ReadonlySet<string> = new Set([
  'TeamCreate',
  'TeamSend',
  'TeamKill',
  'SubAgent',
  'SubAgentDispatch',
]);

/**
 * Discriminated row union for the unified waterfall (OBS-4 M3).
 *
 * - `span`: a regular span row. depth=0 = parent main timeline; depth=2 =
 *   inside an expanded child summary (24px+24px = 48px indent).
 * - `fold-team`: collapsed-by-default header for a tool span that spawned
 *   child sessions. Replaces the underlying span's plain row.
 * - `fold-child-summary`: collapsed-by-default summary of one child trace
 *   (depth=1, 24px indent). Inside an expanded fold-team only.
 */
type WaterfallRow =
  | {
      kind: 'span';
      depth: 0 | 2;
      span: SpanSummary;
      startOffsetMs: number;
      durationMs: number;
      label: string;
      hasError: boolean;
      spanKind: 'llm' | 'tool' | 'event';
    }
  | {
      kind: 'fold-team';
      teamSpan: SpanSummary;
      childTraces: TraceNodeDto[];
      startOffsetMs: number;
      durationMs: number;
      hasError: boolean;
      foldKey: string; // teamSpan.spanId
    }
  | {
      kind: 'fold-child-summary';
      child: TraceNodeDto;
      startOffsetMs: number;
      durationMs: number;
      foldKey: string; // child.traceId
    };

interface BuildResult {
  rows: WaterfallRow[];
  totalMs: number;
  baseStartMs: number;
  stats: { llm: number; tool: number; event: number; error: number; total: number };
}

/**
 * Assign depth=1 child traces to team-spawn spans by start-time window.
 *
 * Strategy:
 *   - 0 team-spawn spans + N children: orphan the children (rare; happens
 *     if BE reports child traces but no spawn tool span — probably a data
 *     glitch). We silently drop fold-team rendering in that case.
 *   - 1 team-spawn span: every child belongs to it (the 90% case for
 *     6f18ecca-style investigations).
 *   - 2+ team-spawn spans: assign each child to the latest spawn span
 *     started at or before its own start, falling back to the first.
 */
function assignChildrenToSpawns(
  parentSpans: SpanSummary[],
  childTraces: TraceNodeDto[],
): Map<string, TraceNodeDto[]> {
  const spawns = parentSpans.filter(
    (s) => isToolSpan(s) && TEAM_TOOL_NAMES.has(s.toolName),
  );
  const assignment = new Map<string, TraceNodeDto[]>();
  if (spawns.length === 0 || childTraces.length === 0) return assignment;

  if (spawns.length === 1) {
    assignment.set(spawns[0].spanId, [...childTraces]);
    return assignment;
  }

  // Multi-spawn: bucket children by latest spawn at-or-before child's start.
  const sortedSpawns = [...spawns].sort((a, b) => tsOf(a.startedAt) - tsOf(b.startedAt));
  for (const child of childTraces) {
    const childStart = tsOf(child.startedAt);
    let owner = sortedSpawns[0];
    for (const s of sortedSpawns) {
      if (tsOf(s.startedAt) <= childStart) owner = s;
      else break;
    }
    let bucket = assignment.get(owner.spanId);
    if (!bucket) {
      bucket = [];
      assignment.set(owner.spanId, bucket);
    }
    bucket.push(child);
  }
  return assignment;
}

function buildRows(
  parentSpans: SpanSummary[],
  childTraces: TraceNodeDto[],
  filter: FilterState,
  foldState: Record<string, boolean>,
  traceRoot: TraceRoot | null | undefined,
): BuildResult {
  const allStats = {
    llm: parentSpans.filter((s) => s.kind === 'llm').length,
    tool: parentSpans.filter((s) => s.kind === 'tool').length,
    event: parentSpans.filter((s) => s.kind === 'event').length,
    error: parentSpans.filter((s) => getSpanError(s)).length,
    total: parentSpans.length,
  };

  const traceStartMs = traceRoot ? tsOf(traceRoot.startTime) : 0;
  const traceTotalMs = traceRoot && traceRoot.totalMs > 0 ? traceRoot.totalMs : 0;

  if (parentSpans.length === 0) {
    return {
      rows: [],
      totalMs: traceTotalMs > 0 ? traceTotalMs : 0,
      stats: allStats,
      baseStartMs: traceStartMs,
    };
  }

  // Compute timeline base + total covering parent main + all child traces
  // (so we don't render a child bar that overflows the visible scale).
  const sortedParent = [...parentSpans].sort((a, b) => tsOf(a.startedAt) - tsOf(b.startedAt));
  const parentStarts = sortedParent.map((s) => tsOf(s.startedAt));
  const parentEnds = sortedParent.map((s, i) => parentStarts[i] + s.latencyMs);
  const childStarts = childTraces.map((t) => tsOf(t.startedAt));
  const childEnds = childTraces.map(
    (t, i) => childStarts[i] + Math.max(0, t.totalDurationMs ?? 0),
  );
  const minStart = Math.min(...parentStarts, ...(childStarts.length ? childStarts : [Infinity]));
  const maxEnd = Math.max(...parentEnds, ...(childEnds.length ? childEnds : [-Infinity]));

  const baseStartMs = traceRoot && traceStartMs > 0
    ? Math.min(traceStartMs, minStart)
    : minStart;
  let totalTime: number;
  if (traceTotalMs > 0) {
    totalTime = Math.max(traceTotalMs, maxEnd - baseStartMs);
  } else {
    totalTime = Math.max(maxEnd - baseStartMs, 100);
  }

  const childrenBySpawn = assignChildrenToSpawns(sortedParent, childTraces);

  const rows: WaterfallRow[] = [];

  for (const span of sortedParent) {
    const spanStartOffset = Math.max(0, tsOf(span.startedAt) - baseStartMs);
    const hasErr = getSpanError(span);
    const assignedChildren = childrenBySpawn.get(span.spanId);

    if (assignedChildren && assignedChildren.length > 0) {
      // fold-team row replaces the plain tool span row.
      const foldKey = span.spanId;
      rows.push({
        kind: 'fold-team',
        teamSpan: span,
        childTraces: assignedChildren,
        startOffsetMs: spanStartOffset,
        durationMs: span.latencyMs,
        hasError: hasErr,
        foldKey,
      });

      if (foldState[foldKey]) {
        // Render child summaries.
        for (const child of assignedChildren) {
          const childStartOffset = Math.max(0, tsOf(child.startedAt) - baseStartMs);
          const childDur = Math.max(0, child.totalDurationMs ?? 0);
          const childFoldKey = child.traceId;
          rows.push({
            kind: 'fold-child-summary',
            child,
            startOffsetMs: childStartOffset,
            durationMs: childDur,
            foldKey: childFoldKey,
          });

          if (foldState[childFoldKey]) {
            // Inline-expand child internal spans (depth=2). Filtered the
            // same way as parent spans.
            const childSorted = [...child.spans].sort(
              (a, b) => tsOf(a.startedAt) - tsOf(b.startedAt),
            );
            for (const cs of childSorted) {
              const summary = traceSpanToSummary(cs, child.traceId);
              const matches = filter.error
                ? getSpanError(summary)
                : (filter.llm && summary.kind === 'llm') ||
                  (filter.tool && summary.kind === 'tool') ||
                  (filter.event && summary.kind === 'event');
              if (!matches) continue;
              rows.push({
                kind: 'span',
                depth: 2,
                span: summary,
                startOffsetMs: Math.max(0, tsOf(summary.startedAt) - baseStartMs),
                durationMs: summary.latencyMs,
                label: getSpanLabel(summary),
                hasError: getSpanError(summary),
                spanKind: summary.kind,
              });
            }
          }
        }
      }
      // Note: filter chips don't hide the fold-team row itself when its
      // underlying tool kind is filtered out — the team-spawn point is a
      // structural anchor for the child group, not just a tool span. This
      // also ensures users always see "where the children live" even if
      // they unchecked tool.
      continue;
    }

    // Regular parent main row — apply filters.
    const matchesFilter = filter.error
      ? hasErr
      : (filter.llm && span.kind === 'llm') ||
        (filter.tool && span.kind === 'tool') ||
        (filter.event && span.kind === 'event');
    if (!matchesFilter) continue;

    rows.push({
      kind: 'span',
      depth: 0,
      span,
      startOffsetMs: spanStartOffset,
      durationMs: span.latencyMs,
      label: getSpanLabel(span),
      hasError: hasErr,
      spanKind: span.kind,
    });
  }

  return { rows, totalMs: totalTime, baseStartMs, stats: allStats };
}

const SessionWaterfallPanel: React.FC<SessionWaterfallPanelProps> = ({
  spans,
  traceTree,
  selectedSpanId,
  onSelectSpan,
  traceRoot,
  onSelectRoot,
}) => {
  const [filter, setFilter] = useState<FilterState>({ llm: true, tool: true, event: true, error: false });
  // OBS-4 M3 — fold state keyed by teamSpan.spanId / child.traceId.
  // Default is empty map → ALL collapsed. This is the single most important
  // invariant of M3: never default any fold to expanded (OBS-3 v1 failure
  // mode). Page reload also resets all (no persistence).
  const [foldState, setFoldState] = useState<Record<string, boolean>>({});
  const toggleFold = (key: string) =>
    setFoldState((prev) => ({ ...prev, [key]: !prev[key] }));

  // Direct child traces (depth=1) attached to the parent session. Higher-depth
  // descendants are not rendered in M3 — they'll surface flat as tool/llm
  // spans within the child summary expansion if the user drills in.
  const directChildTraces = useMemo<TraceNodeDto[]>(() => {
    if (!traceTree) return [];
    // Parent session id = sessionId of any depth=0 trace (they all share it).
    const parentSessionId =
      traceTree.traces.find((t) => t.depth === 0)?.sessionId ?? null;
    if (!parentSessionId) return [];
    return traceTree.traces
      .filter((t) => t.depth === 1 && t.parentSessionId === parentSessionId)
      .sort((a, b) => tsOf(a.startedAt) - tsOf(b.startedAt));
  }, [traceTree]);

  const { rows, totalMs, stats } = useMemo(
    () => buildRows(spans, directChildTraces, filter, foldState, traceRoot),
    [spans, directChildTraces, filter, foldState, traceRoot],
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
    <div className="tr-waterfall">
      {/* Header: name column + time scale aligned with bar track */}
      <div className="tr-waterfall-h">
        <div className="tr-waterfall-h-name">Spans</div>
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
        <span className="tr-waterfall-filter-count mono-sm">
          {rows.length} shown
          {directChildTraces.length > 0 ? ` · ${directChildTraces.length} child trace${directChildTraces.length === 1 ? '' : 's'}` : ''}
        </span>
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
          <div className="tr-empty">No spans match the filter.</div>
        ) : (
          rows.map((row) => {
            if (row.kind === 'fold-team') {
              const totalChildSpans = row.childTraces.reduce(
                (acc, c) => acc + c.llmCallCount + c.toolCallCount + c.eventCount,
                0,
              );
              const totalChildDur = row.childTraces.reduce(
                (acc, c) => acc + Math.max(0, c.totalDurationMs ?? 0),
                0,
              );
              return (
                <WaterfallFoldTeamRow
                  key={`fold-team-${row.foldKey}`}
                  toolName={isToolSpan(row.teamSpan) ? row.teamSpan.toolName : 'Tool'}
                  childCount={row.childTraces.length}
                  totalChildSpanCount={totalChildSpans}
                  totalChildDurationMs={totalChildDur}
                  startOffsetMs={row.startOffsetMs}
                  durationMs={row.durationMs}
                  totalMs={totalMs}
                  expanded={!!foldState[row.foldKey]}
                  selected={row.teamSpan.spanId === selectedSpanId}
                  hasError={row.hasError}
                  onToggle={() => toggleFold(row.foldKey)}
                  onSelect={() => onSelectSpan(row.teamSpan)}
                />
              );
            }

            if (row.kind === 'fold-child-summary') {
              return (
                <WaterfallChildSummaryRow
                  key={`child-summary-${row.foldKey}`}
                  child={row.child}
                  startOffsetMs={row.startOffsetMs}
                  durationMs={row.durationMs}
                  totalMs={totalMs}
                  expanded={!!foldState[row.foldKey]}
                  onToggle={() => toggleFold(row.foldKey)}
                />
              );
            }

            // Regular span row (depth=0 or depth=2).
            const pct = Math.max(0.5, (row.durationMs / totalMs) * 100);
            const left = (row.startOffsetMs / totalMs) * 100;
            const isSelected = row.span.spanId === selectedSpanId;
            const endPct = left + pct;
            const isNearEdge = endPct > 75;
            const indent = row.depth === 2 ? 4 + 48 : 4 + 18;

            return (
              <button
                key={row.span.spanId}
                type="button"
                className={`tr-span-row ${isSelected ? 'sel' : ''} ${row.hasError ? 'err' : ''} ${row.depth === 2 ? 'tr-child-internal-row' : ''}`}
                onClick={() => onSelectSpan(row.span)}
              >
                <div className="tr-span-name" style={{ paddingLeft: indent }}>
                  <span className="tr-tree-line" aria-hidden="true">└─</span>
                  <span className={`tr-kind-tag k-${row.spanKind}`}>{row.spanKind}</span>
                  <span className="tr-span-label mono-sm">{row.label}</span>
                </div>
                <div className="tr-span-bar-track">
                  <div
                    className={`tr-span-bar k-${row.spanKind} ${row.hasError ? 'err' : ''}`}
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
