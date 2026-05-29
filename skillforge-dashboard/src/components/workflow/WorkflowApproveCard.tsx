import React, { useState } from 'react';
import { message } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import JsonViewer from '../JsonViewer';
import { approveRun, type WorkflowApproveDecision } from '../../api/workflow';
import './workflow.css';

/**
 * AUTOEVOLVING V1 Sprint 4 — humanApprove review card.
 *
 * Renders one paused workflow `humanApprove()` gate: the attribution-summary
 * payload (via the shared {@link JsonViewer}) + an optional reviewer reason +
 * Approve / Reject actions wired to `POST /api/workflows/runs/{id}/approve`.
 *
 * Decision is **仿建** of the ask_user card (sprint4-plan-draft.md §2): same
 * warning-amber visual language, independent component logic (workflow approve
 * REST, not chat answerAsk).
 *
 * Idempotency / timing (plan §2 自查点): the card is keyed by `runId+stepIndex`
 * upstream. On success it optimistically hides via `onResolved`. On a 409
 * (already resolved by a concurrent reviewer / status changed) it surfaces an
 * error and invalidates the run detail so the parent re-fetches.
 */
interface WorkflowApproveCardProps {
  runId: string;
  /** Monotonic step index of the paused gate; used for keying + display. */
  stepIndex: number | null;
  /** The humanApprove payload (already JS→Java-converted Map/List on the BE). */
  payload: unknown;
  /** Optional workflow name for the header. */
  workflowName?: string | null;
  /** Called after a successful approve/reject so the parent can hide the card. */
  onResolved?: (decision: WorkflowApproveDecision) => void;
}

const WorkflowApproveCard: React.FC<WorkflowApproveCardProps> = ({
  runId,
  stepIndex,
  payload,
  workflowName,
  onResolved,
}) => {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [resolved, setResolved] = useState(false);

  const shortId = runId.slice(0, 8);

  const submit = async (decision: WorkflowApproveDecision) => {
    if (submitting || resolved) return;
    setSubmitting(true);
    const trimmed = reason.trim();
    try {
      await approveRun(runId, {
        decision,
        ...(trimmed ? { reason: trimmed } : {}),
      });
      setResolved(true);
      message.success(
        decision === 'approved' ? 'Workflow approved' : 'Workflow rejected',
      );
      // Refresh the runs list + this run's detail (resume changes status).
      queryClient.invalidateQueries({ queryKey: ['workflow-runs'] });
      queryClient.invalidateQueries({ queryKey: ['workflow-run', runId] });
      onResolved?.(decision);
    } catch (err: unknown) {
      const status =
        axios.isAxiosError(err) && err.response ? err.response.status : null;
      if (status === 409) {
        message.error(
          'This approval was already resolved or the run status changed.',
        );
        // Re-pull detail so the parent reconciles the now-stale card.
        queryClient.invalidateQueries({ queryKey: ['workflow-run', runId] });
        queryClient.invalidateQueries({ queryKey: ['workflow-runs'] });
      } else {
        const msg =
          axios.isAxiosError(err) && err.message
            ? err.message
            : 'Failed to submit approval';
        message.error(msg);
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (resolved) return null;

  return (
    <div
      className="wf-approve-card"
      role="group"
      aria-label="Workflow approval"
      data-testid={`wf-approve-card-${runId}-${stepIndex ?? 'x'}`}
    >
      <div className="wf-approve-head">
        <span className="wf-approve-head-title">
          ⚑ Workflow needs approval
          {` · ${workflowName ?? `run ${shortId}`}`}
        </span>
        <span className="wf-approve-runid" title={runId}>
          {shortId}
          {stepIndex != null ? ` · step ${stepIndex}` : ''}
        </span>
      </div>

      <div className="wf-approve-payload">
        <JsonViewer data={payload} />
      </div>

      <textarea
        className="wf-approve-reason"
        placeholder="Reason (optional)…"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        disabled={submitting}
        aria-label="Approval reason"
      />

      <div className="wf-approve-actions">
        <button
          type="button"
          className="wf-approve-btn wf-approve-btn--reject"
          onClick={() => submit('rejected')}
          disabled={submitting}
          data-testid="wf-approve-reject"
        >
          Reject
        </button>
        <button
          type="button"
          className="wf-approve-btn wf-approve-btn--approve"
          onClick={() => submit('approved')}
          disabled={submitting}
          data-testid="wf-approve-approve"
        >
          Approve
        </button>
      </div>
    </div>
  );
};

export default WorkflowApproveCard;
