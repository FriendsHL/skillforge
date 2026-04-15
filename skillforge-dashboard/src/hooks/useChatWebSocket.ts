import { useEffect, useRef } from 'react';

export function useChatWebSocket(
  activeSessionId: string | undefined,
  onEvent: (evt: unknown) => void,
) {
  const onEventRef = useRef(onEvent);
  useEffect(() => {
    onEventRef.current = onEvent;
  }, [onEvent]);

  const wsRef = useRef<WebSocket | null>(null);
  const wsReconnectTimerRef = useRef<number | null>(null);
  const wsReconnectDelayRef = useRef(2000);

  useEffect(() => {
    if (!activeSessionId) return;

    if (wsReconnectTimerRef.current != null) {
      clearTimeout(wsReconnectTimerRef.current);
      wsReconnectTimerRef.current = null;
    }
    wsReconnectDelayRef.current = 2000;

    const sessionId = activeSessionId;

    const connectWs = () => {
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const token = localStorage.getItem('sf_token') ?? '';
      const ws = new WebSocket(`${proto}://${window.location.host}/ws/chat/${sessionId}?token=${encodeURIComponent(token)}`);
      wsRef.current = ws;

      ws.onopen = () => {
        wsReconnectDelayRef.current = 2000;
      };
      ws.onmessage = (ev) => {
        try {
          const evt = JSON.parse(ev.data);
          onEventRef.current(evt);
        } catch {
          console.warn('Bad WS payload', ev.data);
        }
      };
      ws.onclose = () => {
        if (wsRef.current !== ws) {
          return;
        }
        wsRef.current = null;
        const delay = wsReconnectDelayRef.current;
        wsReconnectDelayRef.current = Math.min(delay * 2, 30000);
        wsReconnectTimerRef.current = window.setTimeout(() => {
          wsReconnectTimerRef.current = null;
          if (wsRef.current === null) connectWs();
        }, delay);
      };
    };

    connectWs();

    return () => {
      if (wsReconnectTimerRef.current != null) {
        clearTimeout(wsReconnectTimerRef.current);
        wsReconnectTimerRef.current = null;
        wsReconnectDelayRef.current = 2000;
      }
      const ws = wsRef.current;
      wsRef.current = null;
      try {
        if (ws) ws.close();
      } catch {
        // ignore
      }
    };
  }, [activeSessionId]);
}
