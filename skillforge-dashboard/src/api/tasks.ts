import api from './index';

/**
 * Unified task-run row from BE TasksController. Most fields are nullable
 * because different subsystems track different lifecycle metadata.
 */
export interface TaskRunItem {
  /** Composite id: `"<source>:<native_id>"` — unique across subsystems. */
  runId: string;
  /** One of: scheduled_task / subagent / skill_evolution / skill_ab / prompt_ab / collab. */
  source: TaskRunSource;
  /** Human-readable task / agent / skill identifier. */
  name: string;
  /** Subsystem-specific status string. */
  status: string;
  /** ISO-8601 of when this row was created / triggered. Used for sort. */
  triggeredAt: string | null;
  /** ISO-8601 of completion; null while running. */
  finishedAt: string | null;
  /** Linked session for drill-down; null when not applicable. */
  sessionId: string | null;
  /** Brief description / summary for the row. */
  detail: string | null;
  /** Truncated failure reason; null on success. */
  errorMessage: string | null;
}

export type TaskRunSource =
  | 'scheduled_task'
  | 'subagent'
  | 'skill_evolution'
  | 'skill_ab'
  | 'prompt_ab'
  | 'collab';

export const listTaskRuns = (params: { source?: TaskRunSource; limit?: number }) =>
  api.get<TaskRunItem[]>('/tasks/runs', { params });
