import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SessionTaskDto } from '../../../api/sessionTasks';
import SessionTaskProgress from '../SessionTaskProgress';

function task(overrides: Partial<SessionTaskDto> = {}): SessionTaskDto {
  return {
    taskId: 'task-1',
    subject: 'Prepare digest',
    description: 'Prepare and verify the daily digest.',
    activeForm: null,
    status: 'pending',
    owner: null,
    blocked: false,
    blockedBy: [],
    blocks: [],
    createdAt: '2026-08-05T10:00:00Z',
    updatedAt: '2026-08-05T10:01:00Z',
    version: 1,
    metadata: null,
    ...overrides,
  };
}

describe('SessionTaskProgress', () => {
  it('shows completion, current active form and blocked count while hiding deleted tasks', () => {
    render(
      <SessionTaskProgress
        tasks={[
          task({ taskId: 'done', subject: 'Collect sources', status: 'completed' }),
          task({ taskId: 'active', subject: 'Write digest', status: 'in_progress', activeForm: 'Writing digest' }),
          task({ taskId: 'blocked', subject: 'Publish', blocked: true, blockedBy: ['active'] }),
          task({ taskId: 'deleted', subject: 'Discarded', status: 'deleted' }),
        ]}
        loading={false}
        error={null}
        onRetry={vi.fn()}
      />,
    );

    expect(screen.getByText('1 / 3 completed')).toBeInTheDocument();
    expect(screen.getByText('Writing digest')).toBeInTheDocument();
    expect(screen.getByText('1 blocked')).toBeInTheDocument();
    expect(screen.queryByText('Discarded')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Show session tasks' }));
    expect(screen.getByText('Collect sources')).toBeInTheDocument();
    expect(screen.getByText('Write digest')).toBeInTheDocument();
    expect(screen.getByText('Publish')).toBeInTheDocument();
    expect(screen.queryByText('Discarded')).not.toBeInTheDocument();
  });

  it('renders loading and error only inside the task region', () => {
    const onRetry = vi.fn();
    const { rerender } = render(
      <SessionTaskProgress tasks={[]} loading error={null} onRetry={onRetry} />,
    );
    expect(screen.getByRole('status', { name: 'Loading session tasks' })).toBeInTheDocument();

    rerender(
      <SessionTaskProgress
        tasks={[]}
        loading={false}
        error="Task progress unavailable"
        onRetry={onRetry}
      />,
    );
    expect(screen.getByText('Task progress unavailable')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry task progress' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('renders nothing when the session has no tasks', () => {
    const { container } = render(
      <SessionTaskProgress tasks={[]} loading={false} error={null} onRetry={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('shows only the latest valid goal brief and excludes briefs from ordinary progress', () => {
    const goalMetadata = (outcome: string) => ({
      kind: 'goal_brief', schemaVersion: 1, proposalStatus: 'proposed', outcome,
      representativeExample: 'A finished deliverable', antiGoals: ['No silent release'],
      askBefore: ['Publishing'], sourceQuote: outcome,
      fieldSources: {
        outcome: 'USER_STATED', representativeExample: 'SYSTEM_INFERRED',
        antiGoals: 'USER_STATED', askBefore: 'CONFLICTING',
      },
    });
    render(
      <SessionTaskProgress
        tasks={[
          task({ taskId: 'ordinary', status: 'completed' }),
          task({ taskId: 'brief-a', createdAt: '2026-08-05T10:02:00Z', metadata: goalMetadata('Older goal') }),
          task({ taskId: 'brief-b', createdAt: '2026-08-05T10:02:00Z', metadata: goalMetadata('Latest goal') }),
        ]}
        loading={false}
        error={null}
        onRetry={vi.fn()}
        onGoalBriefAction={vi.fn()}
        goalBriefActionDisabled={false}
      />,
    );

    expect(screen.getByText('Latest goal')).toBeInTheDocument();
    expect(screen.queryByText('Older goal')).not.toBeInTheDocument();
    expect(screen.getByText('1 / 1 completed')).toBeInTheDocument();
  });

  it('falls back to rendering an invalid goal brief as an ordinary task', () => {
    render(
      <SessionTaskProgress
        tasks={[task({ metadata: { kind: 'goal_brief', schemaVersion: 1 } })]}
        loading={false}
        error={null}
        onRetry={vi.fn()}
        onGoalBriefAction={vi.fn()}
        goalBriefActionDisabled={false}
      />,
    );
    expect(screen.getByText('0 / 1 completed')).toBeInTheDocument();
  });

  it('does not show a deleted goal brief', () => {
    const { container } = render(
      <SessionTaskProgress
        tasks={[task({ status: 'deleted', metadata: {
          kind: 'goal_brief', schemaVersion: 1, proposalStatus: 'proposed', outcome: 'Deleted goal',
          representativeExample: 'Example', antiGoals: [], askBefore: [], sourceQuote: 'Quote',
          fieldSources: { outcome: 'USER_STATED', representativeExample: 'UNKNOWN', antiGoals: 'UNKNOWN', askBefore: 'UNKNOWN' },
        } })]}
        loading={false}
        error={null}
        onRetry={vi.fn()}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
