import api from './client';

export type SessionTaskStatus =
  | 'pending'
  | 'in_progress'
  | 'completed'
  | 'deleted';

export interface SessionTaskDto {
  taskId: string;
  subject: string;
  description: string;
  activeForm: string | null;
  status: SessionTaskStatus;
  owner: string | null;
  blocked: boolean;
  blockedBy: string[];
  blocks: string[];
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface SessionTaskSnapshot {
  sessionId: string;
  summary: SessionTaskSummary;
  tasks: SessionTaskDto[];
  generatedAt: string;
}

export interface SessionTaskSummary {
  total: number;
  pending: number;
  in_progress: number;
  completed: number;
  deleted: number;
  blocked: number;
}

export interface SessionTaskSnapshotEvent extends SessionTaskSnapshot {
  type: 'session_tasks_snapshot';
}

export const getSessionTasks = (sessionId: string, userId: number) =>
  api.get<SessionTaskSnapshot>(`/chat/sessions/${sessionId}/tasks`, {
    params: { userId },
  });

const TASK_STATUSES = new Set<SessionTaskStatus>([
  'pending',
  'in_progress',
  'completed',
  'deleted',
]);

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

function isSessionTaskSummary(value: unknown): value is SessionTaskSummary {
  if (!value || typeof value !== 'object') return false;
  const summary = value as Record<string, unknown>;
  return ['total', 'pending', 'in_progress', 'completed', 'deleted', 'blocked']
    .every((key) => typeof summary[key] === 'number' && Number.isInteger(summary[key]));
}

export function isSessionTaskDto(value: unknown): value is SessionTaskDto {
  if (!value || typeof value !== 'object') return false;
  const task = value as Record<string, unknown>;
  return (
    typeof task.taskId === 'string' &&
    typeof task.subject === 'string' &&
    typeof task.description === 'string' &&
    (task.activeForm === null || typeof task.activeForm === 'string') &&
    typeof task.status === 'string' &&
    TASK_STATUSES.has(task.status as SessionTaskStatus) &&
    (task.owner === null || typeof task.owner === 'string') &&
    typeof task.blocked === 'boolean' &&
    isStringArray(task.blockedBy) &&
    isStringArray(task.blocks) &&
    typeof task.createdAt === 'string' &&
    typeof task.updatedAt === 'string' &&
    typeof task.version === 'number' &&
    Number.isInteger(task.version) &&
    task.version >= 0
  );
}

export function parseSessionTaskSnapshot(value: unknown): SessionTaskSnapshot | null {
  if (!value || typeof value !== 'object') return null;
  const snapshot = value as Record<string, unknown>;
  if (
    typeof snapshot.sessionId !== 'string' ||
    typeof snapshot.generatedAt !== 'string' ||
    !isSessionTaskSummary(snapshot.summary) ||
    !Array.isArray(snapshot.tasks) ||
    !snapshot.tasks.every(isSessionTaskDto)
  ) {
    return null;
  }
  return {
    sessionId: snapshot.sessionId,
    summary: snapshot.summary,
    tasks: snapshot.tasks,
    generatedAt: snapshot.generatedAt,
  };
}
