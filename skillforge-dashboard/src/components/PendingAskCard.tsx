import React from 'react';
import { IconSparkle } from './chat/ChatIcons';

interface PendingAskOption {
  label: string;
  description?: string;
}
interface PendingAsk {
  askId: string;
  question: string;
  context?: string;
  options: PendingAskOption[];
  allowOther: boolean;
}

interface PendingAskCardProps {
  pendingAsk: PendingAsk;
  otherInput?: string;
  onOtherInputChange?: (v: string) => void;
  onAnswer: (answer: string) => void;
  answeredAt?: string;
  state?: string;
  answer?: string;
}

const PendingAskCard: React.FC<PendingAskCardProps> = ({
  pendingAsk,
  otherInput: controlledOtherInput,
  onOtherInputChange,
  onAnswer,
  answeredAt,
  state,
  answer,
}) => {
  const [localOtherInput, setLocalOtherInput] = React.useState('');
  const [submittedAnswer, setSubmittedAnswer] = React.useState<string | null>(null);
  const otherInput = controlledOtherInput ?? localOtherInput;
  const setOtherInput = onOtherInputChange ?? setLocalOtherInput;
  const trimmed = otherInput.trim();
  const summaryAnswer = answer ?? submittedAnswer ?? undefined;
  const isAnswered = !!answeredAt || submittedAnswer != null;
  const submit = (value: string) => {
    if (submittedAnswer != null || answeredAt) return;
    setSubmittedAnswer(value);
    onAnswer(value);
  };
  if (isAnswered) {
    const label = state === 'superseded' ? 'Answered by later message' : state === 'expired' ? 'Expired' : 'Answered';
    return (
      <div className="ask-card ask-card-summary" aria-label="Ask summary">
        <div className="ask-header">
          <IconSparkle s={11} />
          {label}
        </div>
        <div className="ask-q">{pendingAsk.question}</div>
        {summaryAnswer && <div className="ask-ctx">{summaryAnswer}</div>}
      </div>
    );
  }
  return (
    <div className="ask-card" role="group" aria-label="Agent is asking">
      <div className="ask-header">
        <IconSparkle s={11} />
        Agent is asking
      </div>
      <div className="ask-q">{pendingAsk.question}</div>
      {pendingAsk.context && <div className="ask-ctx">{pendingAsk.context}</div>}
      <div className="ask-options">
        {pendingAsk.options.map((opt, i) => (
          <button
            type="button"
            key={i}
            className="ask-opt"
            onClick={() => submit(opt.label)}
          >
            <div className="ask-opt-label">{opt.label}</div>
            {opt.description && (
              <div className="ask-opt-desc">{opt.description}</div>
            )}
          </button>
        ))}
        {pendingAsk.allowOther && (
          <div className="ask-other">
            <input
              placeholder="Or write your own answer…"
              value={otherInput}
              onChange={(e) => setOtherInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && trimmed) {
                  e.preventDefault();
                  submit(trimmed);
                }
              }}
            />
            <button
              type="button"
              disabled={!trimmed}
              onClick={() => submit(trimmed)}
            >
              Send
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default PendingAskCard;
