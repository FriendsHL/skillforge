import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { GOAL_BRIEF_ACTION_MESSAGES, type GoalBrief } from '../../../api/sessionTasks';
import GoalBriefCard from '../GoalBriefCard';

const brief: GoalBrief = {
  kind: 'goal_brief', schemaVersion: 1, proposalStatus: 'proposed',
  outcome: '<img src=x onerror=alert(1)> Finish the novel',
  representativeExample: 'A coherent first chapter',
  antiGoals: ['Do not imitate a living author'], askBefore: ['Publishing'],
  sourceQuote: 'Help me write a novel',
  fieldSources: {
    outcome: 'USER_STATED', representativeExample: 'SYSTEM_INFERRED',
    antiGoals: 'UNKNOWN', askBefore: 'CONFLICTING',
  },
};

describe('GoalBriefCard', () => {
  it('renders four fields, source labels and untrusted text without HTML execution', () => {
    const { container } = render(
      <GoalBriefCard brief={brief} disabled={false} onAction={vi.fn()} />,
    );
    expect(screen.getByText('Finish the novel', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('A coherent first chapter')).toBeInTheDocument();
    expect(screen.getByText('Do not imitate a living author')).toBeInTheDocument();
    expect(screen.getByText('Publishing')).toBeInTheDocument();
    expect(screen.getByText('User stated')).toBeInTheDocument();
    expect(screen.getByText('Conflicting')).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
  });

  it.each([
    ['Confirm and continue', GOAL_BRIEF_ACTION_MESSAGES.confirm],
    ['Needs changes', GOAL_BRIEF_ACTION_MESSAGES.revise],
    ['Just this once', GOAL_BRIEF_ACTION_MESSAGES.once],
  ])('sends the exact ordinary message for %s', async (label, expected) => {
    const onAction = vi.fn().mockResolvedValue(undefined);
    render(<GoalBriefCard brief={brief} disabled={false} onAction={onAction} />);
    fireEvent.click(screen.getByRole('button', { name: label }));
    await waitFor(() => expect(onAction).toHaveBeenCalledWith(expected));
  });

  it('uses a synchronous submitting lock so a rapid double click sends once', async () => {
    let resolve!: () => void;
    const onAction = vi.fn(() => new Promise<void>((done) => { resolve = done; }));
    render(<GoalBriefCard brief={brief} disabled={false} onAction={onAction} />);
    const button = screen.getByRole('button', { name: 'Confirm and continue' });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(onAction).toHaveBeenCalledTimes(1);
    expect(button).toBeDisabled();
    await act(async () => resolve());
  });

  it('consumes the existing runtime disabled state', () => {
    render(<GoalBriefCard brief={brief} disabled onAction={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Confirm and continue' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Needs changes' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Just this once' })).toBeDisabled();
  });
});
