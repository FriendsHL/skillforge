import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  resolveUnknownOutcome,
  isUnknownOutcomeResolutionAck,
  type UnknownOutcomeAction,
  type UnknownOutcomeInboxDispositionKind,
  type UnknownOutcomeResolutionAck,
  type UnknownOutcomeResolutionRequest,
  type UnknownOutcomeTarget,
} from '../../api';

interface UnknownOutcomeResolutionCardProps {
  target: UnknownOutcomeTarget;
  onResolved?: (ack: UnknownOutcomeResolutionAck) => void;
  onUnavailable?: () => void;
}

interface PendingRequest {
  fingerprint: string;
  requestId: string;
}

function createRequestId(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') return cryptoApi.randomUUID();
  if (typeof cryptoApi?.getRandomValues !== 'function') {
    throw new Error('Secure UUID generation is unavailable');
  }
  const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function httpStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object') return undefined;
  const response = (error as { response?: unknown }).response;
  if (!response || typeof response !== 'object') return undefined;
  const status = (response as { status?: unknown }).status;
  return typeof status === 'number' ? status : undefined;
}

const UnknownOutcomeResolutionCard: React.FC<UnknownOutcomeResolutionCardProps> = ({
  target,
  onResolved,
  onUnavailable,
}) => {
  const inboxIdentity = target.inboxIds.join('\u0000');
  const targetIdentity = [
    target.sessionId,
    target.attemptId,
    target.historyEpoch,
    target.executionGeneration,
    target.executionFence,
    target.actorAuthority,
    JSON.stringify(target.calls),
    inboxIdentity,
  ].join(':');
  const [action, setAction] = useState<UnknownOutcomeAction>('CONTINUE_CURRENT_TIMELINE');
  const [reason, setReason] = useState('');
  const [restoreDispositions, setRestoreDispositions] = useState<Record<string, UnknownOutcomeInboxDispositionKind>>({});
  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<string>('');
  const [resolved, setResolved] = useState<UnknownOutcomeResolutionAck | null>(null);
  const pendingRequestRef = useRef<PendingRequest | null>(null);
  const submittingRef = useRef(false);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    setAction('CONTINUE_CURRENT_TIMELINE');
    setReason('');
    setRestoreDispositions(Object.fromEntries(
      (inboxIdentity === '' ? [] : inboxIdentity.split('\u0000'))
        .map((inboxId) => [inboxId, 'KEEP_FOR_RESTORE']),
    ));
    setConfirming(false);
    setSubmitting(false);
    submittingRef.current = false;
    setFeedback('');
    setResolved(null);
    pendingRequestRef.current = null;
  }, [targetIdentity, inboxIdentity]);

  const dispositions = useMemo(() => target.inboxIds.map((inboxId) => ({
    inboxId,
    disposition: action === 'CONTINUE_CURRENT_TIMELINE'
      ? 'KEEP_FOR_CONTINUE' as const
      : restoreDispositions[inboxId] ?? 'KEEP_FOR_RESTORE',
  })), [action, restoreDispositions, target.inboxIds]);

  const validReason = reason.trim().length > 0 && Array.from(reason).length <= 2_000;

  const changeDecision = (nextAction: UnknownOutcomeAction) => {
    if (submitting) return;
    setAction(nextAction);
    setConfirming(false);
    setFeedback('');
    pendingRequestRef.current = null;
  };

  const changeReason = (value: string) => {
    setReason(value);
    setConfirming(false);
    setFeedback('');
    pendingRequestRef.current = null;
  };

  const submit = async () => {
    if (submittingRef.current || !validReason) return;
    submittingRef.current = true;
    const identity = `${target.sessionId}:${target.attemptId}`;
    const fingerprint = JSON.stringify({ identity, action, reason: reason.trim(), dispositions });
    const existing = pendingRequestRef.current;
    let requestId: string;
    try {
      requestId = existing?.fingerprint === fingerprint
        ? existing.requestId
        : createRequestId();
    } catch {
      submittingRef.current = false;
      setFeedback('A secure resolution request ID could not be created. No request was sent.');
      return;
    }
    pendingRequestRef.current = { fingerprint, requestId };
    const command: UnknownOutcomeResolutionRequest = {
      resolutionRequestId: requestId,
      expectedHistoryEpoch: target.historyEpoch,
      expectedExecutionGeneration: target.executionGeneration,
      expectedExecutionFence: target.executionFence,
      action,
      reason: reason.trim(),
      inboxDispositions: dispositions,
    };
    setSubmitting(true);
    setFeedback('');
    try {
      const response = await resolveUnknownOutcome(target.sessionId, target.attemptId, command);
      if (!mountedRef.current) return;
      if (!isUnknownOutcomeResolutionAck(response.data as unknown, target, command)) {
        pendingRequestRef.current = null;
        setConfirming(false);
        setFeedback('This resolution is no longer available. Refresh the session state.');
        onUnavailable?.();
        return;
      }
      pendingRequestRef.current = null;
      setResolved(response.data);
      setConfirming(false);
      onResolved?.(response.data);
    } catch (error: unknown) {
      if (!mountedRef.current) return;
      const status = httpStatus(error);
      if (status === 403 || status === 404 || status === 409) {
        pendingRequestRef.current = null;
        setConfirming(false);
        setFeedback('This resolution is no longer available. Refresh the session state.');
        onUnavailable?.();
      } else {
        setFeedback('The acknowledgement was not received. Nothing will retry automatically; retrying here reuses the same request ID.');
      }
    } finally {
      submittingRef.current = false;
      if (mountedRef.current) setSubmitting(false);
    }
  };

  if (resolved) {
    return (
      <section className="unknown-outcome-card unknown-outcome-resolved" aria-label="Unknown outcome resolved">
        <strong>Outcome recorded as unknown</strong>
        <span>RESOLVED_UNKNOWN · {resolved.action}</span>
      </section>
    );
  }

  return (
    <section className="unknown-outcome-card" aria-label="Unknown tool outcome resolution">
      <div className="unknown-outcome-state">UNCERTAIN_PENDING_RESOLUTION</div>
      <h3>Tool outcome needs your decision</h3>
      <p className="unknown-outcome-authority">
        {target.actorAuthority === 'OWNER'
          ? 'Authorized as the Session owner.'
          : 'Authorized administrator · session:resolve-unknown'}
      </p>
      <div className="unknown-outcome-warning" role="alert">
        工具操作可能已经成功。禁止自动重试；请先检查外部状态，再明确选择后续动作。
      </div>

      <ol className="unknown-outcome-tools" aria-label="Uncertain Tool calls">
        {target.calls.map((call) => (
          <li key={call.toolUseId}>
            <div>
              <strong>{call.toolName}</strong>
              <span>Tool {call.providerOrdinal + 1}</span>
            </div>
            <pre aria-label={`${call.toolName} input`}>{call.input}</pre>
          </li>
        ))}
      </ol>

      <div className="unknown-outcome-actions" role="radiogroup" aria-label="Post-resolution action">
        <button
          type="button"
          role="radio"
          aria-checked={action === 'CONTINUE_CURRENT_TIMELINE'}
          disabled={submitting}
          onClick={() => changeDecision('CONTINUE_CURRENT_TIMELINE')}
        >
          Continue current timeline
        </button>
        <button
          type="button"
          role="radio"
          aria-checked={action === 'PREPARE_RESTORE'}
          disabled={submitting}
          onClick={() => changeDecision('PREPARE_RESTORE')}
        >
          Prepare checkpoint restore
        </button>
      </div>

      {target.inboxIds.length > 0 && (
        <div className="unknown-outcome-inbox">
          <strong>Queued messages ({target.inboxIds.length})</strong>
          {target.inboxIds.map((inboxId, index) => (
            <label key={inboxId}>
              <span>Queued message {index + 1}</span>
              {action === 'CONTINUE_CURRENT_TIMELINE' ? (
                <span>Keep for continue</span>
              ) : (
                <select
                  aria-label={`Queued message ${index + 1} disposition`}
                  disabled={submitting}
                  value={restoreDispositions[inboxId] ?? 'KEEP_FOR_RESTORE'}
                  onChange={(event) => {
                    setRestoreDispositions((current) => ({
                      ...current,
                      [inboxId]: event.target.value as UnknownOutcomeInboxDispositionKind,
                    }));
                    setConfirming(false);
                    setFeedback('');
                    pendingRequestRef.current = null;
                  }}
                >
                  <option value="KEEP_FOR_RESTORE">Keep (restore remains blocked)</option>
                  <option value="DISCARD_FOR_RESTORE">Discard for restore</option>
                </select>
              )}
            </label>
          ))}
        </div>
      )}

      <label className="unknown-outcome-reason">
        Decision reason
        <textarea
          value={reason}
          maxLength={4_000}
          disabled={submitting}
          onChange={(event) => changeReason(event.target.value)}
        />
      </label>

      {confirming ? (
        <div className="unknown-outcome-confirm" role="dialog" aria-label="Confirm unknown outcome resolution">
          <strong>Confirm this audited decision?</strong>
          <p>
            {action === 'CONTINUE_CURRENT_TIMELINE'
              ? 'Unknown results will be recorded, queued messages kept, then the current timeline may continue once.'
              : 'Unknown results will be recorded and restore preparation enabled. Kept messages continue to block restore.'}
          </p>
          <button type="button" disabled={submitting} onClick={() => setConfirming(false)}>Back</button>
          <button type="button" disabled={submitting} onClick={() => void submit()}>
            {submitting ? 'Recording decision…' : 'Confirm audited decision'}
          </button>
        </div>
      ) : (
        <button
          type="button"
          disabled={!validReason || submitting}
          onClick={() => setConfirming(true)}
        >
          Review decision
        </button>
      )}

      {feedback && <div className="unknown-outcome-feedback" role="status">{feedback}</div>}
    </section>
  );
};

export default UnknownOutcomeResolutionCard;
