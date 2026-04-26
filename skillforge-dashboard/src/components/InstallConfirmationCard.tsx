import type {
  ConfirmationChoice,
  ConfirmationDecision,
  ConfirmationPromptPayload,
} from '../api';

interface InstallConfirmationCardProps {
  payload: ConfirmationPromptPayload;
  submitting: boolean;
  /** The confirmationId that is currently in-flight, if any — used to show a
   *  per-button loading state (approve vs deny). */
  submittingDecision: ConfirmationDecision | null;
  onDecision: (decision: ConfirmationDecision) => void;
}

function choiceToDecision(choice: ConfirmationChoice): ConfirmationDecision | null {
  if (choice.variant === 'approve') return 'APPROVED';
  if (choice.variant === 'deny') return 'DENIED';
  if (choice.style === 'primary') return 'APPROVED';
  if (choice.style === 'danger') return 'DENIED';
  // Fallback: derive from id / label so the card still works if backend
  // omits `variant` in the payload.
  const hay = `${choice.id ?? choice.value ?? ''} ${choice.label}`.toLowerCase();
  if (/approve|allow|yes|accept|确认|允许|同意/.test(hay)) return 'APPROVED';
  if (/deny|reject|no|cancel|拒绝|取消/.test(hay)) return 'DENIED';
  return null;
}

function InstallConfirmationCard({
  payload,
  submitting,
  submittingDecision,
  onDecision,
}: InstallConfirmationCardProps) {
  const isCreateAgent = payload.installTool === 'CreateAgent';
  const needsWarnBanner =
    !isCreateAgent &&
    (payload.installTarget === '*' ||
      payload.installTool === 'multiple' ||
      payload.installTool === 'unknown');

  // Normalize choices to guaranteed approve / deny buttons. If backend sends
  // extra choices we render them pass-through, but we always guarantee the two
  // defaults (the plan specifies two decisions only).
  const approveChoice = payload.choices.find((c) => choiceToDecision(c) === 'APPROVED');
  const denyChoice = payload.choices.find((c) => choiceToDecision(c) === 'DENIED');

  return (
    <div
      className="confirm-card"
      role="dialog"
      aria-label={isCreateAgent ? 'Agent creation approval required' : 'Install confirmation required'}
    >
      <div className="confirm-header">
        <span className="confirm-header-dot" aria-hidden="true" />
        {isCreateAgent ? 'Agent creation approval' : 'Install confirmation'}
      </div>
      <div className="confirm-title">{payload.title}</div>
      <div className="confirm-meta">
        {isCreateAgent ? 'action' : 'tool'}: <strong>{payload.installTool}</strong>
        <span className="confirm-meta-sep">·</span>
        {isCreateAgent ? 'agent' : 'target'}: <code className="confirm-target">{payload.installTarget}</code>
        <span className="confirm-meta-sep">·</span>
        <span className="confirm-meta-dim">{isCreateAgent ? 'one-time approval' : 'session-only'}</span>
      </div>

      {payload.description && (
        <div className="confirm-desc">{payload.description}</div>
      )}

      <pre className="confirm-cmd" aria-label={isCreateAgent ? 'request preview' : 'command preview'}>
        <code>{payload.commandPreview}</code>
      </pre>

      {needsWarnBanner && (
        <div className="confirm-warn" role="alert">
          <span className="confirm-warn-icon" aria-hidden="true">⚠</span>
          <span>
            未能解析具体安装目标，本次确认<strong>不会</strong>被缓存；下次 install 仍会弹卡。
          </span>
        </div>
      )}

      {isCreateAgent ? (
        <div className="confirm-scope-note">
          Approving this creates one active Agent owned by the current user. The
          approval token is single-use and cannot be reused by hooks or later tool calls.
        </div>
      ) : (
        <div className="confirm-scope-note">
          Approving this will also allow sub-agents and team members spawned from
          this session to install the <strong>same target</strong> without
          re-prompting. Installing a different target will prompt you again.
        </div>
      )}

      <div className="confirm-actions">
        <button
          type="button"
          className="confirm-btn confirm-btn--deny"
          disabled={submitting}
          onClick={() => onDecision('DENIED')}
        >
          {submitting && submittingDecision === 'DENIED'
            ? 'Denying…'
            : denyChoice?.label ?? 'Deny'}
        </button>
        <button
          type="button"
          className="confirm-btn confirm-btn--approve"
          disabled={submitting}
          onClick={() => onDecision('APPROVED')}
        >
          {submitting && submittingDecision === 'APPROVED'
            ? 'Approving…'
            : approveChoice?.label ?? 'Approve'}
        </button>
      </div>
    </div>
  );
}

export default InstallConfirmationCard;
