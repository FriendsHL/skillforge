import React, { Suspense, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../contexts/AuthContext';
import { getOverview, type AutoEvolvingOverview } from '../api/autoevolving';
import { listMemoryProposals } from '../api/memoryProposalsApi';
import WorkflowApproveCard from '../components/workflow/WorkflowApproveCard';
import TriggerWorkflowModal from '../components/workflow/TriggerWorkflowModal';
import KPIStrip from '../components/autoevolving/KPIStrip';
import SignalPanels from '../components/autoevolving/SignalPanels';
import AnomalyPanel from '../components/autoevolving/AnomalyPanel';
import { usePendingApprovals } from '../hooks/usePendingApprovals';
import '../components/autoevolving/autoevolving.css';

// Lazy-load the workflow runs panel so its react-flow dependency stays in a
// split chunk (matches Insights.tsx; avoids bundling react-flow into the main
// page chunk + defeating the existing split).
const WorkflowRunsPanel = React.lazy(
  () => import('../components/workflow/WorkflowRunsPanel'),
);

/**
 * AUTOEVOLVING V1 Sprint 4 — `/autoevolving` overview page.
 *
 * Bento layout (sprint4-plan-draft.md §3): header + trigger → KPI strip →
 * pending approvals (only when paused) → 3 signal panels → embedded
 * WorkflowRunsPanel DAG → anomaly diagnostics.
 *
 * Data: one `getOverview` round-trip (KPI + recentReports + recentAnomalies)
 * + `listMemoryProposals` for the memory panel list + `usePendingApprovals`
 * for the humanApprove gates (paused runs + live WS).
 */
const AutoEvolving: React.FC = () => {
  const navigate = useNavigate();
  const { userId } = useAuth();
  const [triggerOpen, setTriggerOpen] = useState(false);
  const dagRef = useRef<HTMLDivElement | null>(null);

  // ── Overview snapshot (KPI + reports + anomalies) ──
  const {
    data: overview,
    isLoading: overviewLoading,
    isError: overviewError,
    error: overviewErrObj,
  } = useQuery<AutoEvolvingOverview>({
    queryKey: ['autoevolving-overview', userId],
    queryFn: () => getOverview({ userId }).then((r) => r.data),
    staleTime: 15_000,
  });

  // ── Memory proposals (pending list for signal panel ③) ──
  const { data: memoryProposals, isLoading: memoryLoading } = useQuery({
    queryKey: ['memory-proposals', 'proposed', userId],
    queryFn: () =>
      listMemoryProposals({ userId, status: 'proposed', limit: 8 }).then(
        (r) => r.data,
      ),
    staleTime: 15_000,
  });

  // ── Pending humanApprove gates ──
  const { approvals, removeApproval } = usePendingApprovals(userId);

  const overviewErrMsg = overviewError
    ? overviewErrObj instanceof Error
      ? overviewErrObj.message
      : 'Failed to load overview.'
    : null;

  const kpi = overview?.kpi ?? {
    workflowRunning: 0,
    workflowCompletedThisWeek: 0,
    memoryProposalPending: 0,
    autoResearchPending: null,
  };
  const recentReports = useMemo(
    () => overview?.recentReports ?? [],
    [overview],
  );
  const recentAnomalies = useMemo(
    () => overview?.recentAnomalies ?? [],
    [overview],
  );

  const scrollToDag = () => {
    dagRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <div className="ae-page" data-testid="autoevolving-page">
      <header className="ae-header">
        <div className="ae-header-text">
          <h1 className="ae-title">Auto-Evolving</h1>
          <p className="ae-subtitle">
            Self-improvement signals, workflow runs, and human-in-the-loop gates.
          </p>
        </div>
        <button
          type="button"
          className="ae-trigger-btn"
          onClick={() => setTriggerOpen(true)}
          data-testid="ae-trigger-btn"
        >
          Trigger workflow ▸
        </button>
      </header>

      {overviewErrMsg && (
        <div className="ae-error" role="alert">
          {overviewErrMsg}
        </div>
      )}

      <KPIStrip kpi={kpi} loading={overviewLoading} onNavigate={navigate} />

      {approvals.length > 0 && (
        <section className="ae-approvals" aria-label="Pending approvals">
          <h2 className="ae-section-title">
            Pending approvals
            <span className="ae-section-count">{approvals.length}</span>
          </h2>
          <div className="ae-approvals-list">
            {approvals.map((a) => (
              <WorkflowApproveCard
                key={`${a.runId}::${a.stepIndex ?? 'x'}`}
                runId={a.runId}
                stepIndex={a.stepIndex}
                payload={a.payload}
                workflowName={a.workflowName}
                onResolved={() => removeApproval(a.runId, a.stepIndex)}
              />
            ))}
          </div>
        </section>
      )}

      <SignalPanels
        recentReports={recentReports}
        memoryProposals={memoryProposals ?? []}
        loading={overviewLoading}
        memoryLoading={memoryLoading}
        onNavigate={navigate}
      />

      <section className="ae-dag-section" aria-label="Workflow runs" ref={dagRef}>
        <h2 className="ae-section-title">Workflow runs</h2>
        <div className="ae-dag-host">
          <Suspense
            fallback={<div className="ae-panel-hint">Loading workflow runs…</div>}
          >
            <WorkflowRunsPanel />
          </Suspense>
        </div>
      </section>

      <AnomalyPanel anomalies={recentAnomalies} loading={overviewLoading} />

      <TriggerWorkflowModal
        open={triggerOpen}
        onClose={() => setTriggerOpen(false)}
        onTriggered={scrollToDag}
      />
    </div>
  );
};

export default AutoEvolving;
