import React, { useCallback, useMemo, useState } from 'react';
import './JsonViewer.css';

// ── Types ────────────────────────────────────────────────────
type PrimitiveKind = 'string' | 'number' | 'boolean' | 'null';

type JsonLine =
  | { kind: 'open';  depth: number; key: string | null; path: string; bracket: '{' | '['; childCount: number; closeIndex: number }
  | { kind: 'close'; depth: number; bracket: '}' | ']'; path: string; openIndex: number }
  | { kind: 'primitive'; depth: number; key: string | null; value: string; vt: PrimitiveKind; path: string; isLast: boolean };

// ── Helpers ───────────────────────────────────────────────────
function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}
function isArray(v: unknown): v is unknown[] {
  return Array.isArray(v);
}
function valueType(v: unknown): PrimitiveKind {
  if (v === null || v === undefined) return 'null';
  if (typeof v === 'boolean') return 'boolean';
  if (typeof v === 'number') return 'number';
  return 'string';
}
function esc(s: string) {
  return s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '\\r').replace(/\t/g, '\\t');
}

// ── Line generation ───────────────────────────────────────────
function generateLines(value: unknown, depth: number, path: string, key: string | null): JsonLine[] {
  if (isObject(value)) {
    const keys = Object.keys(value);
    const lines: JsonLine[] = [];
    // open
    const openIdx = lines.length;
    lines.push({ kind: 'open', depth, key, path, bracket: '{', childCount: keys.length, closeIndex: -1 });
    // children
    for (let i = 0; i < keys.length; i++) {
      const k = keys[i];
      lines.push(...generateLines(value[k], depth + 1, `${path}.${k}`, k));
    }
    // close
    const closeIdx = lines.length;
    lines.push({ kind: 'close', depth, bracket: '}', path, openIndex: openIdx });
    // back-patch closeIndex
    (lines[openIdx] as Extract<JsonLine, { kind: 'open' }>).closeIndex = closeIdx;
    return lines;
  }

  if (isArray(value)) {
    const lines: JsonLine[] = [];
    const openIdx = lines.length;
    lines.push({ kind: 'open', depth, key, path, bracket: '[', childCount: value.length, closeIndex: -1 });
    for (let i = 0; i < value.length; i++) {
      lines.push(...generateLines(value[i], depth + 1, `${path}[${i}]`, null));
    }
    const closeIdx = lines.length;
    lines.push({ kind: 'close', depth, bracket: ']', path, openIndex: openIdx });
    (lines[openIdx] as Extract<JsonLine, { kind: 'open' }>).closeIndex = closeIdx;
    return lines;
  }

  // primitive
  return [{ kind: 'primitive', depth, key, value: value === null ? 'null' : value === undefined ? 'null' : String(value), vt: valueType(value), path, isLast: true }];
}

// ── Component ─────────────────────────────────────────────────
interface JsonViewerProps {
  data: unknown;
}

export default function JsonViewer({ data }: JsonViewerProps) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [hoverPath, setHoverPath] = useState<string | null>(null);

  const lines = useMemo(() => generateLines(data, 0, '$', null), [data]);

  // Build lookup: path → line index
  const pathToLineIdx = useMemo(() => {
    const m = new Map<string, number>();
    lines.forEach((l, i) => m.set(l.path, i));
    return m;
  }, [lines]);

  const toggle = useCallback((path: string) => {
    setCollapsed(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path); else next.add(path);
      return next;
    });
  }, []);

  // Determine which lines are hidden due to collapsed ancestors
  const hidden = useMemo(() => {
    const hiddenSet = new Set<number>();
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (line.kind !== 'open') continue;
      if (!collapsed.has(line.path)) continue;
      for (let j = i + 1; j < line.closeIndex; j++) {
        hiddenSet.add(j);
      }
    }
    return hiddenSet;
  }, [lines, collapsed]);

  // Build visible lines with their original index
  const visible = useMemo(() => {
    const result: { line: JsonLine; idx: number; lineNum: number }[] = [];
    let num = 1;
    for (let i = 0; i < lines.length; i++) {
      if (hidden.has(i)) continue;
      result.push({ line: lines[i], idx: i, lineNum: num++ });
    }
    return result;
  }, [lines, hidden]);

  // Compute which lines to highlight (bracket pair matching)
  const highlightSet = useMemo(() => {
    const set = new Set<number>();
    if (!hoverPath) return set;
    const idx = pathToLineIdx.get(hoverPath);
    if (idx === undefined) return set;
    const line = lines[idx];
    if (line.kind === 'open') {
      set.add(idx);
      set.add(line.closeIndex);
    } else if (line.kind === 'close') {
      set.add(idx);
      set.add(line.openIndex);
    }
    return set;
  }, [hoverPath, lines, pathToLineIdx]);

  const INDENT = 20; // px per depth level

  return (
    <div className="jv-root">
      {visible.map(({ line, idx, lineNum }) => {
        const px = INDENT + line.depth * INDENT; // +INDENT for line number gutter
        const isHighlighted = highlightSet.has(idx);
        const isClickable = line.kind === 'open';
        const lineClass = [
          'jv-line',
          isClickable ? 'jv-clickable' : '',
          isHighlighted ? 'jv-highlight' : '',
          `jv-depth-${Math.min(line.depth, 9)}`
        ].filter(Boolean).join(' ');

        // Indent guide lines (vertical lines at each indent step)
        const guides: React.ReactNode[] = [];
        for (let d = 0; d < line.depth; d++) {
          const guideLeft = INDENT + d * INDENT + INDENT / 2;
          guides.push(
            <span
              key={`g-${d}`}
              className="jv-guide"
              style={{ left: guideLeft }}
            />
          );
        }

        return (
          <div
            key={idx}
            className={lineClass}
            style={{ paddingLeft: px }}
            onMouseEnter={isClickable ? () => setHoverPath(line.path) : undefined}
            onMouseLeave={isClickable ? () => setHoverPath(null) : undefined}
          >
            {/* Indent guides */}
            {guides}

            {/* Line number */}
            <span className="jv-ln">{lineNum}</span>

            {/* Content */}
            <span className="jv-content">
              {line.kind === 'open' && (
                <OpenLine line={line} collapsed={collapsed} toggle={toggle} />
              )}
              {line.kind === 'close' && (
                <span className="jv-bracket">{line.bracket}</span>
              )}
              {line.kind === 'primitive' && (
                <PrimitiveLine line={line} />
              )}
            </span>
          </div>
        );
      })}
    </div>
  );
}

// ── Sub-components ──────────────────────────────────────────

function OpenLine({ line, collapsed, toggle }: {
  line: Extract<JsonLine, { kind: 'open' }>;
  collapsed: Set<string>;
  toggle: (path: string) => void;
}) {
  const isCollapsed = collapsed.has(line.path);
  const summary = isCollapsed
    ? line.bracket === '{'
      ? `{…} ${line.childCount} prop${line.childCount !== 1 ? 's' : ''}`
      : `[…] ${line.childCount} item${line.childCount !== 1 ? 's' : ''}`
    : null;

  return (
    <>
      <button
        className="jv-toggle"
        onClick={(e) => { e.stopPropagation(); toggle(line.path); }}
        aria-label={isCollapsed ? 'Expand' : 'Collapse'}
      >
        <svg width={10} height={10} viewBox="0 0 10 10" className={isCollapsed ? 'jv-collapsed' : ''}>
          <path d="M3 1l5 4-5 4" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      {line.key !== null && <><span className="jv-key">"{esc(line.key)}"</span><span className="jv-colon">: </span></>}
      <span className="jv-bracket">{line.bracket}</span>
      {summary && <span className="jv-summary"> {summary}</span>}
    </>
  );
}

function PrimitiveLine({ line }: { line: Extract<JsonLine, { kind: 'primitive' }> }) {
  const comma = !line.isLast ? <span className="jv-comma">,</span> : null;

  // Truncate very long strings
  const maxLen = 120;
  let displayValue = line.vt === 'string'
    ? `"${esc(line.value)}"`
    : line.value;
  const isTruncated = displayValue.length > maxLen;
  if (isTruncated) {
    displayValue = displayValue.slice(0, maxLen) + '…';
  }

  return (
    <>
      {line.key !== null && <><span className="jv-key">"{esc(line.key)}"</span><span className="jv-colon">: </span></>}
      <span className={`jv-val jv-val-${line.vt}`} title={isTruncated ? line.value : undefined}>
        {displayValue}
      </span>
      {comma}
    </>
  );
}
