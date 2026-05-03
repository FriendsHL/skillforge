import React from 'react';
import { fmtMs } from './session-detail-utils';

/**
 * OBS-4 M3 — collapsed-by-default header row for a `TeamCreate` /
 * `SubAgent` / `TeamSend` / `TeamKill` tool span that spawned child
 * sessions. Replaces the plain tool span row when child traces are
 * attached to it.
 *
 * Default collapsed (the OBS-3 v1 fail mode was defaulting to expanded,
 * which buried the parent main line under 100+ child spans). Click the
 * caret on the LHS to reveal child summary rows; click anywhere on the
 * row body to also select the underlying team span (so the right-side
 * detail panel still shows the tool's input/output).
 */
interface WaterfallFoldTeamRowProps {
  toolName: string;
  childCount: number;
  totalChildSpanCount: number;
  totalChildDurationMs: number;
  startOffsetMs: number;
  durationMs: number;
  totalMs: number;
  expanded: boolean;
  selected: boolean;
  hasError: boolean;
  onToggle: () => void;
  onSelect: () => void;
}

const WaterfallFoldTeamRow: React.FC<WaterfallFoldTeamRowProps> = ({
  toolName,
  childCount,
  totalChildSpanCount,
  totalChildDurationMs,
  startOffsetMs,
  durationMs,
  totalMs,
  expanded,
  selected,
  hasError,
  onToggle,
  onSelect,
}) => {
  const pct = Math.max(0.5, (durationMs / totalMs) * 100);
  const left = (startOffsetMs / totalMs) * 100;
  const endPct = left + pct;
  const isNearEdge = endPct > 75;

  // Click handlers: caret toggles fold; rest of row selects underlying span.
  const handleCaretClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    onToggle();
  };

  // Use prefix nesting marker — keep visually consistent with depth-0 spans
  // (no extra indent for fold-team because it IS depth-0; only the child
  // summary / depth-2 spans below it indent).
  return (
    <div
      role="button"
      tabIndex={0}
      className={`tr-span-row tr-fold-team-row ${selected ? 'sel' : ''} ${hasError ? 'err' : ''} ${expanded ? 'is-expanded' : ''}`}
      onClick={onSelect}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onSelect();
        }
      }}
    >
      <div className="tr-span-name" style={{ paddingLeft: 4 }}>
        <button
          type="button"
          className={`tr-fold-caret ${expanded ? 'is-expanded' : ''}`}
          onClick={handleCaretClick}
          aria-label={expanded ? 'Collapse subagent group' : 'Expand subagent group'}
          aria-expanded={expanded}
        >
          ▶
        </button>
        <span className="tr-kind-tag k-tool">tool</span>
        <span className="tr-span-label mono-sm">
          {toolName} <span className="tr-fold-team-meta">(派 {childCount} child · 共 {totalChildSpanCount} spans · 总 {fmtMs(totalChildDurationMs)})</span>
        </span>
      </div>
      <div className="tr-span-bar-track">
        <div
          className={`tr-span-bar k-tool ${hasError ? 'err' : ''}`}
          style={{ left: `${left}%`, width: `${pct}%` }}
          title={`${toolName} · ${fmtMs(durationMs)}`}
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
    </div>
  );
};

export default WaterfallFoldTeamRow;
