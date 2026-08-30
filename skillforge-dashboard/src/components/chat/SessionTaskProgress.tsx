import React, { useMemo, useState } from 'react';
import {
  parseGoalBrief,
  type GoalBrief,
  type SessionTaskDto,
  type SessionTaskStatus,
} from '../../api/sessionTasks';
import GoalBriefCard from './GoalBriefCard';
import './SessionTaskProgress.css';

interface SessionTaskProgressProps {
  tasks: SessionTaskDto[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  onGoalBriefAction?: (message: string) => void | Promise<void>;
  goalBriefActionDisabled?: boolean;
}

const STATUS_LABELS: Record<SessionTaskStatus, string> = {
  pending: 'Pending',
  in_progress: 'In progress',
  completed: 'Completed',
  deleted: 'Deleted',
};

const SessionTaskProgress: React.FC<SessionTaskProgressProps> = ({
  tasks,
  loading,
  error,
  onRetry,
  onGoalBriefAction = () => undefined,
  goalBriefActionDisabled = false,
}) => {
  const [expanded, setExpanded] = useState(false);
  const parsedTasks = useMemo(
    () => tasks.map((task) => ({ task, goalBrief: parseGoalBrief(task.metadata) })),
    [tasks],
  );
  const latestGoalBrief = useMemo(() => parsedTasks
    .filter((entry): entry is { task: SessionTaskDto; goalBrief: GoalBrief } =>
      entry.task.status !== 'deleted' && entry.goalBrief !== null)
    .sort((left, right) => left.task.createdAt.localeCompare(right.task.createdAt)
      || left.task.taskId.localeCompare(right.task.taskId))
    .at(-1)?.goalBrief ?? null, [parsedTasks]);
  const visibleTasks = useMemo(
    () => parsedTasks
      .filter(({ task, goalBrief }) => task.status !== 'deleted' && goalBrief === null)
      .map(({ task }) => task),
    [parsedTasks],
  );
  const taskById = useMemo(
    () => new Map(visibleTasks.map((task) => [task.taskId, task])),
    [visibleTasks],
  );
  const completedCount = visibleTasks.filter((task) => task.status === 'completed').length;
  const blockedCount = visibleTasks.filter((task) => task.blocked).length;
  const currentTask = visibleTasks.find((task) => task.status === 'in_progress');
  const currentLabel = currentTask?.activeForm?.trim() || currentTask?.subject;
  const completionPct = visibleTasks.length === 0
    ? 0
    : Math.round((completedCount / visibleTasks.length) * 100);

  if (loading && visibleTasks.length === 0 && !latestGoalBrief) {
    return (
      <section
        className="session-task-progress session-task-progress--loading"
        role="status"
        aria-label="Loading session tasks"
      >
        <span className="session-task-progress__pulse" aria-hidden="true" />
        Loading task progress…
      </section>
    );
  }

  if (error && visibleTasks.length === 0 && !latestGoalBrief) {
    return (
      <section className="session-task-progress session-task-progress--error" role="status">
        <span>{error}</span>
        <button type="button" aria-label="Retry task progress" onClick={onRetry}>
          Retry
        </button>
      </section>
    );
  }

  if (visibleTasks.length === 0 && !latestGoalBrief) return null;

  return (
    <>
      {latestGoalBrief && (
        <GoalBriefCard
          brief={latestGoalBrief}
          disabled={goalBriefActionDisabled}
          onAction={onGoalBriefAction}
        />
      )}
      {visibleTasks.length > 0 && (
        <section className="session-task-progress" aria-label="Session task progress">
          <button
            type="button"
            className="session-task-progress__summary"
            aria-expanded={expanded}
            aria-controls="session-task-progress-list"
            aria-label={expanded ? 'Hide session tasks' : 'Show session tasks'}
            onClick={() => setExpanded((value) => !value)}
          >
            <span className="session-task-progress__meter" aria-hidden="true">
              <span style={{ transform: `scaleY(${completionPct / 100})` }} />
            </span>
            <span className="session-task-progress__count">
              {completedCount} / {visibleTasks.length} completed
            </span>
            <span className="session-task-progress__current">
              {currentLabel ?? (completedCount === visibleTasks.length
                ? 'All tasks complete' : 'Waiting to start')}
            </span>
            {blockedCount > 0 && (
              <span className="session-task-progress__blocked">{blockedCount} blocked</span>
            )}
            <span className="session-task-progress__chevron" aria-hidden="true">
              {expanded ? '▴' : '▾'}
            </span>
          </button>
          <div
            className="session-task-progress__bar"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={visibleTasks.length}
            aria-valuenow={completedCount}
            aria-label="Session task completion"
          />
          {expanded && (
            <ol id="session-task-progress-list" className="session-task-progress__list">
              {visibleTasks.map((task) => {
                const blockedBy = task.blockedBy
                  .map((taskId) => taskById.get(taskId)?.subject ?? taskId)
                  .join(', ');
                return (
                  <li key={task.taskId} className={`session-task-progress__task is-${task.status}`}>
                    <span className="session-task-progress__status-dot" aria-hidden="true" />
                    <span className="session-task-progress__task-copy">
                      <strong>{task.subject}</strong>
                      {task.activeForm && task.status === 'in_progress' && (
                        <span>{task.activeForm}</span>
                      )}
                      {task.blocked && blockedBy && <span>Blocked by {blockedBy}</span>}
                    </span>
                    <span className="session-task-progress__status">
                      {task.blocked ? 'Blocked' : STATUS_LABELS[task.status]}
                    </span>
                  </li>
                );
              })}
            </ol>
          )}
        </section>
      )}
    </>
  );
};

export default React.memo(SessionTaskProgress);
