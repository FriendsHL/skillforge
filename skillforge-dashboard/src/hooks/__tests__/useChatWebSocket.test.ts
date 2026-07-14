import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useChatWebSocket } from '../useChatWebSocket';

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];

  readonly url: string;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onclose: (() => void) | null = null;
  close = vi.fn();

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  emit(payload: unknown) {
    this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent<string>);
  }
}

describe('useChatWebSocket — session ownership', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => null),
    });
    vi.stubGlobal('WebSocket', FakeWebSocket);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('ignores queued events from the socket replaced by a session switch', () => {
    const onEvent = vi.fn();
    const { rerender } = renderHook(
      ({ sessionId }: { sessionId: string }) => {
        useChatWebSocket(sessionId, onEvent);
      },
      { initialProps: { sessionId: 'session-a' } },
    );

    const sessionASocket = FakeWebSocket.instances[0];
    expect(sessionASocket.url).toContain('/ws/chat/session-a');

    rerender({ sessionId: 'session-b' });

    const sessionBSocket = FakeWebSocket.instances[1];
    expect(sessionASocket.close).toHaveBeenCalledOnce();
    expect(sessionBSocket.url).toContain('/ws/chat/session-b');

    act(() => {
      sessionASocket.emit({ type: 'text_delta', sessionId: 'session-a', delta: 'stale' });
      sessionBSocket.emit({ type: 'text_delta', sessionId: 'session-b', delta: 'current' });
    });

    expect(onEvent).toHaveBeenCalledTimes(1);
    expect(onEvent).toHaveBeenCalledWith(
      expect.objectContaining({ sessionId: 'session-b', delta: 'current' }),
    );
  });
});
