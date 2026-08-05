import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../client', () => {
  const get = vi.fn();
  return { default: { get } };
});

import api from '../client';
import { getSessionTasks } from '../sessionTasks';

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
