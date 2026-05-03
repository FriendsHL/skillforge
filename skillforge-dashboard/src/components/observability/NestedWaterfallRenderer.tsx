/**
 * OBS-3 — shared renderer for the unified trace waterfall (parent + nested
 * descendant trace sub-trees).
 *
 * Used by 3 callsites (PRD §2 + tech-design §3.3):
 *   - SessionWaterfallPanel (mode='full')
 *   - pages/Traces.tsx (mode='full')
 *   - components/chat/RightRail.tsx mini-waterfall (mode='mini')
 *
 * Modes:
 *   - 'full' — 16px indent / standard row height / ▶ icon button toggles
 *     descendant sub-tree; full status text on child badges.
 *   - 'mini' — 8px indent / compact single-line / status dot; tap a row to
 *     toggle. Designed to fit the chat right-rail constrained width.
 *
 * Folding state lives at the page level (`expandedSubtrees: Set<string>`)
 * and is passed in. The renderer never owns the set — that lets the parent
 * clear it on trace change without prop-drilling reset signals.
 *
 * Truncation: when `truncated=true`, the renderer surfaces a "Show more"
 * button that calls `onLoadMore(childTraceId)`; the caller fires the lazy
 * `getTraceWithDescendants(childTraceId, max_depth=2)` and merges the
 * extra descendants back into the cached query data.
 */
import React, { useMemo } from 'react';
import type {
  SpanSummary,
  UnifiedSpan,
  DescendantTraceMeta,
  DescendantTraceStatus,
} from '../../types/observability';

export type NestedWaterfallMode = 'full' | 'mini';

export interface NestedWaterfallProps {
  /** Spans tagged with `depth` + `parentTraceId`. Pre-sorted by `started_at` (BE contract). */
  spans: UnifiedSpan[];
  /** Descendant trace metadata in DFS order. May be empty. */
  descendants: DescendantTraceMeta[];
  /** Time-axis total in ms (root trace duration). Defaults to max span end – min span start. */
  totalMs: number;
  selectedSpanId: string | null;
  onSelectSpan: (span: SpanSummary) => void;
  mode: NestedWaterfallMode;
  /** Page-owned set of expanded child trace_ids; defaults to all collapsed. */
  expandedSubtrees: Set<string>;
  onToggleSubtree: (childTraceId: string) => void;
  /** Whether BE truncated descendants (response.truncated). */
  truncated?: boolean;
  /** Lazy-load callback fired by "Show more" — receives the parent child traceId. */
  onLoadMore?: (childTraceId: string) => void;
  /**
   * Optional: a root agent row at the top of the waterfall (full mode only).
   * Used by SessionWaterfallPanel which renders an "agent loop" header row.
   */
  rootRow?: {
    label: string;
    totalMs: number;
    status: 'ok' | 'error' | 'running';
    selected: boolean;
    onSelect: () => void;
  } | null;
}

/* ─── Pure helpers (exported for tests) ──────────────────────────────────── */

export function tsOf(iso: string | null | undefined): number {
  if (!iso) return 0;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : 0;
}

export function fmtMs(ms: number): string {
  if (ms < 1000) return Math.max(0, Math.round(ms)) + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
  return (ms / 60000).toFixed(1) + 'm';
}

function getSpanLabel(span: SpanSummary): string {
  if (span.kind === 'llm') return span.model || 'LLM';
  if (span.kind === 'tool') return span.toolName || 'Tool';
  return span.name || span.eventType || 'Event';
}

function getSpanError(span: SpanSummary): boolean {
  if (span.kind === 'llm') return span.errorType != null || span.error != null;
  if (span.kind === 'tool' || span.kind === 'event') return !span.success;
  return false;
}

/** Status dot/badge color (semantic, used by both modes). */
function statusColor(status: DescendantTraceStatus): string {
  switch (status) {
    case 'ok':
      return '#5c8a4a';
    case 'error':
      return '#b84130';
    case 'cancelled':
      return '#888';
    case 'running':
    default:
      return '#4a7aa8';
  }
}

/* ─── Visibility model ───────────────────────────────────────────────────── */

interface VisibilityResolver {
  /** True when `span` should be hidden because an ancestor descendant trace is collapsed. */
  isHidden: (span: UnifiedSpan) => boolean;
  /**
   * For a depth-0 dispatch span, return the child traceId it triggers (if any),
   * otherwise null. Used to attach the toggle button to the right row.
   */
  childOfDispatch: (span: UnifiedSpan) => DescendantTraceMeta | null;
  /** Direct child descendants of a given trace (depth N → depth N+1 with parentTraceId === traceId). */
  childrenOf: (parentTraceId: string) => DescendantTraceMeta[];
}

function buildResolver(
  descendants: DescendantTraceMeta[],
  expanded: Set<string>,
): VisibilityResolver {
  const byParent = new Map<string, DescendantTraceMeta[]>();
  for (const d of descendants) {
    const arr = byParent.get(d.parentTraceId) ?? [];
    arr.push(d);
    byParent.set(d.parentTraceId, arr);
  }
  // dispatchSpanId → DescendantTraceMeta (depth-1 lookup against root spans;
  // depth-N lookup also works when parentSpanId is set on the descendant).
  const byDispatchSpanId = new Map<string, DescendantTraceMeta>();
  for (const d of descendants) {
    if (d.parentSpanId) byDispatchSpanId.set(d.parentSpanId, d);
  }
  // traceId → DescendantTraceMeta (so we can climb the tree to test ancestor expansion).
  const byTraceId = new Map<string, DescendantTraceMeta>();
  for (const d of descendants) byTraceId.set(d.traceId, d);

  function isHidden(unified: UnifiedSpan): boolean {
    // Root-trace spans (depth=0) always visible.
    if (unified.depth === 0 || unified.parentTraceId == null) return false;
    // Descendant span: visible iff its OWN trace_id is in expandedSubtrees,
    // and (recursively) all ancestor descendant traces are also expanded —
    // can't show a grandchild without showing its parent context.
    const ownTraceId = unified.span.traceId;
    if (!ownTraceId || !expanded.has(ownTraceId)) return true;
    // Walk up the ancestor chain (depth N → N-1 → ... → 1). The root trace is
    // not in byTraceId so the loop exits when we reach depth=1 (whose
    // parentTraceId points at the root).
    let meta: DescendantTraceMeta | undefined = byTraceId.get(ownTraceId);
    while (meta && meta.depth > 1) {
      const parent = byTraceId.get(meta.parentTraceId);
      if (!parent) break;
      if (!expanded.has(parent.traceId)) return true;
      meta = parent;
    }
    return false;
  }

  function childOfDispatch(unified: UnifiedSpan): DescendantTraceMeta | null {
    return byDispatchSpanId.get(unified.span.spanId) ?? null;
  }

  function childrenOf(parentTraceId: string): DescendantTraceMeta[] {
    return byParent.get(parentTraceId) ?? [];
  }

  return { isHidden, childOfDispatch, childrenOf };
}

/* ─── Component ──────────────────────────────────────────────────────────── */

const NestedWaterfallRenderer: React.FC<NestedWaterfallProps> = ({
  spans,
  descendants,
  totalMs,
  selectedSpanId,
  onSelectSpan,
  mode,
  expandedSubtrees,
  onToggleSubtree,
  truncated = false,
  onLoadMore,
  rootRow = null,
}) => {
  const resolver = useMemo(
    () => buildResolver(descendants, expandedSubtrees),
    [descendants, expandedSubtrees],
  );

  // Compute the time base. If empty, fall back to provided totalMs (or 1).
  const { baseStartMs, effectiveTotalMs } = useMemo(() => {
    if (spans.length === 0) {
      return { baseStartMs: 0, effectiveTotalMs: Math.max(totalMs, 1) };
    }
    const starts = spans.map((u) => tsOf(u.span.startedAt)).filter((t) => t > 0);
    if (starts.length === 0) {
      return { baseStartMs: 0, effectiveTotalMs: Math.max(totalMs, 1) };
    }
    const base = Math.min(...starts);
    const ends = spans.map((u) => tsOf(u.span.startedAt) + (u.span.latencyMs || 0));
    const span = Math.max(...ends) - base;
    return {
      baseStartMs: base,
      effectiveTotalMs: Math.max(totalMs > 0 ? totalMs : span, 1),
    };
  }, [spans, totalMs]);

  // Indent by depth. Mini uses 8px to fit the right rail.
  const indentPerDepth = mode === 'mini' ? 8 : 16;
  // Root indent (room for tree-line glyph in full mode).
  const baseIndent = mode === 'mini' ? 4 : 22;

  // Pre-flatten to a render list so we can intersperse "child header" rows
  // between dispatch spans and their child sub-tree's first span. We render
  // spans in `spans` order (BE pre-sorts started_at ASC) and emit the child
  // header right after the dispatch span.
  type RenderItem =
    | { kind: 'span'; unified: UnifiedSpan }
    | { kind: 'child-header'; meta: DescendantTraceMeta; expanded: boolean }
    | { kind: 'load-more'; parentTraceId: string };

  const items = useMemo<RenderItem[]>(() => {
    const out: RenderItem[] = [];
    /** Descendants whose dispatch span IS in `spans` — these get their toggle
     *  inline on the dispatch SpanRow; no standalone header row emitted. */
    const dispatchedHeaders = new Set<string>();

    for (const u of spans) {
      if (resolver.isHidden(u)) continue;
      out.push({ kind: 'span', unified: u });
      const child = resolver.childOfDispatch(u);
      if (child) dispatchedHeaders.add(child.traceId);
    }

    // Orphaned descendants — their dispatch span is null or not in `spans`.
    // Append a standalone header row at the end so the user still sees them.
    for (const d of descendants) {
      if (dispatchedHeaders.has(d.traceId)) continue;
      out.push({
        kind: 'child-header',
        meta: d,
        expanded: expandedSubtrees.has(d.traceId),
      });
    }

    if (truncated && onLoadMore && descendants.length > 0) {
      // Lazy-load anchor — attach to the parent trace of the first descendant
      // (almost always the root trace's traceId).
      out.push({ kind: 'load-more', parentTraceId: descendants[0].parentTraceId });
    }

    return out;
  }, [spans, descendants, expandedSubtrees, resolver, truncated, onLoadMore]);

  if (spans.length === 0 && rootRow == null) {
    return (
      <div className="tr-waterfall nested-wf">
        <div className="tr-empty">No spans in this trace.</div>
      </div>
    );
  }

  return (
    <div className={`tr-waterfall nested-wf nested-wf--${mode}`}>
      {/* Header: time scale (full mode only — mini relies on caller-provided header). */}
      {mode === 'full' && (
        <div className="tr-waterfall-h">
          <div className="tr-waterfall-h-name">Spans</div>
          <div className="tr-waterfall-h-bar">
            <div className="tr-timescale">
              {[0, 0.25, 0.5, 0.75, 1].map((t) => (
                <div key={t} className="tr-timescale-tick" style={{ left: `${t * 100}%` }}>
                  <span>{fmtMs(effectiveTotalMs * t)}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      <div className="tr-waterfall-body">
        {/* Optional root row (full mode only — agent loop header). */}
        {rootRow && mode === 'full' && (
          <button
            type="button"
            className={`tr-span-row ${rootRow.selected ? 'sel' : ''} ${rootRow.status === 'error' ? 'err' : ''}`}
            onClick={rootRow.onSelect}
          >
            <div className="tr-span-name" style={{ paddingLeft: 4 }}>
              <span className="tr-kind-tag k-agent">agent</span>
              <span className="tr-span-label mono-sm">{rootRow.label}</span>
            </div>
            <div className="tr-span-bar-track">
              <div
                className={`tr-span-bar k-agent ${rootRow.status === 'error' ? 'err' : ''}`}
                style={{ left: '0%', width: '100%' }}
                title={`Total · ${fmtMs(rootRow.totalMs)}`}
              />
              <span
                className="tr-span-dur mono-sm"
                style={{
                  left: 'calc(100% - 4px)',
                  transform: 'translateY(-50%) translateX(-100%)',
                  color: 'rgba(255,255,255,0.92)',
                }}
              >
                {fmtMs(rootRow.totalMs)}
              </span>
            </div>
          </button>
        )}

        {items.map((item, idx) => {
          if (item.kind === 'span') {
            return (
              <SpanRow
                key={`span-${item.unified.span.spanId}`}
                unified={item.unified}
                mode={mode}
                baseStartMs={baseStartMs}
                effectiveTotalMs={effectiveTotalMs}
                indentPerDepth={indentPerDepth}
                baseIndent={baseIndent}
                selected={item.unified.span.spanId === selectedSpanId}
                onSelect={() => onSelectSpan(item.unified.span)}
                /* Toggle button when this span dispatches a child trace. */
                dispatchChild={resolver.childOfDispatch(item.unified)}
                expandedSubtrees={expandedSubtrees}
                onToggleSubtree={onToggleSubtree}
              />
            );
          }
          if (item.kind === 'child-header') {
            // Only render the explicit child-header when the dispatch span was
            // missing — otherwise the SpanRow already exposes a toggle.
            // We always show the badge when collapsed so the user knows a
            // child sub-tree exists (esp. in mini mode).
            return (
              <ChildHeaderRow
                key={`child-${item.meta.traceId}`}
                meta={item.meta}
                expanded={item.expanded}
                onToggle={() => onToggleSubtree(item.meta.traceId)}
                mode={mode}
                indentPerDepth={indentPerDepth}
                baseIndent={baseIndent}
              />
            );
          }
          // load-more
          return (
            <button
              key={`load-more-${idx}`}
              type="button"
              className="nested-wf-load-more"
              onClick={() => onLoadMore?.(item.parentTraceId)}
            >
              Show more descendants…
            </button>
          );
        })}

        {items.length === 0 && rootRow == null && (
          <div className="tr-empty">No visible spans.</div>
        )}
      </div>
    </div>
  );
};

interface SpanRowProps {
  unified: UnifiedSpan;
  mode: NestedWaterfallMode;
  baseStartMs: number;
  effectiveTotalMs: number;
  indentPerDepth: number;
  baseIndent: number;
  selected: boolean;
  onSelect: () => void;
  dispatchChild: DescendantTraceMeta | null;
  expandedSubtrees: Set<string>;
  onToggleSubtree: (childTraceId: string) => void;
}

const SpanRow: React.FC<SpanRowProps> = ({
  unified,
  mode,
  baseStartMs,
  effectiveTotalMs,
  indentPerDepth,
  baseIndent,
  selected,
  onSelect,
  dispatchChild,
  expandedSubtrees,
  onToggleSubtree,
}) => {
  const span = unified.span;
  const startOffsetMs = Math.max(0, tsOf(span.startedAt) - baseStartMs);
  const durationMs = Math.max(0, span.latencyMs || 0);
  const pct = Math.max(0.5, (durationMs / effectiveTotalMs) * 100);
  const left = (startOffsetMs / effectiveTotalMs) * 100;
  const endPct = left + pct;
  const isNearEdge = endPct > 75;
  const hasError = getSpanError(span);
  const label = getSpanLabel(span);

  // Depth-based dimming + indent. opacity 0.85 at depth=1, 0.05 step per layer.
  const dimOpacity = unified.depth > 0 ? Math.max(0.55, 0.85 - 0.05 * unified.depth) : 1;
  const indentPx = baseIndent + unified.depth * indentPerDepth;

  const isDispatch = dispatchChild != null;
  const expanded = isDispatch ? expandedSubtrees.has(dispatchChild.traceId) : false;

  // Click handler: toggle subtree iff click came from the toggle button;
  // otherwise select span. We model the toggle as a separate click target so
  // the row click stays "select span" semantically.
  const handleToggleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (dispatchChild) onToggleSubtree(dispatchChild.traceId);
  };

  return (
    <button
      type="button"
      className={`tr-span-row ${selected ? 'sel' : ''} ${hasError ? 'err' : ''} nested-wf-row nested-wf-row--depth-${unified.depth} ${mode === 'mini' ? 'nested-wf-row--mini' : ''}`}
      onClick={onSelect}
      style={{ opacity: dimOpacity }}
      data-depth={unified.depth}
      data-trace-id={unified.parentTraceId ?? ''}
    >
      <div className="tr-span-name" style={{ paddingLeft: indentPx }}>
        {unified.depth > 0 && mode === 'full' && (
          // OBS-3 W5 fix: empty span — vertical guide line entirely from CSS
          // background; Unicode │ in 1px box overflowed the container.
          <span className="nested-wf-guide" aria-hidden="true" />
        )}
        {unified.depth > 0 && (
          <span className="tr-tree-line" aria-hidden="true">{mode === 'mini' ? '·' : '└─'}</span>
        )}
        {/* Toggle button (full mode → ▶/▼ icon; mini mode → tap row, but we still
            render a small affordance so the user knows it's expandable). */}
        {isDispatch && (
          <span
            role="button"
            tabIndex={0}
            aria-label={expanded ? 'Collapse child trace' : 'Expand child trace'}
            aria-expanded={expanded}
            className={`nested-wf-toggle nested-wf-toggle--${mode}`}
            onClick={handleToggleClick}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                e.stopPropagation();
                if (dispatchChild) onToggleSubtree(dispatchChild.traceId);
              }
            }}
          >
            {mode === 'full' ? (expanded ? '▼' : '▶') : (expanded ? '▾' : '▸')}
          </span>
        )}
        <span className={`tr-kind-tag k-${span.kind}`}>{span.kind}</span>
        <span className="tr-span-label mono-sm">{label}</span>
        {/* Child status badge inline on dispatch row when collapsed (so user
            sees status without expanding). Full mode shows full text; mini
            shows a colored dot. */}
        {isDispatch && dispatchChild && (
          mode === 'full' ? (
            <span
              className={`nested-wf-child-status nested-wf-child-status--${dispatchChild.status}`}
              title={`Child trace status: ${dispatchChild.status}`}
            >
              {dispatchChild.status}
            </span>
          ) : (
            <span
              className="nested-wf-child-dot"
              style={{ background: statusColor(dispatchChild.status) }}
              title={`Child trace ${dispatchChild.status}`}
              aria-label={`Child trace ${dispatchChild.status}`}
            />
          )
        )}
      </div>
      <div className="tr-span-bar-track">
        <div
          className={`tr-span-bar k-${span.kind} ${hasError ? 'err' : ''}`}
          style={{ left: `${left}%`, width: `${pct}%` }}
          title={`${label} · ${fmtMs(durationMs)}`}
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
          {fmtMs(durationMs)}
        </span>
      </div>
    </button>
  );
};

interface ChildHeaderRowProps {
  meta: DescendantTraceMeta;
  expanded: boolean;
  onToggle: () => void;
  mode: NestedWaterfallMode;
  indentPerDepth: number;
  baseIndent: number;
}

/**
 * Standalone child-header row, rendered when the BE could not resolve the
 * dispatch span (`parentSpanId === null` per tech-design §1.4 #5). In the
 * common case the dispatch span itself carries the toggle; this row fills
 * the gap so users still see the descendant exists.
 */
const ChildHeaderRow: React.FC<ChildHeaderRowProps> = ({
  meta,
  expanded,
  onToggle,
  mode,
  indentPerDepth,
  baseIndent,
}) => {
  const indentPx = baseIndent + Math.max(0, meta.depth - 1) * indentPerDepth;
  return (
    <button
      type="button"
      className={`tr-span-row nested-wf-child-header nested-wf-child-header--${mode}`}
      onClick={onToggle}
      aria-expanded={expanded}
    >
      <div className="tr-span-name" style={{ paddingLeft: indentPx }}>
        <span className={`nested-wf-toggle nested-wf-toggle--${mode}`} aria-hidden="true">
          {mode === 'full' ? (expanded ? '▼' : '▶') : (expanded ? '▾' : '▸')}
        </span>
        <span className="tr-kind-tag k-agent">child</span>
        <span className="tr-span-label mono-sm">{meta.agentName || meta.traceId.slice(0, 8)}</span>
        {mode === 'full' ? (
          <span
            className={`nested-wf-child-status nested-wf-child-status--${meta.status}`}
            title={`Status: ${meta.status}`}
          >
            {meta.status}
          </span>
        ) : (
          <span
            className="nested-wf-child-dot"
            style={{ background: statusColor(meta.status) }}
            title={meta.status}
            aria-label={meta.status}
          />
        )}
      </div>
      <div className="tr-span-bar-track">
        <span className="tr-span-dur mono-sm" style={{ left: 'calc(100% - 4px)', transform: 'translateY(-50%) translateX(-100%)' }}>
          {fmtMs(meta.totalDurationMs)}
        </span>
      </div>
    </button>
  );
};

export default NestedWaterfallRenderer;
