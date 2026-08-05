import React, { useMemo, useState } from 'react';
import type { ToolCall } from '../ToolCallTimeline';
import { IconCheck, IconTool, IconX } from './ChatIcons';
import './TaskToolCallCard.css';

interface TaskToolCallCardProps {
  toolCall: ToolCall;
}

interface TaskResultSummary {
  taskId?: string;
  subject?: string;
  status?: string;
  activeForm?: string | null;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function parseTaskResult(output: string | undefined): TaskResultSummary | null {
  if (!output) return null;
  try {
    const parsed = asRecord(JSON.parse(output));
    if (!parsed) return null;
    const task = asRecord(parsed.task) ?? parsed;
    return {
      taskId: typeof task.taskId === 'string' ? task.taskId : undefined,
      subject: typeof task.subject === 'string' ? task.subject : undefined,
      status: typeof task.status === 'string' ? task.status : undefined,
      activeForm:
        task.activeForm === null || typeof task.activeForm === 'string'
          ? task.activeForm
          : undefined,
    };
  } catch {
    return null;
  }
}

function statusLabel(value: string): string {
  return value.replaceAll('_', ' ');
}

const TaskToolCallCard: React.FC<TaskToolCallCardProps> = ({ toolCall }) => {
  const [expanded, setExpanded] = useState(false);
  const input = asRecord(toolCall.input) ?? {};
  const result = useMemo(() => parseTaskResult(toolCall.output), [toolCall.output]);
  const isCreate = toolCall.name === 'TaskCreate';
  const failed = toolCall.status === 'error';
  const subject = result?.subject ?? (
    typeof input.subject === 'string' ? input.subject : undefined
  );
  const activeForm = result?.activeForm ?? (
    typeof input.activeForm === 'string'
      ? input.activeForm
      : typeof input.active_form === 'string'
        ? input.active_form
        : undefined
  );
  const changedStatus = !isCreate && typeof input.status === 'string'
    ? statusLabel(input.status)
    : undefined;
  const inputText = typeof toolCall.input === 'string'
    ? toolCall.input
    : JSON.stringify(toolCall.input ?? {});

  return (
    <div className={`task-tool-card${failed ? ' task-tool-card--error' : ''}`}>
      <button
        type="button"
        className="tool-row task-tool-card__summary"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >
        <span className="tool-icon"><IconTool s={11} /></span>
        <span className="task-tool-card__copy">
          <span className="task-tool-card__label">
            {failed
              ? (isCreate ? 'Task creation failed' : 'Task update failed')
              : (isCreate ? 'Task created' : 'Task updated')}
          </span>
          {subject && <strong>{subject}</strong>}
          <span className="task-tool-card__change">
            {changedStatus ? `status → ${changedStatus}` : activeForm ?? 'details updated'}
          </span>
        </span>
        {activeForm && changedStatus && (
          <span className="task-tool-card__active-form">{activeForm}</span>
        )}
        {failed
          ? <span className="tool-err"><IconX s={12} /></span>
          : <span className="tool-check"><IconCheck s={12} /></span>}
      </button>
      {expanded && (
        <div className="tool-expanded">
          <div className="tool-section">
            <div className="tool-section-label">input</div>
            <pre>{inputText}</pre>
          </div>
          {toolCall.output && (
            <div className="tool-section">
              <div className="tool-section-label">output</div>
              <pre>{toolCall.output}</pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default TaskToolCallCard;
