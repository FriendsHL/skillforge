import React, { useMemo, useState } from 'react';
import { Modal, Select, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import {
  listWorkflows,
  runWorkflow,
  type WorkflowDto,
} from '../../api/workflow';
import './workflow.css';

/**
 * AUTOEVOLVING V1 Sprint 4 — Trigger workflow modal (FR-6.6 / AC-10).
 *
 * Lists registered workflow definitions (name + phase preview), lets the
 * operator pick one and supply free-form `args` as JSON, then fires
 * `POST /api/workflows/{name}/run`. On 202 success it surfaces a toast and
 * notifies the parent (which scrolls to the DAG panel — live colouring is
 * handled by the embedded WorkflowRunsPanel's own WS + poll). 409 →
 * "already running" warning.
 */
interface TriggerWorkflowModalProps {
  open: boolean;
  onClose: () => void;
  /** Called with the new runId after a successful trigger. */
  onTriggered?: (runId: string) => void;
}

function phasePreview(wf: WorkflowDto): string {
  if (!wf.phases?.length) return '(no phases)';
  return wf.phases.map((p) => p.title).join(' → ');
}

const TriggerWorkflowModal: React.FC<TriggerWorkflowModalProps> = ({
  open,
  onClose,
  onTriggered,
}) => {
  const queryClient = useQueryClient();
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const [argsText, setArgsText] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Only fetch definitions while the modal is open.
  const { data: workflowsResp, isLoading } = useQuery({
    queryKey: ['workflows', 'definitions'],
    queryFn: () => listWorkflows().then((r) => r.data),
    enabled: open,
    staleTime: 5 * 60_000,
  });

  const workflows = useMemo<WorkflowDto[]>(
    () => workflowsResp?.items ?? [],
    [workflowsResp],
  );
  const selected = useMemo(
    () => workflows.find((w) => w.name === selectedName) ?? null,
    [workflows, selectedName],
  );

  const reset = () => {
    setSelectedName(null);
    setArgsText('');
    setSubmitting(false);
  };

  const handleClose = () => {
    if (submitting) return;
    reset();
    onClose();
  };

  const handleSubmit = async () => {
    if (!selectedName) {
      message.warning('Select a workflow first');
      return;
    }
    // Parse args JSON (empty → no args). BE `RunWorkflowRequest.args` is a
    // Map<String,Object>, so it must be a JSON object — `42` / `[1,2]` / null
    // would hit a BE 400. Validate client-side before firing.
    let parsedArgs: unknown;
    const trimmed = argsText.trim();
    if (trimmed) {
      try {
        parsedArgs = JSON.parse(trimmed);
      } catch {
        message.error('Args must be valid JSON');
        return;
      }
      if (
        typeof parsedArgs !== 'object' ||
        parsedArgs === null ||
        Array.isArray(parsedArgs)
      ) {
        message.error('Args must be a JSON object, e.g. {"key":"value"}');
        return;
      }
    }

    setSubmitting(true);
    try {
      const res = await runWorkflow(
        selectedName,
        trimmed ? { args: parsedArgs } : {},
      );
      message.success(`Workflow "${selectedName}" started`);
      queryClient.invalidateQueries({ queryKey: ['workflow-runs'] });
      onTriggered?.(res.data.runId);
      reset();
      onClose();
    } catch (err: unknown) {
      const status =
        axios.isAxiosError(err) && err.response ? err.response.status : null;
      if (status === 409) {
        message.warning('This workflow is already running');
      } else {
        const msg =
          axios.isAxiosError(err) && err.message
            ? err.message
            : 'Failed to start workflow';
        message.error(msg);
      }
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="Trigger workflow"
      open={open}
      onCancel={handleClose}
      onOk={handleSubmit}
      okText="Run"
      okButtonProps={{ loading: submitting, disabled: !selectedName }}
      cancelButtonProps={{ disabled: submitting }}
      destroyOnHidden
      data-testid="trigger-workflow-modal"
    >
      <div className="wf-trigger-body">
        <label className="wf-trigger-label" htmlFor="wf-trigger-select">
          Workflow
        </label>
        <Select
          id="wf-trigger-select"
          style={{ width: '100%' }}
          placeholder={isLoading ? 'Loading workflows…' : 'Select a workflow'}
          loading={isLoading}
          value={selectedName ?? undefined}
          onChange={(v) => setSelectedName(v)}
          options={workflows.map((w) => ({ label: w.name, value: w.name }))}
          data-testid="wf-trigger-select"
        />

        {selected && (
          <div className="wf-trigger-meta">
            {selected.description && (
              <p className="wf-trigger-desc">{selected.description}</p>
            )}
            <p className="wf-trigger-phases">{phasePreview(selected)}</p>
          </div>
        )}

        <label className="wf-trigger-label" htmlFor="wf-trigger-args">
          Args (JSON, optional)
        </label>
        <textarea
          id="wf-trigger-args"
          className="wf-approve-reason"
          placeholder='e.g. {"agentId": 1, "windowDays": 7}'
          value={argsText}
          onChange={(e) => setArgsText(e.target.value)}
          disabled={submitting}
          data-testid="wf-trigger-args"
        />
      </div>
    </Modal>
  );
};

export default TriggerWorkflowModal;
