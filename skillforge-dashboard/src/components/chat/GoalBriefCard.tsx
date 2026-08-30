import React, { useRef, useState } from 'react';
import {
  GOAL_BRIEF_ACTION_MESSAGES,
  type GoalBrief,
  type GoalBriefFieldSource,
} from '../../api/sessionTasks';
import './GoalBriefCard.css';

interface GoalBriefCardProps {
  brief: GoalBrief;
  disabled: boolean;
  onAction: (message: string) => void | Promise<void>;
}

const SOURCE_LABELS: Record<GoalBriefFieldSource, string> = {
  USER_STATED: 'User stated',
  USER_CONFIRMED: 'User confirmed',
  SYSTEM_INFERRED: 'System inferred',
  UNKNOWN: 'Unknown',
  CONFLICTING: 'Conflicting',
};

interface GoalBriefFieldProps {
  label: string;
  source: GoalBriefFieldSource;
  children: React.ReactNode;
}

const GoalBriefField: React.FC<GoalBriefFieldProps> = ({ label, source, children }) => (
  <div className="goal-brief-card__field">
    <dt>
      {label}
      <span className={`goal-brief-card__source is-${source.toLowerCase()}`}>
        {SOURCE_LABELS[source]}
      </span>
    </dt>
    <dd>{children}</dd>
  </div>
);

const GoalBriefCard: React.FC<GoalBriefCardProps> = ({ brief, disabled, onAction }) => {
  const submittingRef = useRef(false);
  const [submitting, setSubmitting] = useState(false);

  const submit = async (message: string) => {
    if (disabled || submittingRef.current) return;
    submittingRef.current = true;
    setSubmitting(true);
    try {
      await onAction(message);
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  const actionDisabled = disabled || submitting;
  return (
    <section className="goal-brief-card" aria-label="Proposed goal brief">
      <header className="goal-brief-card__header">
        <div>
          <span className="goal-brief-card__eyebrow">Goal proposal</span>
          <h2>My understanding of the goal</h2>
        </div>
        <span className="goal-brief-card__status">
          {brief.proposalStatus === 'revised' ? 'Revised proposal' : 'Proposal'}
        </span>
      </header>
      <dl className="goal-brief-card__fields">
        <GoalBriefField label="Outcome" source={brief.fieldSources.outcome}>
          {brief.outcome}
        </GoalBriefField>
        <GoalBriefField label="Representative example" source={brief.fieldSources.representativeExample}>
          {brief.representativeExample}
        </GoalBriefField>
        <GoalBriefField label="Must not happen" source={brief.fieldSources.antiGoals}>
          {brief.antiGoals.length > 0 ? <ul>{brief.antiGoals.map((item, index) => <li key={`${index}:${item}`}>{item}</li>)}</ul> : 'None proposed'}
        </GoalBriefField>
        <GoalBriefField label="Ask before" source={brief.fieldSources.askBefore}>
          {brief.askBefore.length > 0 ? <ul>{brief.askBefore.map((item, index) => <li key={`${index}:${item}`}>{item}</li>)}</ul> : 'None proposed'}
        </GoalBriefField>
      </dl>
      <p className="goal-brief-card__quote">From your message: “{brief.sourceQuote}”</p>
      <footer className="goal-brief-card__actions">
        <button type="button" disabled={actionDisabled} onClick={() => void submit(GOAL_BRIEF_ACTION_MESSAGES.confirm)}>Confirm and continue</button>
        <button type="button" disabled={actionDisabled} onClick={() => void submit(GOAL_BRIEF_ACTION_MESSAGES.revise)}>Needs changes</button>
        <button type="button" disabled={actionDisabled} onClick={() => void submit(GOAL_BRIEF_ACTION_MESSAGES.once)}>Just this once</button>
      </footer>
      <p className="goal-brief-card__boundary">This proposal does not approve permissions, external code execution, or publishing.</p>
    </section>
  );
};

export default React.memo(GoalBriefCard);
