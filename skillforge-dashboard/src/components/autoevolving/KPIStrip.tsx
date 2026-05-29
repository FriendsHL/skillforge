import React from 'react';
import type { AutoEvolvingKpi } from '../../api/autoevolving';

/**
 * AUTOEVOLVING V1 Sprint 4 — KPI strip (4 cards).
 *
 * Scale-contrast big number + label (design.md "clear hierarchy"). Each card
 * with a `to` navigates on click; the autoResearch placeholder card is inert.
 */
interface KpiCard {
  key: string;
  label: string;
  /** Display value; null renders the "N/A" placeholder treatment. */
  value: number | null;
  to: string | null;
  hint?: string;
}

interface KPIStripProps {
  kpi: AutoEvolvingKpi;
  loading?: boolean;
  onNavigate: (to: string) => void;
}

const KPIStrip: React.FC<KPIStripProps> = ({ kpi, loading, onNavigate }) => {
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
      key: 'memory',
      label: 'Memory proposals pending',
      value: kpi.memoryProposalPending,
      to: '/memories',
    },
    {
      key: 'research',
      label: 'Auto-research',
      value: kpi.autoResearchPending,
      to: null,
      hint: 'Ships in V2',
    },
  ];

  return (
    <div className="ae-kpi-strip" data-testid="ae-kpi-strip">
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
            {c.hint && <span className="ae-kpi-hint">{c.hint}</span>}
          </button>
        );
      })}
    </div>
  );
};

export default KPIStrip;
