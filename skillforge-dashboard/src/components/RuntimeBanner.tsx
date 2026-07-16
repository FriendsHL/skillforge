import React from 'react';
import type { RuntimeStatus } from '../hooks/useChatSession';

interface RuntimeBannerProps {
  runtimeStatus: RuntimeStatus;
  runtimeStep: string;
  runtimeError: string;
  cancelling: boolean;
  retrying: boolean;
  onCancel: () => void;
  onRetry: () => void;
}

const RuntimeBanner: React.FC<RuntimeBannerProps> = ({
  runtimeStatus,
  runtimeStep,
  runtimeError,
  cancelling,
  retrying,
  onCancel,
  onRetry,
}) => {
  if (runtimeStatus === 'idle' && runtimeStep !== 'cancelled') return null;

  if (runtimeStatus === 'running') {
    return (
      <div className="runtime-rd running" role="status">
        <span className="pulse" />
        <span className="step-text">
          <strong>Agent is running</strong>
          {runtimeStep ? <> · {runtimeStep}</> : null}
        </span>
        <button
          type="button"
          className="cancel-btn"
          disabled={cancelling}
          onClick={onCancel}
        >
          {cancelling ? 'Cancelling…' : 'Cancel'}
        </button>
      </div>
    );
  }

  if (runtimeStatus === 'waiting_user') {
    return (
      <div className="runtime-rd waiting" role="status">
        <span className="pulse" />
        <span className="step-text">
          <strong>Waiting on your answer</strong>
        </span>
      </div>
    );
  }

  if (runtimeStatus === 'error') {
    return (
      <div className="runtime-rd err" role="alert">
        <span className="pulse" />
        <span className="step-text">
          <strong>Runtime error</strong>
          {runtimeError ? <> · {runtimeError}</> : null}
        </span>
        {runtimeStep === 'retryable' && (
          <button
            type="button"
            className="retry-btn"
            disabled={retrying}
            aria-label={retrying ? 'Retrying failed turn' : 'Retry failed turn'}
            onClick={onRetry}
          >
            {retrying ? 'Retrying…' : 'Retry'}
          </button>
        )}
      </div>
    );
  }

  if (runtimeStatus === 'idle' && runtimeStep === 'cancelled') {
    return (
      <div className="runtime-rd waiting" role="status">
        <span className="pulse" />
        <span className="step-text">
          <strong>Cancelled</strong>
        </span>
      </div>
    );
  }

  return null;
};

export default RuntimeBanner;
