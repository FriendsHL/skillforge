import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../client', () => {
  const get = vi.fn();
  return { default: { get } };
});

import api from '../client';
import { getSessionTasks, parseGoalBrief } from '../sessionTasks';

const mockedGet = (api as unknown as { get: ReturnType<typeof vi.fn> }).get;

describe('sessionTasks API', () => {
  beforeEach(() => {
    mockedGet.mockReset();
  });

  it('loads the full task snapshot for one owned session', async () => {
    mockedGet.mockResolvedValueOnce({
      data: {
        sessionId: 'session-1',
        summary: { total: 0, pending: 0, in_progress: 0, completed: 0, deleted: 0, blocked: 0 },
        tasks: [],
        generatedAt: '2026-08-05T12:00:00Z',
      },
    });

    await getSessionTasks('session-1', 42);

    expect(mockedGet).toHaveBeenCalledWith('/chat/sessions/session-1/tasks', {
      params: { userId: 42 },
    });
  });
});

describe('parseGoalBrief', () => {
  const valid = {
    kind: 'goal_brief',
    schemaVersion: 1,
    proposalStatus: 'proposed',
    outcome: 'Ship the complete feature',
    representativeExample: 'A verified production release',
    antiGoals: ['Do not publish without confirmation'],
    askBefore: ['External code execution'],
    fieldSources: {
      outcome: 'USER_STATED',
      representativeExample: 'SYSTEM_INFERRED',
      antiGoals: 'USER_CONFIRMED',
      askBefore: 'CONFLICTING',
    },
    sourceQuote: 'Take this requirement through release.',
  };

  it('parses the complete v1 shallow shape', () => {
    expect(parseGoalBrief(valid)).toEqual(valid);
  });

  it.each([
    [{ ...valid, outcome: undefined }],
    [{ ...valid, schemaVersion: 2 }],
    [{ ...valid, proposalStatus: 'approved' }],
    [{ ...valid, extra: 'not part of v1' }],
    [{ ...valid, outcome: 'x'.repeat(2001) }],
    [{ ...valid, antiGoals: Array.from({ length: 11 }, () => 'x') }],
    [{ ...valid, askBefore: ['x'.repeat(501)] }],
    [{ ...valid, fieldSources: { ...valid.fieldSources, outcome: 'TRUST_ME' } }],
    [{ ...valid, fieldSources: { ...valid.fieldSources, extra: 'USER_STATED' } }],
    [{
      ...valid,
      outcome: 'x'.repeat(2000),
      representativeExample: 'x'.repeat(2000),
      sourceQuote: 'x'.repeat(2000),
      antiGoals: ['x'.repeat(500), 'x'.repeat(500), 'x'.repeat(500), 'x'.repeat(500), 'x'],
    }],
    [Object.assign(Object.create({ kind: 'goal_brief' }), valid)],
    [Object.defineProperty({ ...valid }, 'outcome', { enumerable: true, get: () => 'unsafe' })],
  ])('rejects invalid or malicious metadata %#', (metadata) => {
    expect(parseGoalBrief(metadata)).toBeNull();
  });
});
