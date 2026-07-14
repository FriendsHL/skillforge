/**
 * CHAT-REASONING-PANEL — wire-level coverage for the WS event handler:
 *   1. `reasoning_delta` accumulates into setStreamingReasoningText
 *   2. First `text_delta` after `reasoning_delta` sets reasoningDurationMs
 *      to a non-null value and clears the internal ref
 *   3. `session_status` idle clears streamingReasoningText + duration
 *   4. `message_appended` (assistant) clears streamingReasoningText + duration
 *
 * The hook is wrapped via renderHook; setters are vi.fn() stubs so we can
 * assert which ones fired with which arguments. RuntimeStatus / RawMessage
 * setters are no-ops for these tests.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useChatWsEventHandler, type WsEventHandlerDeps } from '../useChatWsEventHandler';

function makeDeps(overrides: Partial<WsEventHandlerDeps> = {}): WsEventHandlerDeps {
  return {
    activeSessionId: 's1',
    setRuntimeStatus: vi.fn(),
    setRuntimeStep: vi.fn(),
    setRuntimeError: vi.fn(),
    setPendingAsk: vi.fn(),
    setPendingConfirm: vi.fn(),
    setInflightTools: vi.fn(),
    setStreamingText: vi.fn(),
    setStreamingToolInputs: vi.fn(),
    setCancelling: vi.fn(),
    setRawMessages: vi.fn(),
    setOtherInput: vi.fn(),
    setCollabRunStatus: vi.fn(),
    setSessions: vi.fn(),
    setCompactionNotice: vi.fn(),
    setLoopSpans: vi.fn(),
    setStreamingReasoningText: vi.fn(),
    setReasoningDurationMs: vi.fn(),
    llmModelName: 'test-model',
    ...overrides,
  };
}

describe('useChatWsEventHandler — CHAT-REASONING-PANEL', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('reasoning_delta accumulates text via setStreamingReasoningText', () => {
    const setStreamingReasoningText = vi.fn();
    const deps = makeDeps({ setStreamingReasoningText });
    const { result } = renderHook(() => useChatWsEventHandler(deps));

    act(() => {
      result.current({ type: 'reasoning_delta', sessionId: 's1', delta: 'Step 1 ' });
      result.current({ type: 'reasoning_delta', sessionId: 's1', delta: 'and step 2' });
    });

    expect(setStreamingReasoningText).toHaveBeenCalledTimes(2);
    // Each call passes a setState updater fn — verify they concatenate.
    const first = setStreamingReasoningText.mock.calls[0][0] as (prev: string) => string;
    expect(first('')).toBe('Step 1 ');
    const second = setStreamingReasoningText.mock.calls[1][0] as (prev: string) => string;
    expect(second('Step 1 ')).toBe('Step 1 and step 2');
  });

  it('reasoning_delta with empty delta is a no-op (no setState call)', () => {
    const setStreamingReasoningText = vi.fn();
    const deps = makeDeps({ setStreamingReasoningText });
    const { result } = renderHook(() => useChatWsEventHandler(deps));

    act(() => {
      result.current({ type: 'reasoning_delta', sessionId: 's1', delta: '' });
      // Missing `delta` field also treated as empty.
      result.current({ type: 'reasoning_delta', sessionId: 's1' });
    });

    expect(setStreamingReasoningText).not.toHaveBeenCalled();
  });

  it('text_delta after reasoning_delta sets reasoningDurationMs to a non-null number', () => {
    const setReasoningDurationMs = vi.fn();
    const setStreamingText = vi.fn();
    const deps = makeDeps({ setReasoningDurationMs, setStreamingText });
    const { result } = renderHook(() => useChatWsEventHandler(deps));

    act(() => {
      // First reasoning_delta arms the timer ref.
      result.current({ type: 'reasoning_delta', sessionId: 's1', delta: 'thinking…' });
    });
    // No duration set yet — text_delta hasn't fired.
    expect(setReasoningDurationMs).not.toHaveBeenCalled();

    act(() => {
      result.current({ type: 'text_delta', sessionId: 's1', delta: 'Hello' });
    });

    expect(setReasoningDurationMs).toHaveBeenCalledTimes(1);
    const durArg = setReasoningDurationMs.mock.calls[0][0] as number;
    expect(typeof durArg).toBe('number');
    expect(durArg).toBeGreaterThanOrEqual(0);
    expect(setStreamingText).toHaveBeenCalledTimes(1);

    // Second text_delta should NOT re-trigger duration (timer ref was cleared).
    setReasoningDurationMs.mockClear();
    act(() => {
      result.current({ type: 'text_delta', sessionId: 's1', delta: ' world' });
    });
    expect(setReasoningDurationMs).not.toHaveBeenCalled();
  });

  it('text_delta without prior reasoning_delta does NOT set reasoningDurationMs', () => {
    const setReasoningDurationMs = vi.fn();
    const deps = makeDeps({ setReasoningDurationMs });
    const { result } = renderHook(() => useChatWsEventHandler(deps));

    act(() => {
      result.current({ type: 'text_delta', sessionId: 's1', delta: 'Hello' });
    });

    // No reasoning phase happened — durationMs should stay untouched.
    expect(setReasoningDurationMs).not.toHaveBeenCalled();
  });

  it('session_status=idle clears streamingReasoningText + reasoningDurationMs', () => {
    const setStreamingReasoningText = vi.fn();
    const setReasoningDurationMs = vi.fn();
    const deps = makeDeps({ setStreamingReasoningText, setReasoningDurationMs });
    const { result } = renderHook(() => useChatWsEventHandler(deps));

    act(() => {
      result.current({ type: 'session_status', sessionId: 's1', status: 'idle', step: '' });
    });

    expect(setStreamingReasoningText).toHaveBeenCalledWith('');
    expect(setReasoningDurationMs).toHaveBeenCalledWith(null);
  });

  it('session_status=error also clears reasoning state', () => {
    const setStreamingReasoningText = vi.fn();
    const setReasoningDurationMs = vi.fn();
    const deps = makeDeps({ setStreamingReasoningText, setReasoningDurationMs });
    const { result } = renderHook(() => useChatWsEventHandler(deps));

    act(() => {
      result.current({
        type: 'session_status',
        sessionId: 's1',
        status: 'error',
        step: '',
        error: 'boom',
      });
    });

    expect(setStreamingReasoningText).toHaveBeenCalledWith('');
    expect(setReasoningDurationMs).toHaveBeenCalledWith(null);
  });

  it('message_appended for an assistant message clears streamingReasoningText + duration', () => {
    const setStreamingReasoningText = vi.fn();
    const setReasoningDurationMs = vi.fn();
    const deps = makeDeps({ setStreamingReasoningText, setReasoningDurationMs });
    const { result } = renderHook(() => useChatWsEventHandler(deps));

    act(() => {
      result.current({
        type: 'message_appended',
        sessionId: 's1',
        message: { role: 'assistant', content: 'final reply' },
      });
    });

    expect(setStreamingReasoningText).toHaveBeenCalledWith('');
    expect(setReasoningDurationMs).toHaveBeenCalledWith(null);
  });

  it('message_appended for a user message does NOT clear reasoning state', () => {
    const setStreamingReasoningText = vi.fn();
    const setReasoningDurationMs = vi.fn();
    const deps = makeDeps({ setStreamingReasoningText, setReasoningDurationMs });
    const { result } = renderHook(() => useChatWsEventHandler(deps));

    act(() => {
      result.current({
        type: 'message_appended',
        sessionId: 's1',
        message: { role: 'user', content: 'user message' },
      });
    });

    // User messages don't end an assistant turn — leave reasoning state alone.
    expect(setStreamingReasoningText).not.toHaveBeenCalled();
    expect(setReasoningDurationMs).not.toHaveBeenCalled();
  });

  it('resets snapshot ordering when the active session changes', () => {
    const setRawMessages = vi.fn();
    const baseDeps = makeDeps({ setRawMessages });
    const { result, rerender } = renderHook(
      ({ sessionId }: { sessionId: string }) =>
        useChatWsEventHandler({ ...baseDeps, activeSessionId: sessionId }),
      { initialProps: { sessionId: 'session-a' } },
    );

    act(() => {
      result.current({
        type: 'messages_snapshot',
        sessionId: 'session-a',
        snapshotVersion: 50,
        messages: [],
      });
    });
    setRawMessages.mockClear();

    rerender({ sessionId: 'session-b' });
    act(() => {
      result.current({
        type: 'message_appended',
        sessionId: 'session-b',
        snapshotVersion: 1,
        message: { role: 'assistant', content: 'session-b reply' },
      });
    });

    expect(setRawMessages).toHaveBeenCalledOnce();
  });

  it('ignores an event explicitly owned by another session', () => {
    const setStreamingText = vi.fn();
    const deps = makeDeps({ activeSessionId: 'session-b', setStreamingText });
    const { result } = renderHook(() => useChatWsEventHandler(deps));

    act(() => {
      result.current({
        type: 'text_delta',
        sessionId: 'session-a',
        delta: 'stale session text',
      });
    });

    expect(setStreamingText).not.toHaveBeenCalled();
  });
});
