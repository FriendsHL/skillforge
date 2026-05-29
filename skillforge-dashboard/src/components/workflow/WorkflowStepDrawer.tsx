import React, { useEffect } from 'react';
import JsonViewer from '../JsonViewer';
import WorkflowApproveCard from './WorkflowApproveCard';
import { deriveAgentStatus, statusChipClass, STEP_KIND_HUMAN_APPROVE } from './workflowDagUtils';
import type { WorkflowStep } from '../../api/workflow';
import './workflow.css';

/**
 * AUTOEVOLVING V1 Q2 — right slide-out detail drawer for a workflow run step.
 *
 * Mirrors `FlywheelStepDrawer` (backdrop + aside role=dialog + Esc/×/backdrop
 * close, sectioned body). Opens when the DAG's `onNodeClick` selects an agent /
 * gate node. For a paused `human_approve` gate it embeds the existing
 * `WorkflowApproveCard` so the operator can approve/reject in place — the card
 * owns all approve logic (reason / 409 / query invalidation); we do not
 * re-implement it.
 *
 * Read-only otherwise: sections 1-3 just surface the step's recorded fields.
 */
interface WorkflowStepDrawerProps {
  /** Selected step; null = drawer closed. */
  step: WorkflowStep | null;
  /** Owning run id (for the embedded approve card + display). */
  runId: string;
  /** Run-level status — drives the derived chip + the approve-gate condition. */
  runStatus: string;
  /** Workflow name (passed to the approve card header). */
  workflowName: string | null;
  /** Close handler — parent sets selectedStep(null). */
  onClose: () => void;
  /** Fired after an embedded approve/reject resolves (parent may close). */
  onApproved?: () => void;
}

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return '—';
  return new Date(t).toLocaleString();
}

const WorkflowStepDrawer: React.FC<WorkflowStepDrawerProps> = ({
  step,
  runId,
  runStatus,
  workflowName,
  onClose,
  onApproved,
}) => {
  // Esc-to-close. Cleanup MUST remove the listener (frontend.md footgun #5) —
  // re-bound whenever the selected step changes or the drawer closes.
  useEffect(() => {
    if (!step) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [step, onClose]);

  if (!step) return null;

  const derived = deriveAgentStatus(step, runStatus);
  const title = step.agentSlug ?? `step ${step.stepIndex ?? '?'}`;
  const isApproveGate =
    step.stepKind === STEP_KIND_HUMAN_APPROVE &&
    step.status === 'pending' &&
    runStatus === 'paused';

  return (
    <>
      <div
        className="wf-drawer-backdrop"
        onClick={onClose}
        data-testid="wf-drawer-backdrop"
        aria-hidden="true"
      />
      <aside
        className="wf-drawer"
        role="dialog"
        aria-labelledby="wf-drawer-title"
        data-testid="wf-drawer"
      >
        <header className="wf-drawer-head">
          <h2 id="wf-drawer-title" className="wf-drawer-title" title={title}>
            {title}
          </h2>
          <span className={`wf-chip ${statusChipClass(derived)}`}>{derived}</span>
          <button
            type="button"
            className="wf-drawer-close"
            onClick={onClose}
            aria-label="Close detail panel"
          >
            ×
          </button>
        </header>

        {/* Section — read-only step facts. */}
        <section className="wf-drawer-section">
          <dl className="wf-drawer-facts">
            <DrawerFact label="step index" value={step.stepIndex != null ? String(step.stepIndex) : '—'} />
            <DrawerFact label="kind" value={step.stepKind} />
            <DrawerFact label="status" value={step.status} />
            {step.phase && <DrawerFact label="phase" value={step.phase} />}
            <DrawerFact label="created" value={formatTime(step.createdAt)} />
            <DrawerFact label="updated" value={formatTime(step.updatedAt)} />
          </dl>
          {step.status === 'error' && (
            <p className="wf-drawer-error" role="alert">
              This step ended in error. See the run-level error for details.
            </p>
          )}
        </section>

        {/* Section — payload (humanApprove arg or any recorded payload).
            Suppressed for the approve-gate case: the embedded WorkflowApproveCard
            already renders the same payload, so showing it here too would
            duplicate the JSON for the operator. */}
        {step.payload != null && !isApproveGate && (
          <section className="wf-drawer-section">
            <h3 className="wf-drawer-section-title">Payload</h3>
            <div className="wf-drawer-payload">
              <JsonViewer data={step.payload} />
            </div>
          </section>
        )}

        {/* Section — in-place approval for a paused human_approve gate. Reuses
            WorkflowApproveCard verbatim (approve/reject + reason + 409 +
            query invalidation all live there). */}
        {isApproveGate && (
          <section className="wf-drawer-section" data-testid="wf-drawer-approve">
            <h3 className="wf-drawer-section-title">Approval</h3>
            <WorkflowApproveCard
              runId={runId}
              stepIndex={step.stepIndex}
              payload={step.payload}
              workflowName={workflowName}
              onResolved={() => {
                onApproved?.();
                onClose();
              }}
            />
          </section>
        )}
      </aside>
    </>
  );
};

const DrawerFact: React.FC<{ label: string; value: string }> = ({
  label,
  value,
}) => (
  <div className="wf-drawer-fact">
    <dt className="wf-drawer-fact-label">{label}</dt>
    <dd className="wf-drawer-fact-value">{value}</dd>
  </div>
);

export default WorkflowStepDrawer;
