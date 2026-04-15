import { renderHook, act } from '@testing-library/react';
import { useCollabState } from '../useCollabState';

describe('useCollabState', () => {
  it('initializes all fields to null', () => {
    const { result } = renderHook(() => useCollabState(undefined));
    expect(result.current.collabRunId).toBeNull();
    expect(result.current.collabHandle).toBeNull();
    expect(result.current.collabLeaderSessionId).toBeNull();
    expect(result.current.collabRunStatus).toBeNull();
  });

  it('updates collabRunId when setter is called', () => {
    const { result } = renderHook(() => useCollabState('session-1'));
    act(() => { result.current.setCollabRunId('run-abc'); });
    expect(result.current.collabRunId).toBe('run-abc');
  });

  it('updates collabHandle when setter is called', () => {
    const { result } = renderHook(() => useCollabState('session-1'));
    act(() => { result.current.setCollabHandle('handle-xyz'); });
    expect(result.current.collabHandle).toBe('handle-xyz');
  });

  it('updates collabLeaderSessionId when setter is called', () => {
    const { result } = renderHook(() => useCollabState('session-1'));
    act(() => { result.current.setCollabLeaderSessionId('leader-session-1'); });
    expect(result.current.collabLeaderSessionId).toBe('leader-session-1');
  });

  it('updates collabRunStatus when setter is called', () => {
    const { result } = renderHook(() => useCollabState('session-1'));
    act(() => { result.current.setCollabRunStatus('running'); });
    expect(result.current.collabRunStatus).toBe('running');
  });

  it('resets all fields to null when activeSessionId changes', () => {
    const { result, rerender } = renderHook(
      ({ sessionId }: { sessionId: string | undefined }) => useCollabState(sessionId),
      { initialProps: { sessionId: 'session-1' as string | undefined } },
    );

    // Set some state
    act(() => {
      result.current.setCollabRunId('run-abc');
      result.current.setCollabHandle('handle-xyz');
      result.current.setCollabLeaderSessionId('leader-1');
      result.current.setCollabRunStatus('running');
    });
    expect(result.current.collabRunId).toBe('run-abc');

    // Change activeSessionId — should reset all to null
    rerender({ sessionId: 'session-2' });
    expect(result.current.collabRunId).toBeNull();
    expect(result.current.collabHandle).toBeNull();
    expect(result.current.collabLeaderSessionId).toBeNull();
    expect(result.current.collabRunStatus).toBeNull();
  });
});
