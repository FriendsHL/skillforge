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
  metadata: unknown;
}

export type GoalBriefProposalStatus = 'proposed' | 'revised';
export type GoalBriefFieldSource =
  | 'USER_STATED'
  | 'USER_CONFIRMED'
  | 'SYSTEM_INFERRED'
  | 'UNKNOWN'
  | 'CONFLICTING';

export interface GoalBriefFieldSources {
  outcome: GoalBriefFieldSource;
  representativeExample: GoalBriefFieldSource;
  antiGoals: GoalBriefFieldSource;
  askBefore: GoalBriefFieldSource;
}

export interface GoalBrief {
  kind: 'goal_brief';
  schemaVersion: 1;
  proposalStatus: GoalBriefProposalStatus;
  outcome: string;
  representativeExample: string;
  antiGoals: string[];
  askBefore: string[];
  fieldSources: GoalBriefFieldSources;
  sourceQuote: string;
}

export const GOAL_BRIEF_ACTION_MESSAGES = {
  confirm: '我确认你对目标的理解；这不批准任何需要单独确认的权限、外部代码执行或发布操作，请按这个目标继续。',
  revise: '我需要修改你对目标的理解；这不批准任何高影响操作。请先询问我需要调整的内容。',
  once: '先只完成当前这一次，不要把它当作长期目标或创建常驻能力；这不批准任何需要单独确认的高影响操作。',
} as const;

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

const GOAL_BRIEF_KEYS = new Set([
  'kind', 'schemaVersion', 'proposalStatus', 'outcome', 'representativeExample',
  'antiGoals', 'askBefore', 'fieldSources', 'sourceQuote',
]);
const GOAL_BRIEF_FIELD_KEYS = [
  'outcome', 'representativeExample', 'antiGoals', 'askBefore',
] as const;
const GOAL_BRIEF_FIELD_SOURCES = new Set<GoalBriefFieldSource>([
  'USER_STATED', 'USER_CONFIRMED', 'SYSTEM_INFERRED', 'UNKNOWN', 'CONFLICTING',
]);
const MAX_GOAL_BRIEF_FIELD_LENGTH = 2_000;
const MAX_GOAL_BRIEF_ARRAY_ITEMS = 10;
const MAX_GOAL_BRIEF_ARRAY_ITEM_LENGTH = 500;
const MAX_GOAL_BRIEF_DISPLAY_LENGTH = 8_000;

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object') return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function hasOnlyDataProperties(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const descriptors = Object.getOwnPropertyDescriptors(value);
  return keys.every((key) => Object.prototype.hasOwnProperty.call(descriptors[key], 'value'));
}

function isBoundedString(value: unknown, maxLength: number): value is string {
  return typeof value === 'string' && value.length <= maxLength;
}

function parseGoalBriefItems(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > MAX_GOAL_BRIEF_ARRAY_ITEMS) return null;
  if (!value.every((item) => isBoundedString(item, MAX_GOAL_BRIEF_ARRAY_ITEM_LENGTH))) return null;
  return [...value];
}

function parseGoalBriefFieldSources(value: unknown): GoalBriefFieldSources | null {
  if (!isPlainRecord(value)) return null;
  const keys = Object.keys(value);
  if (keys.length !== GOAL_BRIEF_FIELD_KEYS.length) return null;
  if (!keys.every((key) =>
    GOAL_BRIEF_FIELD_KEYS.includes(key as typeof GOAL_BRIEF_FIELD_KEYS[number]))) return null;
  if (!hasOnlyDataProperties(value, keys)) return null;
  if (!GOAL_BRIEF_FIELD_KEYS.every((key) =>
    typeof value[key] === 'string' && GOAL_BRIEF_FIELD_SOURCES.has(value[key] as GoalBriefFieldSource))) return null;
  return {
    outcome: value.outcome as GoalBriefFieldSource,
    representativeExample: value.representativeExample as GoalBriefFieldSource,
    antiGoals: value.antiGoals as GoalBriefFieldSource,
    askBefore: value.askBefore as GoalBriefFieldSource,
  };
}

export function parseGoalBrief(metadata: unknown): GoalBrief | null {
  if (!isPlainRecord(metadata)) return null;
  const keys = Object.keys(metadata);
  if (keys.length !== GOAL_BRIEF_KEYS.size
    || !keys.every((key) => GOAL_BRIEF_KEYS.has(key))) return null;
  if (!hasOnlyDataProperties(metadata, keys)) return null;
  if (metadata.kind !== 'goal_brief' || metadata.schemaVersion !== 1) return null;
  if (metadata.proposalStatus !== 'proposed' && metadata.proposalStatus !== 'revised') return null;
  if (!isBoundedString(metadata.outcome, MAX_GOAL_BRIEF_FIELD_LENGTH)
    || !isBoundedString(metadata.representativeExample, MAX_GOAL_BRIEF_FIELD_LENGTH)
    || !isBoundedString(metadata.sourceQuote, MAX_GOAL_BRIEF_FIELD_LENGTH)) return null;
  const antiGoals = parseGoalBriefItems(metadata.antiGoals);
  const askBefore = parseGoalBriefItems(metadata.askBefore);
  const fieldSources = parseGoalBriefFieldSources(metadata.fieldSources);
  if (!antiGoals || !askBefore || !fieldSources) return null;
  const displayLength = metadata.outcome.length + metadata.representativeExample.length
    + metadata.sourceQuote.length + [...antiGoals, ...askBefore]
      .reduce((total, item) => total + item.length, 0);
  if (displayLength > MAX_GOAL_BRIEF_DISPLAY_LENGTH) return null;
  return {
    kind: 'goal_brief', schemaVersion: 1, proposalStatus: metadata.proposalStatus,
    outcome: metadata.outcome, representativeExample: metadata.representativeExample,
    antiGoals, askBefore, fieldSources, sourceQuote: metadata.sourceQuote,
  };
}

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
    task.version >= 0 &&
    Object.prototype.hasOwnProperty.call(task, 'metadata')
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
