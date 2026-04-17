import React, { useEffect, useRef } from 'react';
import { Table, Tooltip, message, Card } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getSessions, extractList } from '../api';
import { SessionSchema, safeParseList } from '../api/schemas';
import { useAuth } from '../contexts/AuthContext';

type SessionRow = {
  id: string;
  title?: string;
  agentId?: number | string;
  agentName?: string;
  messageCount?: number;
  totalTokens?: number;
  runtimeStatus?: string;
  runtimeStep?: string;
  runtimeError?: string;
  createdAt?: string;
  updatedAt?: string;
  [k: string]: any;
};

// Keyframes for the running pulse dot (injected once on mount).
const PULSE_STYLE_ID = 'session-list-pulse-style';
function ensurePulseStyle() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(PULSE_STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = PULSE_STYLE_ID;
  style.textContent = `
@keyframes sflPulse {
  0%   { box-shadow: 0 0 0 0 rgba(24,144,255,0.6); }
  70%  { box-shadow: 0 0 0 6px rgba(24,144,255,0); }
  100% { box-shadow: 0 0 0 0 rgba(24,144,255,0); }
}`;
  document.head.appendChild(style);
}

const StatusDot: React.FC<{ status?: string; error?: string }> = ({ status, error }) => {
  let color = 'var(--text-muted)';
  let label = status || 'unknown';
  let animated = false;
  switch (status) {
    case 'idle':
      color = 'var(--color-success)';
      label = 'idle';
      break;
    case 'running':
      color = 'var(--color-info)';
      label = 'running';
      animated = true;
      break;
    case 'error':
      color = 'var(--color-error)';
      label = error ? `error: ${error}` : 'error';
      break;
    case 'waiting_user':
      color = 'var(--color-warning)';
      label = 'waiting user';
      break;
    default:
      break;
  }
  return (
    <Tooltip title={label}>
      <span
        style={{
          display: 'inline-block',
          width: 10,
          height: 10,
          borderRadius: '50%',
          background: color,
          marginRight: 8,
          verticalAlign: 'middle',
          animation: animated ? 'sflPulse 1.4s infinite' : 'none',
        }}
      />
    </Tooltip>
  );
};

const SessionList: React.FC = () => {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { userId } = useAuth();
  const SESSIONS_QUERY_KEY = React.useMemo(() => ['sessions', userId] as const, [userId]);

  const { data: sessions = [], isLoading: loading, refetch, isError: sessionsError } = useQuery({
    queryKey: SESSIONS_QUERY_KEY,
    queryFn: () =>
      getSessions(userId).then((res) => safeParseList(SessionSchema, extractList<SessionRow>(res)) as SessionRow[]),
    // staleTime:0 so page re-visits always refetch — WS keeps data live after that.
    // Without this, the 30s global staleTime hides status changes that happened
    // while the component was unmounted and the WS was closed.
    staleTime: 0,
  });
  useEffect(() => {
    if (sessionsError) message.error('Failed to load sessions');
  }, [sessionsError]);

  const wsRef = useRef<WebSocket | null>(null);
  const unmountedRef = useRef(false);
  const reconnectDelayRef = useRef(2000);
  const reconnectTimerRef = useRef<number | null>(null);

  const updateCache = (updater: (prev: SessionRow[]) => SessionRow[]) => {
    queryClient.setQueryData<SessionRow[]>(SESSIONS_QUERY_KEY, (prev) => updater(prev ?? []));
  };

  // --- WS merge reducer ---
  const applyEvent = (evt: any) => {
    if (!evt || !evt.type) return;
    if (evt.type === 'session_updated') {
      updateCache((prev) => {
        const idx = prev.findIndex((s) => s.id === evt.sessionId);
        if (idx < 0) return prev;
        const next = prev.slice();
        next[idx] = { ...next[idx], ...pickUpdatableFields(evt) };
        return next;
      });
    } else if (evt.type === 'session_created') {
      const row = evt.session;
      if (!row || !row.id) return;
      updateCache((prev) => (prev.some((s) => s.id === row.id) ? prev : [row, ...prev]));
    } else if (evt.type === 'session_deleted') {
      updateCache((prev) => prev.filter((s) => s.id !== evt.sessionId));
    }
  };

  const pickUpdatableFields = (evt: any): Partial<SessionRow> => {
    const out: Partial<SessionRow> = {};
    if ('title' in evt && evt.title != null) out.title = evt.title;
    if ('runtimeStatus' in evt && evt.runtimeStatus != null) out.runtimeStatus = evt.runtimeStatus;
    if ('runtimeStep' in evt) out.runtimeStep = evt.runtimeStep;
    if ('runtimeError' in evt) out.runtimeError = evt.runtimeError;
    if ('messageCount' in evt && evt.messageCount != null) out.messageCount = evt.messageCount;
    if ('updatedAt' in evt && evt.updatedAt != null) out.updatedAt = evt.updatedAt;
    return out;
  };

  const connectWs = () => {
    if (unmountedRef.current) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const url = `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`;
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch (e) {
      scheduleReconnect();
      return;
    }
    wsRef.current = ws;
    ws.onopen = () => {
      // Successful connection — reset backoff. If this is a reconnect
      // (not the very first open), also refetch to reconcile drift.
      const wasReconnect = reconnectDelayRef.current !== 2000;
      reconnectDelayRef.current = 2000;
      if (wasReconnect) {
        refetch();
      }
    };
    ws.onmessage = (ev) => {
      try {
        const evt = JSON.parse(ev.data);
        applyEvent(evt);
      } catch (e) {
        console.warn('Bad user WS payload', ev.data);
      }
    };
    ws.onerror = () => {
      // onclose will fire next; let it handle reconnect.
    };
    ws.onclose = () => {
      if (wsRef.current === ws) wsRef.current = null;
      if (!unmountedRef.current) scheduleReconnect();
    };
  };

  const scheduleReconnect = () => {
    if (unmountedRef.current) return;
    if (reconnectTimerRef.current != null) return;
    const delay = reconnectDelayRef.current;
    reconnectTimerRef.current = window.setTimeout(() => {
      reconnectTimerRef.current = null;
      // Exponential backoff up to 30s.
      reconnectDelayRef.current = Math.min(reconnectDelayRef.current * 2, 30000);
      connectWs();
    }, delay);
  };

  useEffect(() => {
    ensurePulseStyle();
    unmountedRef.current = false;
    connectWs();
    return () => {
      unmountedRef.current = true;
      if (reconnectTimerRef.current != null) {
        window.clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      const ws = wsRef.current;
      wsRef.current = null;
      if (ws) {
        try { ws.close(); } catch { /* noop */ }
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const columns = [
    {
      title: 'Title',
      dataIndex: 'title',
      key: 'title',
      width: 300,
      render: (v: string, r: SessionRow) => (
        <span>
          <StatusDot status={r.runtimeStatus} error={r.runtimeError} />
          {v && v !== 'New Session' ? v : `Session ${String(r.id ?? '').slice(0, 8)}`}
        </span>
      ),
    },
    { title: 'Agent', dataIndex: 'agentName', key: 'agentName', width: 140, render: (v: string, r: SessionRow) => v || r.agentId },
    { title: 'Messages', dataIndex: 'messageCount', key: 'messageCount', width: 80 },
    { title: 'Tokens', dataIndex: 'totalTokens', key: 'totalTokens', width: 80 },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: string) => (v ? new Date(v).toLocaleString() : '-'),
    },
  ];

  return (
    <div>
      <Card style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
      <Table
        dataSource={sessions}
        columns={columns}
        rowKey="id"
        loading={loading}
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 20, showSizeChanger: false, showTotal: (t: number) => `${t} sessions` }}
        onRow={(record) => ({
          onClick: () => navigate(`/chat/${record.id}`),
          style: { cursor: 'pointer' },
        })}
      />
      </Card>
    </div>
  );
};

export default SessionList;
