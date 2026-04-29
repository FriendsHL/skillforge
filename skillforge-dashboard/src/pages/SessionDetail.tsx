import React, { useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getSession, getSessionMessages, getSessionSpans } from '../api';
import { useAuth } from '../contexts/AuthContext';
import SessionTimelinePanel, {
  type TimelineMessage,
} from '../components/sessions/detail/SessionTimelinePanel';
import SpanDetailPanel from '../components/sessions/detail/SpanDetailPanel';
import type {
  SessionSpansResponse,
  SpanSummary,
} from '../types/observability';
import '../components/sessions/detail/session-detail.css';

interface RawMessage {
  id?: string | number;
  role?: string;
  content?: unknown;
  createdAt?: string;
}

function flattenContentText(content: unknown): string {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .map((b) => {
      if (b && typeof b === 'object') {
        const block = b as Record<string, unknown>;
        if (block.type === 'text' && typeof block.text === 'string') return block.text;
        if (block.type === 'tool_use' && typeof block.name === 'string') {
          return `🔧 ${block.name}`;
        }
        if (block.type === 'tool_result') {
          const v = typeof block.content === 'string' ? block.content : '';
          return v.length > 200 ? v.slice(0, 200) + '…' : v;
        }
      }
      return '';
    })
    .filter(Boolean)
    .join('\n');
}

function normalizeMessage(raw: RawMessage, idx: number): TimelineMessage {
  const role = (raw.role === 'assistant' || raw.role === 'user' || raw.role === 'system' || raw.role === 'tool')
    ? raw.role
    : 'assistant';
  return {
    id: raw.id != null ? String(raw.id) : `idx-${idx}`,
    role,
    createdAt: raw.createdAt ?? '',
    text: flattenContentText(raw.content),
  };
}

const SessionDetail: React.FC = () => {
  const { id: sessionId } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { userId } = useAuth();
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);

  const sessionQuery = useQuery({
    queryKey: ['session', sessionId, userId],
    queryFn: async () => {
      if (!sessionId) throw new Error('missing sessionId');
      const res = await getSession(sessionId, userId);
      return res.data as Record<string, unknown>;
    },
    enabled: Boolean(sessionId),
    staleTime: 30_000,
  });

  const messagesQuery = useQuery({
    queryKey: ['session', sessionId, 'messages', userId],
    queryFn: async () => {
      if (!sessionId) throw new Error('missing sessionId');
      const res = await getSessionMessages(sessionId, userId);
      return Array.isArray(res.data) ? (res.data as RawMessage[]) : [];
    },
    enabled: Boolean(sessionId),
    staleTime: 30_000,
    refetchInterval: 30_000, // align with spans query, no new WS (footgun #5)
  });

  const spansQuery = useQuery<SessionSpansResponse>({
    queryKey: ['session', sessionId, 'spans', userId],
    queryFn: async () => {
      if (!sessionId) throw new Error('missing sessionId');
      const res = await getSessionSpans(sessionId, userId, { limit: 200, kinds: ['llm', 'tool'] });
      return res.data;
    },
    enabled: Boolean(sessionId),
    staleTime: 30_000,
    refetchInterval: 30_000,
  });

  const messages = useMemo<TimelineMessage[]>(() => {
    const raw = messagesQuery.data ?? [];
    return raw.map((m, i) => normalizeMessage(m, i));
  }, [messagesQuery.data]);

  const spans = useMemo<SpanSummary[]>(() => spansQuery.data?.spans ?? [], [spansQuery.data]);

  const selectedSpan = useMemo<SpanSummary | null>(() => {
    // FE-B-USER-1 fix: do not auto-select spans[0]; first row click should fire a real
    // selection event so SpanDetailPanel transitions from empty-state to the span detail.
    if (!selectedSpanId) return null;
    return spans.find((s) => s.spanId === selectedSpanId) ?? null;
  }, [spans, selectedSpanId]);

  const sessionTitle =
    (sessionQuery.data?.title as string | undefined) ??
    (sessionId ? `Session ${sessionId.slice(0, 12)}` : 'Session');

  const isLoading = sessionQuery.isLoading || messagesQuery.isLoading || spansQuery.isLoading;
  const isError = sessionQuery.isError || messagesQuery.isError || spansQuery.isError;

  return (
    <div className="obs-session-detail">
      <header className="obs-session-detail-head">
        <button
          type="button"
          className="btn-ghost-sf"
          onClick={() => navigate('/sessions')}
          aria-label="Back to sessions"
        >
          ← Sessions
        </button>
        <div className="obs-session-detail-title-block">
          <h1 className="obs-session-detail-title">{sessionTitle}</h1>
          <div className="obs-session-detail-sub mono-sm">
            {sessionId} · {messages.length} messages · {spans.length} spans
          </div>
        </div>
      </header>

      {isError && (
        <div className="obs-empty-state">Failed to load session detail.</div>
      )}

      <div className="obs-session-detail-grid">
        <SessionTimelinePanel
          messages={messages}
          spans={spans}
          selectedSpanId={selectedSpan?.spanId ?? null}
          onSelectSpan={(s) => setSelectedSpanId(s.spanId)}
        />
        <SpanDetailPanel span={selectedSpan} />
      </div>

      {isLoading && spans.length === 0 && messages.length === 0 && (
        <div className="obs-empty-state">Loading session…</div>
      )}
    </div>
  );
};

export default SessionDetail;
