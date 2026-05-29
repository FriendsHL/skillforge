/**
 * AUTOEVOLVING V1 Sprint 4 — WorkflowApproveCard tests.
 *   1. renders the payload JSON
 *   2. Approve → POST approveRun({decision:'approved'}) + onResolved
 *   3. Reject  → POST approveRun({decision:'rejected'})
 *   4. 409 → error message, card stays (not resolved)
 */
import React from 'react';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const errorSpy = vi.fn();
const successSpy = vi.fn();
vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return {
    ...actual,
    message: {
      ...actual.message,
      error: (...a: unknown[]) => errorSpy(...a),
      success: (...a: unknown[]) => successSpy(...a),
      warning: vi.fn(),
      info: vi.fn(),
    },
  };
});

const approveRunMock = vi.fn();
vi.mock('../../../api/workflow', () => ({
  approveRun: (...a: unknown[]) => approveRunMock(...a),
}));

import WorkflowApproveCard from '../WorkflowApproveCard';

function renderCard(props?: Partial<React.ComponentProps<typeof WorkflowApproveCard>>) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const onResolved = vi.fn();
  const utils = render(
    <QueryClientProvider client={client}>
      <WorkflowApproveCard
        runId="run-abc123def"
        stepIndex={3}
        payload={{ topIssues: ['latency spike', 'tool error'] }}
        workflowName="opt-report"
        onResolved={onResolved}
        {...props}
      />
    </QueryClientProvider>,
  );
  return { ...utils, onResolved };
}

describe('WorkflowApproveCard', () => {
  beforeEach(() => {
    approveRunMock.mockReset();
    errorSpy.mockReset();
    successSpy.mockReset();
  });

  it('renders the payload JSON', () => {
    renderCard();
    expect(screen.getByText(/topIssues/)).toBeInTheDocument();
    expect(screen.getByText(/Workflow needs approval/)).toBeInTheDocument();
  });

  it('falls back to the runId in the header when workflowName is null', () => {
    renderCard({ workflowName: null });
    const title = document.querySelector('.wf-approve-head-title');
    // shortId = runId.slice(0,8) = 'run-abc1'
    expect(title?.textContent).toMatch(/run-abc1/);
  });

  it('Approve posts decision=approved and resolves', async () => {
    approveRunMock.mockResolvedValue({ data: { status: 'running', decision: 'approved' } });
    const { onResolved } = renderCard();
    fireEvent.click(screen.getByTestId('wf-approve-approve'));
    await waitFor(() => expect(approveRunMock).toHaveBeenCalledTimes(1));
    expect(approveRunMock).toHaveBeenCalledWith(
      'run-abc123def',
      expect.objectContaining({ decision: 'approved' }),
    );
    await waitFor(() => expect(onResolved).toHaveBeenCalledWith('approved'));
    expect(successSpy).toHaveBeenCalled();
  });

  it('Reject posts decision=rejected', async () => {
    approveRunMock.mockResolvedValue({ data: { status: 'error', decision: 'rejected' } });
    renderCard();
    fireEvent.click(screen.getByTestId('wf-approve-reject'));
    await waitFor(() => expect(approveRunMock).toHaveBeenCalledTimes(1));
    expect(approveRunMock).toHaveBeenCalledWith(
      'run-abc123def',
      expect.objectContaining({ decision: 'rejected' }),
    );
  });

  it('surfaces an error on 409 and does not resolve', async () => {
    approveRunMock.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409 },
      message: 'conflict',
    });
    const { onResolved } = renderCard();
    fireEvent.click(screen.getByTestId('wf-approve-approve'));
    await waitFor(() => expect(errorSpy).toHaveBeenCalled());
    expect(onResolved).not.toHaveBeenCalled();
    // Card remains mounted (still resolvable).
    expect(screen.getByTestId('wf-approve-approve')).toBeInTheDocument();
  });
});
