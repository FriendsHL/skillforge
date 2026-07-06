import React from 'react';
import type { AutoEvolvingKpi } from '../../api/autoevolving';

/**
 * AUTOEVOLVING — KPI strip (3 cards).
 *
 * Semantic roles (UX audit F2):
 *  ① Workflows running     → current load / concurrency
 *  ② Completed this week   → velocity / flywheel speed
 *  ③ Pending approvals     → human-in-the-loop gates
 *
 * Each card with a `to` navigates on click. The old "Memory proposals pending"
 * and "Auto-research" cards are removed — memory count duplicated the signal
 * panel, and auto-research is now a V2 notice strip (F3).
 */
interface KpiCard {
  key: string;
  label: string;
  value: number | null;
  to: string | null;
}

interface KPIStripProps {
  kpi: AutoEvolvingKpi;
  loading?: boolean;
  pendingApprovalsCount?: number;
  onNavigate: (to: string) => void;
}

const KPIStrip: React.FC<KPIStripProps> = ({
  kpi,
  loading,
  pendingApprovalsCount = 0,
  onNavigate,
}) => {
  const cards: KpiCard[] = [
    {
      key: 'running',
      label: 'Workflows running',
      value: kpi.workflowRunning,
      to: '/insights/patterns?tab=workflows',
    },
    {
      key: 'completed',
      label: 'Completed this week',
      value: kpi.workflowCompletedThisWeek,
      to: '/insights/patterns?tab=workflows',
    },
    {
      key: 'approvals',
      label: 'Pending approvals',
      value: pendingApprovalsCount,
      to: null,
    },
  ];

  return (
    <div className="ae-kpi-strip ae-kpi-strip--3" data-testid="ae-kpi-strip">
      {cards.map((c) => {
        const clickable = c.to != null;
        const displayValue =
          c.value == null ? 'N/A' : loading ? '—' : String(c.value);
        return (
          <button
            key={c.key}
            type="button"
            className={`ae-kpi-card${clickable ? '' : ' ae-kpi-card--inert'}`}
            onClick={clickable ? () => onNavigate(c.to as string) : undefined}
            disabled={!clickable}
            data-testid={`ae-kpi-${c.key}`}
          >
            <span className="ae-kpi-value">{displayValue}</span>
            <span className="ae-kpi-label">{c.label}</span>
          </button>
        );
      })}
    </div>
  );
};

export default KPIStrip;
