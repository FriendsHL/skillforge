import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChatSessionSetters } from '../useChatSession';
import { useChatSession } from '../useChatSession';

const getSessionMessagesMock = vi.fn();
const getSessionMock = vi.fn();
const messageErrorMock = vi.fn();

vi.mock('../../api', () => ({
  getSessionMessages: (...args: unknown[]) => getSessionMessagesMock(...args),
  getSession: (...args: unknown[]) => getSessionMock(...args),
  extractList: <T,>(response: { data: T[] }) => response.data,
}));

vi.mock('antd', () => ({
  message: { error: (...args: unknown[]) => messageErrorMock(...args) },
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function createSetters(): ChatSessionSetters {
  return {
    setRawMessages: vi.fn(),
    setRuntimeStatus: vi.fn(),
    setRuntimeStep: vi.fn(),
    setRuntimeError: vi.fn(),
    setExecutionMode: vi.fn(),
    setSelectedAgent: vi.fn(),
    setParentSessionId: vi.fn(),
    setSessionDepth: vi.fn(),
    setCollabRunId: vi.fn(),
    setCollabHandle: vi.fn(),
    setCollabLeaderSessionId: vi.fn(),
    setCollabRunStatus: vi.fn(),
    setLightCompactCount: vi.fn(),
    setFullCompactCount: vi.fn(),
    setTotalTokensReclaimed: vi.fn(),
  };
}

describe('useChatSession session ownership', () => {
  beforeEach(() => {
    getSessionMessagesMock.mockReset();
    getSessionMock.mockReset();
    messageErrorMock.mockReset();
  });

  it('ignores late Session A messages and details after Session B has loaded', async () => {
    const messagesA = deferred<{ data: Array<{ role: 'user'; content: string }> }>();
    const detailsA = deferred<{ data: Record<string, unknown> }>();
    const messagesB = deferred<{ data: Array<{ role: 'user'; content: string }> }>();
    const detailsB = deferred<{ data: Record<string, unknown> }>();
    getSessionMessagesMock.mockImplementation((sessionId: string) =>
      sessionId === 'session-a' ? messagesA.promise : messagesB.promise,
    );
    getSessionMock.mockImplementation((sessionId: string) =>
      sessionId === 'session-a' ? detailsA.promise : detailsB.promise,
    );
    const setters = createSetters();
    const { rerender } = renderHook(
      ({ sessionId }) => useChatSession(sessionId, setters),
      { initialProps: { sessionId: 'session-a' as string | undefined } },
    );

    rerender({ sessionId: 'session-b' });
    await act(async () => {
      messagesB.resolve({ data: [{ role: 'user', content: 'Message B' }] });
      detailsB.resolve({
        data: { runtimeStatus: 'running', runtimeStep: 'Session B detail', agentId: 22 },
      });
      await Promise.all([messagesB.promise, detailsB.promise]);
    });

    await waitFor(() => {
      expect(setters.setRawMessages).toHaveBeenCalledWith([
        expect.objectContaining({ content: 'Message B' }),
      ]);
      expect(setters.setRuntimeStep).toHaveBeenCalledWith('Session B detail');
      expect(setters.setSelectedAgent).toHaveBeenCalledWith(22);
    });

    await act(async () => {
      messagesA.resolve({ data: [{ role: 'user', content: 'Late message A' }] });
      detailsA.resolve({
        data: { runtimeStatus: 'error', runtimeStep: 'Late Session A detail', agentId: 11 },
      });
      await Promise.all([messagesA.promise, detailsA.promise]);
    });

    expect(setters.setRawMessages).toHaveBeenCalledTimes(1);
    expect(setters.setRuntimeStep).toHaveBeenCalledTimes(1);
    expect(setters.setRuntimeStep).not.toHaveBeenCalledWith('Late Session A detail');
    expect(setters.setSelectedAgent).not.toHaveBeenCalledWith(11);
  });

  it('suppresses obsolete Session A message and detail errors after switching to B', async () => {
    const messagesA = deferred<{ data: unknown[] }>();
    const detailsA = deferred<{ data: Record<string, unknown> }>();
    const messagesB = deferred<{ data: unknown[] }>();
    const detailsB = deferred<{ data: Record<string, unknown> }>();
    getSessionMessagesMock.mockImplementation((sessionId: string) =>
      sessionId === 'session-a' ? messagesA.promise : messagesB.promise,
    );
    getSessionMock.mockImplementation((sessionId: string) =>
      sessionId === 'session-a' ? detailsA.promise : detailsB.promise,
    );
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const setters = createSetters();
    const { rerender } = renderHook(
      ({ sessionId }) => useChatSession(sessionId, setters),
      { initialProps: { sessionId: 'session-a' as string | undefined } },
    );

    rerender({ sessionId: 'session-b' });
    await act(async () => {
      messagesA.reject(new Error('late messages failure'));
      detailsA.reject(new Error('late details failure'));
      await Promise.allSettled([messagesA.promise, detailsA.promise]);
    });

    expect(messageErrorMock).not.toHaveBeenCalled();
    expect(consoleErrorSpy).not.toHaveBeenCalled();
    expect(setters.setRawMessages).not.toHaveBeenCalled();
    expect(setters.setRuntimeStatus).not.toHaveBeenCalled();

    messagesB.resolve({ data: [] });
    detailsB.resolve({ data: {} });
    await Promise.all([messagesB.promise, detailsB.promise]);
    consoleErrorSpy.mockRestore();
  });

  it('preserves errors for the currently active session', async () => {
    getSessionMessagesMock.mockRejectedValueOnce(new Error('messages failure'));
    getSessionMock.mockRejectedValueOnce(new Error('details failure'));
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    renderHook(() => useChatSession('session-current', createSetters()));

    await waitFor(() => {
      expect(messageErrorMock).toHaveBeenCalledWith('Failed to load messages');
      expect(consoleErrorSpy).toHaveBeenCalledWith(
        'Failed to load session details',
        expect.any(Error),
      );
    });
    consoleErrorSpy.mockRestore();
  });
});
