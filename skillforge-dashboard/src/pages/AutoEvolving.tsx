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
import { usePendingApprovals } from '../hooks/usePendingApprovals';
import '../components/autoevolving/autoevolving.css';
import EvolveTrajectoryPanel from '../components/evolve/EvolveTrajectoryPanel';
import EvolveTriggerModal from '../components/evolve/EvolveTriggerModal';

// Lazy-load the workflow runs panel so its react-flow dependency stays in a
// split chunk (matches Insights.tsx; avoids bundling react-flow into the main
// page chunk + defeating the existing split).
const WorkflowRunsPanel = React.lazy(
  () => import('../components/workflow/WorkflowRunsPanel'),
);

// Lazy-load anomaly and harvested-scenarios — below-fold panels that don't
// need to block the initial render.
const AnomalyPanel = React.lazy(
  () => import('../components/autoevolving/AnomalyPanel'),
);
const HarvestedScenariosPanel = React.lazy(
  () => import('../components/evolve/HarvestedScenariosPanel'),
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
  const [evolveOpen, setEvolveOpen] = useState(false);
  const dagRef = useRef<HTMLDivElement | null>(null);
  const evolveRef = useRef<HTMLDivElement | null>(null);
  // Run id the user just nudged into the DAG via an anomaly quick-link
  // (audit F9a). String identity changes each click so retry-clicking the
  // same run still scrolls back into view.
  const [dagFocusRunId, setDagFocusRunId] = useState<string | null>(null);

  const scrollToEvolve = () =>
    evolveRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });

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

  /** Anomaly quick-link handler — focus DAG on the failing run + scroll. */
  const openRunInDag = (runId: string) => {
    setDagFocusRunId(runId);
    // Defer scroll so the panel has a tick to mount/refocus first.
    requestAnimationFrame(() => scrollToDag());
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
        <div className="ae-header-actions">
          <button
            type="button"
            className="ae-trigger-btn ae-trigger-btn--ghost ae-trigger-btn--stacked"
            onClick={() => setEvolveOpen(true)}
            data-testid="ae-evolve-btn"
          >
            <span className="ae-trigger-btn-label">Evolve agent ▸</span>
            <span className="ae-trigger-btn-sub">Improve prompts via iteration</span>
          </button>
          <button
            type="button"
            className="ae-trigger-btn ae-trigger-btn--stacked"
            onClick={() => setTriggerOpen(true)}
            data-testid="ae-trigger-btn"
          >
            <span className="ae-trigger-btn-label">Trigger workflow ▸</span>
            <span className="ae-trigger-btn-sub">Run a pipeline manually</span>
          </button>
        </div>
      </header>

      {/* F1: Hero status statement — answers "did the flywheel turn today?" at a glance.
          Computed from existing front-end data; no API change required. */}
      {!overviewLoading && !overviewError && (
        <div className="ae-hero-status" data-testid="ae-hero-status">
          <span className="ae-hero-status-sentence">
            {overview?.kpi?.workflowCompletedThisWeek != null &&
            overview.kpi.workflowCompletedThisWeek > 0
              ? `${overview.kpi.workflowCompletedThisWeek} workflow${
                  overview.kpi.workflowCompletedThisWeek !== 1 ? 's' : ''
                } completed this week`
              : 'No workflows completed yet'}
            <span className="ae-hero-status-sep">·</span>
            {approvals.length > 0
              ? `${approvals.length} pending approval${approvals.length !== 1 ? 's' : ''}`
              : 'No pending gates'}
            <span className="ae-hero-status-sep">·</span>
            {recentReports.length > 0
              ? `${recentReports.length} signal${recentReports.length !== 1 ? 's' : ''} active`
              : 'No signals detected'}
          </span>
        </div>
      )}

      {overviewErrMsg && (
        <div className="ae-error" role="alert">
          {overviewErrMsg}
        </div>
      )}

      <KPIStrip
        kpi={kpi}
        loading={overviewLoading}
        pendingApprovalsCount={approvals.length}
        onNavigate={navigate}
      />

      {/* Evolve trajectory — hoisted above-the-fold (audit F5) so the agent's
          live improvement curve is the first thing users see after the KPI
          strip. Moved out of the bottom drawer where it was buried. */}
      <section className="ae-evolve-section" aria-label="Evolve trajectory" ref={evolveRef}>
        <EvolveTrajectoryPanel />
      </section>

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
            <WorkflowRunsPanel focusRunId={dagFocusRunId} />
          </Suspense>
        </div>
      </section>

      <Suspense fallback={<div className="ae-panel-hint">Loading anomaly diagnostics…</div>}>
        <AnomalyPanel
          anomalies={recentAnomalies}
          loading={overviewLoading}
          onOpenInDag={openRunInDag}
        />
      </Suspense>

      <Suspense fallback={<div className="ae-panel-hint">Loading harvested scenarios…</div>}>
        <HarvestedScenariosPanel />
      </Suspense>

      <TriggerWorkflowModal
        open={triggerOpen}
        onClose={() => setTriggerOpen(false)}
        onTriggered={scrollToDag}
      />

      <EvolveTriggerModal
        open={evolveOpen}
        onClose={() => setEvolveOpen(false)}
        onTriggered={scrollToEvolve}
      />
    </div>
  );
};

export default AutoEvolving;
