import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CheckpointActionConfirmation from '../CheckpointActionConfirmation';

describe('CheckpointActionConfirmation', () => {
  it('explains branch isolation before creating a new Session', () => {
    const onConfirm = vi.fn();
    render(
      <CheckpointActionConfirmation
        action="branch"
        onCancel={vi.fn()}
        onConfirm={onConfirm}
      />,
    );

    const dialog = screen.getByRole('alertdialog', { name: 'Confirm checkpoint branch' });
    expect(dialog).toHaveTextContent('new Session');
    expect(dialog).toHaveTextContent('current Session stays unchanged');
    expect(dialog).toHaveTextContent('active attempts');
    fireEvent.click(screen.getByRole('button', { name: 'Create new Session' }));
    fireEvent.click(screen.getByRole('button', { name: 'Create new Session' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('labels restore as destructive and states pruning/no replay semantics', () => {
    const onConfirm = vi.fn();
    render(
      <CheckpointActionConfirmation
        action="restore"
        onCancel={vi.fn()}
        onConfirm={onConfirm}
      />,
    );

    const dialog = screen.getByRole('alertdialog', { name: 'Confirm checkpoint restore' });
    expect(dialog).toHaveTextContent('destructive action');
    expect(dialog).toHaveTextContent('removes messages and runtime state after the checkpoint');
    expect(dialog).toHaveTextContent('does not replay an Agent turn');
    expect(dialog).toHaveTextContent('cannot be undone');
    fireEvent.click(screen.getByRole('button', { name: 'Restore destructively' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });
});
