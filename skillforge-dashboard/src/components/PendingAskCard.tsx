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
  otherInput: string;
  onOtherInputChange: (v: string) => void;
  onAnswer: (answer: string) => void;
}

const PendingAskCard: React.FC<PendingAskCardProps> = ({
  pendingAsk,
  otherInput,
  onOtherInputChange,
  onAnswer,
}) => {
  const trimmed = otherInput.trim();
  return (
    <div className="ask-card" role="dialog" aria-label="Agent is asking">
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
            onClick={() => onAnswer(opt.label)}
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
              onChange={(e) => onOtherInputChange(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && trimmed) {
                  e.preventDefault();
                  onAnswer(trimmed);
                }
              }}
            />
            <button
              type="button"
              disabled={!trimmed}
              onClick={() => onAnswer(trimmed)}
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
