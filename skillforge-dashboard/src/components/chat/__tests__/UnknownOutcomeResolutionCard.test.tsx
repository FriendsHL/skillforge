import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ resolveUnknownOutcome: vi.fn() }));
vi.mock('../../../api', () => ({
  resolveUnknownOutcome: mocks.resolveUnknownOutcome,
  isUnknownOutcomeResolutionAck: (
    value: { resolutionRequestId?: string; sessionId?: string },
    targetValue: { sessionId: string },
    command: { resolutionRequestId: string },
  ) => value?.resolutionRequestId === command.resolutionRequestId
    && value?.sessionId === targetValue.sessionId,
}));

import UnknownOutcomeResolutionCard from '../UnknownOutcomeResolutionCard';

const resolveUnknownOutcome = mocks.resolveUnknownOutcome;

const target = {
  sessionId: 'session-1',
  attemptId: 41,
  historyEpoch: 3,
  executionGeneration: 8,
  executionFence: 13,
  state: 'UNCERTAIN_PENDING_RESOLUTION' as const,
  actorAuthority: 'OWNER' as const,
  calls: [{
    providerOrdinal: 0,
    toolUseId: 'tool-use-1',
    toolName: 'ShellTool',
    input: '{"command":"</pre><script>alert(1)</script>"}',
  }],
  inboxIds: ['0a9d6af1-9fc1-45d8-85c7-37c4acee7051'],
};

const ack = {
  resolutionRequestId: '94bbb1a1-7135-4029-8a46-5b389ac813bd',
  sessionId: 'session-1',
  attemptId: 41,
  stepId: 'e00d6df5-c43b-4696-97c2-902fc9d3c788',
  historyEpoch: 3,
  executionGeneration: 8,
  executionFence: 13,
  actorAuthority: 'ADMIN' as const,
  action: 'CONTINUE_CURRENT_TIMELINE' as const,
  resultBatchId: 'f4f21af0-2755-4ae3-866e-284fe6eefcec',
  outcomeState: 'RESOLVED_UNKNOWN' as const,
  inboxDispositions: [{
    inboxId: target.inboxIds[0],
    disposition: 'KEEP_FOR_CONTINUE' as const,
  }],
  postActionState: 'PENDING' as const,
  restorePreparing: false,
  auditId: 12,
  resolvedAt: '2026-09-04T00:00:00Z',
};

function enterReasonAndReview(): void {
  fireEvent.change(screen.getByLabelText('Decision reason'), {
    target: { value: 'Checked external state; continue safely.' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Review decision' }));
}

describe('UnknownOutcomeResolutionCard', () => {
  beforeEach(() => {
    resolveUnknownOutcome.mockReset();
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn(() => '94bbb1a1-7135-4029-8a46-5b389ac813bd'),
    });
  });

  it('states that the Tool may have succeeded and forbids automatic retry', () => {
    render(<UnknownOutcomeResolutionCard target={target} />);

    expect(screen.getByText('UNCERTAIN_PENDING_RESOLUTION')).toBeInTheDocument();
    expect(screen.getByText('Authorized as the Session owner.')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('可能已经成功');
    expect(screen.getByRole('alert')).toHaveTextContent('禁止自动重试');
  });

  it('renders verified Tool name and input only as text', () => {
    const { container } = render(<UnknownOutcomeResolutionCard target={target} />);

    expect(screen.getByText('ShellTool')).toBeInTheDocument();
    expect(screen.getByLabelText('ShellTool input')).toHaveTextContent(
      '</pre><script>alert(1)</script>',
    );
    expect(container.querySelector('script')).toBeNull();
  });

  it('shows explicit admin authorization context', () => {
    render(
      <UnknownOutcomeResolutionCard
        target={{ ...target, actorAuthority: 'ADMIN' }}
      />,
    );

    expect(screen.getByText(/Authorized administrator/)).toHaveTextContent(
      'session:resolve-unknown',
    );
  });

  it('keeps an in-progress decision when discovery refetches the same identity', () => {
    const { rerender } = render(<UnknownOutcomeResolutionCard target={target} />);
    fireEvent.change(screen.getByLabelText('Decision reason'), {
      target: { value: 'Do not erase this review.' },
    });

    rerender(
      <UnknownOutcomeResolutionCard
        target={{ ...target, inboxIds: [...target.inboxIds] }}
      />,
    );

    expect(screen.getByLabelText('Decision reason')).toHaveValue('Do not erase this review.');
  });

  it('requires confirmation and submits the exact CONTINUE command only once', async () => {
    let finish: ((value: unknown) => void) | undefined;
    resolveUnknownOutcome.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    render(<UnknownOutcomeResolutionCard target={target} />);
    enterReasonAndReview();

    const confirm = screen.getByRole('button', { name: 'Confirm audited decision' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);

    expect(resolveUnknownOutcome).toHaveBeenCalledTimes(1);
    expect(resolveUnknownOutcome).toHaveBeenCalledWith('session-1', 41, {
      resolutionRequestId: '94bbb1a1-7135-4029-8a46-5b389ac813bd',
      expectedHistoryEpoch: 3,
      expectedExecutionGeneration: 8,
      expectedExecutionFence: 13,
      action: 'CONTINUE_CURRENT_TIMELINE',
      reason: 'Checked external state; continue safely.',
      inboxDispositions: [{
        inboxId: target.inboxIds[0],
        disposition: 'KEEP_FOR_CONTINUE',
      }],
    });

    finish?.({ data: ack });
    expect(await screen.findByText(/RESOLVED_UNKNOWN/)).toBeInTheDocument();
  });

  it('reuses the same request ID after an ACK-loss-shaped failure', async () => {
    resolveUnknownOutcome
      .mockRejectedValueOnce(new Error('network lost after commit'))
      .mockResolvedValueOnce({ data: ack });
    render(<UnknownOutcomeResolutionCard target={target} />);
    enterReasonAndReview();

    fireEvent.click(screen.getByRole('button', { name: 'Confirm audited decision' }));
    expect(await screen.findByText(/acknowledgement was not received/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm audited decision' }));

    await waitFor(() => expect(resolveUnknownOutcome).toHaveBeenCalledTimes(2));
    expect(resolveUnknownOutcome.mock.calls[1][2].resolutionRequestId)
      .toBe(resolveUnknownOutcome.mock.calls[0][2].resolutionRequestId);
  });

  it('sends an explicit disposition for every inbox item when preparing restore', async () => {
    resolveUnknownOutcome.mockResolvedValueOnce({
      data: {
        ...ack,
        action: 'PREPARE_RESTORE',
        inboxDispositions: [{
          inboxId: target.inboxIds[0],
          disposition: 'DISCARD_FOR_RESTORE',
        }],
        postActionState: 'NONE',
        restorePreparing: true,
      },
    });
    render(<UnknownOutcomeResolutionCard target={target} />);
    fireEvent.click(screen.getByRole('radio', { name: 'Prepare checkpoint restore' }));
    fireEvent.change(screen.getByLabelText('Queued message 1 disposition'), {
      target: { value: 'DISCARD_FOR_RESTORE' },
    });
    enterReasonAndReview();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm audited decision' }));

    await waitFor(() => expect(resolveUnknownOutcome).toHaveBeenCalledTimes(1));
    expect(resolveUnknownOutcome.mock.calls[0][2]).toMatchObject({
      action: 'PREPARE_RESTORE',
      inboxDispositions: [{
        inboxId: target.inboxIds[0],
        disposition: 'DISCARD_FOR_RESTORE',
      }],
    });
  });

  it.each([403, 404, 409])('does not echo server detail for stale or unauthorized %s', async (status) => {
    const onUnavailable = vi.fn();
    resolveUnknownOutcome.mockRejectedValueOnce({
      response: { status, data: { error: 'secret owner/attempt detail' } },
    });
    render(
      <UnknownOutcomeResolutionCard
        target={target}
        onUnavailable={onUnavailable}
      />,
    );
    enterReasonAndReview();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm audited decision' }));

    expect(await screen.findByText(/no longer available/i)).toBeInTheDocument();
    expect(screen.queryByText(/secret owner/)).not.toBeInTheDocument();
    expect(onUnavailable).toHaveBeenCalledTimes(1);
  });
});
