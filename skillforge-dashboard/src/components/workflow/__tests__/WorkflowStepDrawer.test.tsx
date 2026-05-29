/**
 * AUTOEVOLVING V1 Q2 — WorkflowStepDrawer tests.
 *   (a) step=null → renders nothing
 *   (b) completed step → read-only facts, NO embedded approve card
 *   (c) paused + human_approve + pending → embedded WorkflowApproveCard
 *       (Approve / Reject buttons present)
 *   (d) Esc / × / backdrop → onClose
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { WorkflowStep } from '../../../api/workflow';

if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
}

// WorkflowApproveCard (embedded for the gate case) calls approveRun.
const approveRunMock = vi.fn();
vi.mock('../../../api/workflow', () => ({
  approveRun: (...a: unknown[]) => approveRunMock(...a),
}));

import WorkflowStepDrawer from '../WorkflowStepDrawer';

function mkStep(p: Partial<WorkflowStep>): WorkflowStep {
  return {
    stepIndex: 0,
    stepKind: 'subagent_dispatch',
    status: 'completed',
    agentSlug: 'annotator',
    phase: 'Annotate',
    payload: null,
    createdAt: null,
    updatedAt: null,
    ...p,
  };
}

function renderDrawer(
  step: WorkflowStep | null,
  overrides?: Partial<React.ComponentProps<typeof WorkflowStepDrawer>>,
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onClose = vi.fn();
  const utils = render(
    <QueryClientProvider client={client}>
      <WorkflowStepDrawer
        step={step}
        runId="run-abc123def"
        runStatus="paused"
        workflowName="opt-report"
        onClose={onClose}
        {...overrides}
      />
    </QueryClientProvider>,
  );
  return { ...utils, onClose };
}

describe('WorkflowStepDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when step is null', () => {
    renderDrawer(null);
    expect(screen.queryByTestId('wf-drawer')).not.toBeInTheDocument();
  });

  it('renders read-only facts for a completed step without an approve card', () => {
    renderDrawer(mkStep({ status: 'completed' }), { runStatus: 'completed' });
    expect(screen.getByTestId('wf-drawer')).toBeInTheDocument();
    expect(screen.getByText('annotator')).toBeInTheDocument();
    // step facts present
    expect(screen.getByText('kind')).toBeInTheDocument();
    // NO embedded approval section / card
    expect(screen.queryByTestId('wf-drawer-approve')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-approve-approve')).not.toBeInTheDocument();
  });

  it('embeds the approve card for a paused pending human_approve gate', () => {
    renderDrawer(
      mkStep({
        stepKind: 'human_approve',
        status: 'pending',
        agentSlug: null,
        stepIndex: 2,
        payload: { topIssues: ['x'] },
      }),
      { runStatus: 'paused' },
    );
    expect(screen.getByTestId('wf-drawer-approve')).toBeInTheDocument();
    expect(screen.getByTestId('wf-approve-approve')).toBeInTheDocument();
    expect(screen.getByTestId('wf-approve-reject')).toBeInTheDocument();
  });

  it('approving in the embedded card resolves → onClose (onApproved→onClose chain)', async () => {
    approveRunMock.mockResolvedValue({
      data: { runId: 'run-abc123def', status: 'running', decision: 'approved' },
    });
    const onApproved = vi.fn();
    const { onClose } = renderDrawer(
      mkStep({
        stepKind: 'human_approve',
        status: 'pending',
        agentSlug: null,
        stepIndex: 2,
        payload: { topIssues: ['x'] },
      }),
      { runStatus: 'paused', onApproved },
    );
    fireEvent.click(screen.getByTestId('wf-approve-approve'));
    await waitFor(() => expect(approveRunMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(onApproved).toHaveBeenCalledTimes(1));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does NOT embed the approve card when the run is not paused', () => {
    renderDrawer(
      mkStep({ stepKind: 'human_approve', status: 'pending' }),
      { runStatus: 'running' },
    );
    expect(screen.queryByTestId('wf-drawer-approve')).not.toBeInTheDocument();
  });

  it('calls onClose on Esc, × button, and backdrop click', () => {
    // Esc
    const a = renderDrawer(mkStep({}));
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(a.onClose).toHaveBeenCalledTimes(1);
    a.unmount();

    // × button
    const b = renderDrawer(mkStep({}));
    fireEvent.click(screen.getByLabelText('Close detail panel'));
    expect(b.onClose).toHaveBeenCalledTimes(1);
    b.unmount();

    // backdrop
    const c = renderDrawer(mkStep({}));
    fireEvent.click(screen.getByTestId('wf-drawer-backdrop'));
    expect(c.onClose).toHaveBeenCalledTimes(1);
  });
});
