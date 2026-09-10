import React, { useRef } from 'react';

export type CheckpointActionKind = 'branch' | 'restore';

interface CheckpointActionConfirmationProps {
  action: CheckpointActionKind;
  loading?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

const CheckpointActionConfirmation: React.FC<CheckpointActionConfirmationProps> = ({
  action,
  loading = false,
  onCancel,
  onConfirm,
}) => {
  const submittedRef = useRef(false);
  const destructive = action === 'restore';

  return (
    <div
      className="checkpoint-action-confirm"
      role="alertdialog"
      aria-modal="true"
      aria-label={destructive ? 'Confirm checkpoint restore' : 'Confirm checkpoint branch'}
    >
      <strong>{destructive ? 'Restore this checkpoint in place?' : 'Create a branch from this checkpoint?'}</strong>
      <p>
        {destructive
          ? 'This destructive action removes messages and runtime state after the checkpoint. It does not replay an Agent turn, cannot be undone, and retained resolution audits remain.'
          : 'A new Session is created from the checkpoint. The current Session stays unchanged; queued messages, active attempts, and parent archives are not copied.'}
      </p>
      <div>
        <button type="button" disabled={loading} onClick={onCancel}>Cancel</button>
        <button
          type="button"
          disabled={loading}
          onClick={() => {
            if (submittedRef.current) return;
            submittedRef.current = true;
            onConfirm();
          }}
        >
          {loading
            ? (destructive ? 'Restoring…' : 'Creating branch…')
            : (destructive ? 'Restore destructively' : 'Create new Session')}
        </button>
      </div>
    </div>
  );
};

export default CheckpointActionConfirmation;
