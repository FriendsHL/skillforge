import React from 'react';
import type { TraceNodeDto, TraceStatus } from '../../../types/observability';
import { fmtMs } from './session-detail-utils';

/**
 * OBS-4 M3 — collapsed-by-default summary row for a single spawned child
 * trace (depth=1) inside an expanded fold-team group. Click the caret to
 * inline-expand the child's internal spans (depth=2). Default collapsed
 * — OBS-3 v1 fail mode was defaulting to expanded, drowning the parent.
 *
 * Indent = 24px (1 level deeper than parent main spans).
 */
interface WaterfallChildSummaryRowProps {
  child: TraceNodeDto;
  startOffsetMs: number;
  durationMs: number;
  totalMs: number;
  expanded: boolean;
  onToggle: () => void;
}

function statusBadgeClass(status: TraceStatus): string {
  switch (status) {
    case 'ok': return 'tr-status-badge ok';
    case 'error': return 'tr-status-badge err';
    case 'cancelled': return 'tr-status-badge cancel';
    case 'running': return 'tr-status-badge run';
    default: return 'tr-status-badge';
  }
}

const WaterfallChildSummaryRow: React.FC<WaterfallChildSummaryRowProps> = ({
  child,
  startOffsetMs,
  durationMs,
  totalMs,
  expanded,
  onToggle,
}) => {
  const pct = Math.max(0.5, (durationMs / totalMs) * 100);
  const left = (startOffsetMs / totalMs) * 100;
  const endPct = left + pct;
  const isNearEdge = endPct > 75;
  const totalSpanCount = child.llmCallCount + child.toolCallCount + child.eventCount;
  const hasError = child.status === 'error';
  const agentLabel = child.agentName ?? `agent#${child.agentId ?? '?'}`;

  return (
    <div
      role="button"
      tabIndex={0}
      className={`tr-span-row tr-child-summary-row ${hasError ? 'err' : ''} ${expanded ? 'is-expanded' : ''}`}
      onClick={onToggle}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onToggle();
        }
      }}
    >
      <div className="tr-span-name" style={{ paddingLeft: 4 + 24 }}>
        <span
          className={`tr-fold-caret ${expanded ? 'is-expanded' : ''}`}
          aria-hidden="true"
        >
          ▶
        </span>
        <span className="tr-kind-tag k-agent">child</span>
        <span className="tr-span-label mono-sm">{agentLabel}</span>
        <span className={statusBadgeClass(child.status)}>{child.status}</span>
        <span className="tr-child-summary-meta mono-sm">{totalSpanCount} spans</span>
      </div>
      <div className="tr-span-bar-track">
        <div
          className={`tr-span-bar k-agent ${hasError ? 'err' : ''}`}
          style={{ left: `${left}%`, width: `${pct}%`, opacity: 0.7 }}
          title={`${agentLabel} · ${fmtMs(durationMs)}`}
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

export default WaterfallChildSummaryRow;
