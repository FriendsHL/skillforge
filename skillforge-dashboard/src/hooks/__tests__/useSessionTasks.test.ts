import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionTaskDto, SessionTaskSnapshot } from '../../api/sessionTasks';
import { useSessionTasks } from '../useSessionTasks';

const getSessionTasksMock = vi.fn();

vi.mock('../../api/sessionTasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/sessionTasks')>();
  return {
    ...actual,
    getSessionTasks: (...args: unknown[]) => getSessionTasksMock(...args),
  };
});

let rafId = 0;
let rafCallbacks = new Map<number, FrameRequestCallback>();

function flushAnimationFrames(): void {
  const callbacks = [...rafCallbacks.values()];
  rafCallbacks.clear();
  callbacks.forEach((callback) => callback(performance.now()));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function task(overrides: Partial<SessionTaskDto> = {}): SessionTaskDto {
  return {
    taskId: 'task-1',
    subject: 'Prepare digest',
    description: 'Prepare and verify the daily digest.',
    activeForm: 'Preparing digest',
    status: 'in_progress',
    owner: null,
    blocked: false,
    blockedBy: [],
    blocks: [],
    createdAt: '2026-08-05T10:00:00Z',
    updatedAt: '2026-08-05T10:01:00Z',
    version: 1,
    ...overrides,
  };
}

function snapshot(sessionId: string, tasks: SessionTaskDto[]): SessionTaskSnapshot {
  return {
    sessionId,
    summary: {
      total: tasks.length,
      pending: tasks.filter((task) => task.status === 'pending').length,
      in_progress: tasks.filter((task) => task.status === 'in_progress').length,
      completed: tasks.filter((task) => task.status === 'completed').length,
      deleted: tasks.filter((task) => task.status === 'deleted').length,
      blocked: tasks.filter((task) => task.blocked).length,
    },
    tasks,
    generatedAt: '2026-08-05T10:02:00Z',
  };
}

describe('useSessionTasks', () => {
  beforeEach(() => {
    getSessionTasksMock.mockReset();
    rafId = 0;
    rafCallbacks = new Map();
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      const id = ++rafId;
      rafCallbacks.set(id, callback);
      return id;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => {
      rafCallbacks.delete(id);
    });
  });

  it('loads the REST snapshot without exposing another session', async () => {
    getSessionTasksMock.mockResolvedValueOnce({ data: snapshot('session-a', [task()]) });

    const { result } = renderHook(() => useSessionTasks('session-a', 1));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.tasks).toEqual([expect.objectContaining({ taskId: 'task-1' })]);
    expect(result.current.error).toBeNull();
  });

  it('upserts by taskId and newer version without deleting omitted or deleted tasks', async () => {
    const retained = task({ taskId: 'task-2', subject: 'Retained task', version: 2 });
    const deleted = task({ taskId: 'task-3', subject: 'Deleted history', status: 'deleted', version: 4 });
    getSessionTasksMock.mockResolvedValueOnce({
      data: snapshot('session-a', [task({ version: 2 }), retained, deleted]),
    });
    const { result } = renderHook(() => useSessionTasks('session-a', 1));
    await waitFor(() => expect(result.current.tasks).toHaveLength(3));

    act(() => {
      result.current.handleWsEvent({
        type: 'session_tasks_snapshot',
        ...snapshot('session-a', [
          task({ subject: 'Stale title', version: 1 }),
        ]),
      });
      flushAnimationFrames();
    });

    expect(result.current.tasks).toEqual(expect.arrayContaining([
      expect.objectContaining({ taskId: 'task-1', subject: 'Prepare digest', version: 2 }),
      expect.objectContaining({ taskId: 'task-2' }),
      expect.objectContaining({ taskId: 'task-3', status: 'deleted' }),
    ]));

    act(() => {
      result.current.handleWsEvent({
        type: 'session_tasks_snapshot',
        ...snapshot('session-a', [
          task({ subject: 'Verified digest', status: 'completed', version: 3 }),
        ]),
      });
      flushAnimationFrames();
    });

    expect(result.current.tasks).toEqual(expect.arrayContaining([
      expect.objectContaining({ taskId: 'task-1', subject: 'Verified digest', version: 3 }),
      expect.objectContaining({ taskId: 'task-2' }),
      expect.objectContaining({ taskId: 'task-3' }),
    ]));
  });

  it('accepts a relation-only update with the same version and a newer updatedAt', async () => {
    getSessionTasksMock.mockResolvedValueOnce({
      data: snapshot('session-a', [task({ blocked: true, blockedBy: ['task-0'], version: 3 })]),
    });
    const { result } = renderHook(() => useSessionTasks('session-a', 1));
    await waitFor(() => expect(result.current.tasks).toHaveLength(1));

    act(() => {
      result.current.handleWsEvent({
        type: 'session_tasks_snapshot',
        ...snapshot('session-a', [task({
          blocked: false,
          blockedBy: [],
          version: 3,
          updatedAt: '2026-08-05T10:03:00Z',
        })]),
      });
      flushAnimationFrames();
    });

    expect(result.current.tasks[0]).toEqual(expect.objectContaining({
      blocked: false,
      blockedBy: [],
      version: 3,
      updatedAt: '2026-08-05T10:03:00Z',
    }));
  });

  it('ignores late REST and WS data from the previous session', async () => {
    const responseA = deferred<{ data: SessionTaskSnapshot }>();
    const responseB = deferred<{ data: SessionTaskSnapshot }>();
    getSessionTasksMock.mockImplementation((sessionId: string) =>
      sessionId === 'session-a' ? responseA.promise : responseB.promise,
    );
    const { result, rerender } = renderHook(
      ({ sessionId }) => useSessionTasks(sessionId, 1),
      { initialProps: { sessionId: 'session-a' as string | undefined } },
    );

    rerender({ sessionId: 'session-b' });
    await act(async () => {
      responseB.resolve({ data: snapshot('session-b', [task({ taskId: 'task-b', subject: 'B task' })]) });
      await responseB.promise;
    });
    await waitFor(() => expect(result.current.tasks[0]?.taskId).toBe('task-b'));

    await act(async () => {
      responseA.resolve({ data: snapshot('session-a', [task({ taskId: 'task-a', subject: 'Late A' })]) });
      await responseA.promise;
      result.current.handleWsEvent({
        type: 'session_tasks_snapshot',
        ...snapshot('session-a', [task({ taskId: 'task-a-ws', subject: 'Late A WS' })]),
      });
      flushAnimationFrames();
    });

    expect(result.current.tasks.map((item) => item.taskId)).toEqual(['task-b']);
  });

  it('keeps task failure local and retries without throwing', async () => {
    getSessionTasksMock
      .mockRejectedValueOnce(new Error('task endpoint unavailable'))
      .mockResolvedValueOnce({ data: snapshot('session-a', [task()]) });
    const { result } = renderHook(() => useSessionTasks('session-a', 1));

    await waitFor(() => expect(result.current.error).toBe('Task progress unavailable'));
    expect(result.current.loading).toBe(false);
    expect(result.current.tasks).toEqual([]);

    await act(async () => {
      await result.current.retry();
    });
    await waitFor(() => expect(result.current.tasks).toHaveLength(1));
    expect(result.current.error).toBeNull();
  });

  it('batches multiple WS snapshots into one animation-frame state update', async () => {
    getSessionTasksMock.mockResolvedValueOnce({ data: snapshot('session-a', []) });
    const { result } = renderHook(() => useSessionTasks('session-a', 1));
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => {
      result.current.handleWsEvent({
        type: 'session_tasks_snapshot',
        ...snapshot('session-a', [task({ taskId: 'task-1', version: 1 })]),
      });
      result.current.handleWsEvent({
        type: 'session_tasks_snapshot',
        ...snapshot('session-a', [task({ taskId: 'task-1', status: 'completed', version: 2 })]),
      });
      result.current.handleWsEvent({
        type: 'session_tasks_snapshot',
        ...snapshot('session-a', [task({ taskId: 'task-2', subject: 'Second task', version: 1 })]),
      });
    });

    expect(rafCallbacks).toHaveLength(1);
    act(() => flushAnimationFrames());
    expect(result.current.tasks).toEqual(expect.arrayContaining([
      expect.objectContaining({ taskId: 'task-1', status: 'completed', version: 2 }),
      expect.objectContaining({ taskId: 'task-2' }),
    ]));
  });

  it('keeps a newer WS task when an older REST snapshot resolves afterward', async () => {
    const response = deferred<{ data: SessionTaskSnapshot }>();
    getSessionTasksMock.mockReturnValueOnce(response.promise);
    const { result } = renderHook(() => useSessionTasks('session-a', 1));

    act(() => {
      result.current.handleWsEvent({
        type: 'session_tasks_snapshot',
        ...snapshot('session-a', [
          task({ subject: 'Live update', status: 'completed', version: 5 }),
        ]),
      });
      flushAnimationFrames();
    });
    expect(result.current.tasks[0]).toEqual(expect.objectContaining({
      subject: 'Live update',
      version: 5,
    }));

    await act(async () => {
      response.resolve({
        data: snapshot('session-a', [
          task({ subject: 'Stale REST value', status: 'pending', version: 3 }),
        ]),
      });
      await response.promise;
    });

    expect(result.current.tasks[0]).toEqual(expect.objectContaining({
      subject: 'Live update',
      status: 'completed',
      version: 5,
    }));
  });
});
