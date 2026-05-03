import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getSession, getSessionMessages, getTraces, getTraceWithDescendants } from '../api';
import { useAuth } from '../contexts/AuthContext';
import TraceSidebar, { type TraceInfo } from '../components/sessions/detail/TraceSidebar';
import SessionWaterfallPanel from '../components/sessions/detail/SessionWaterfallPanel';
import TraceDetailPanel, { type TraceOverview } from '../components/sessions/detail/TraceDetailPanel';
import SessionStatsBar from '../components/sessions/detail/SessionStatsBar';
import type {
  SpanSummary,
  UnifiedSpan,
  DescendantTraceMeta,
  TraceWithDescendants,
} from '../types/observability';
import '../components/sessions/sessions.css';
import '../components/skills/skills.css';
import '../components/traces/traces.css';
import '../components/sessions/detail/session-detail.css';

/**
 * OBS-3 — DFS depth + descendant cap. Mirrors backend defaults; passed in
 * the React Query key so a (rare) future tweak to caps invalidates cleanly.
 */
const OBS3_MAX_DEPTH = 3;
const OBS3_MAX_DESCENDANTS = 20;

interface TraceFinalizedPayload {
  type: 'trace_finalized';
  sessionId?: string;
  traceId: string;
  status: 'ok' | 'error' | 'cancelled' | 'running';
  error?: string | null;
  totalDurationMs?: number;
  toolCallCount?: number;
  eventCount?: number;
}

function isTraceFinalizedEvent(value: unknown): value is TraceFinalizedPayload {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return v.type === 'trace_finalized' && typeof v.traceId === 'string' && typeof v.status === 'string';
}

interface RawMessage {
  id?: string | number;
  /** Sequence number within the session — primary sort key (BE writes monotonically). */
  seqNo?: number;
  role?: string;
  content?: unknown;
  createdAt?: string;
  /**
   * OBS-2 M3 — `t_session_message.trace_id` exposed via SessionMessageDto.
   * NULL for historical messages (pre-M1 cut-over) and for messages outside
   * an active trace (system / pre-loop). Newly written messages carry it.
   */
  traceId?: string | null;
}

interface TimelineMessage {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  createdAt: string;
  text: string;
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

function tsOf(iso: string | null | undefined): number {
  if (!iso) return 0;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : 0;
}

function normalizeTrace(raw: Record<string, unknown>, index: number): TraceInfo {
  const tokensIn = Number(raw.inputTokens || 0);
  const tokensOut = Number(raw.outputTokens || 0);
  const inputStr = String(raw.input || '');
  const title = inputStr.length > 50 ? inputStr.slice(0, 50) + '…' : inputStr;
  
  return {
    id: String(raw.traceId || ''),
    index,
    name: String(raw.name || 'Agent loop'),
    title: title || String(raw.name || 'Agent loop'),
    input: inputStr,
    output: String(raw.output || ''),
    status: raw.success === false ? 'error' : 'ok',
    totalMs: Number(raw.durationMs || 0),
    tokensIn,
    tokensOut,
    llmCalls: Number(raw.llmCallCount || 0),
    toolCalls: Number(raw.toolCallCount || 0),
    model: String(raw.modelId || '—'),
    startTime: String(raw.startTime || ''),
  };
}



const SessionDetail: React.FC = () => {
  const { id: sessionId } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { userId } = useAuth();
  const queryClient = useQueryClient();
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);
  /** OBS-3 — page-owned expansion state for nested descendant sub-trees. */
  const [expandedSubtrees, setExpandedSubtrees] = useState<Set<string>>(new Set());

  // Session info
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

  // Messages — used by SessionWaterfallPanel (turn-index calculation) and by
  // selectedTraceOverview (derive User query / Assistant result per trace_id).
  const messagesQuery = useQuery({
    queryKey: ['session', sessionId, 'messages', userId],
    queryFn: async () => {
      if (!sessionId) throw new Error('missing sessionId');
      const res = await getSessionMessages(sessionId, userId);
      return Array.isArray(res.data) ? (res.data as RawMessage[]) : [];
    },
    enabled: Boolean(sessionId),
    staleTime: 30_000,
  });

  // Traces for this session - get full trace info including input/output
  const tracesQuery = useQuery({
    queryKey: ['session', sessionId, 'traces'],
    queryFn: async () => {
      if (!sessionId) throw new Error('missing sessionId');
      const res = await getTraces(sessionId);
      const list = Array.isArray(res.data) ? res.data : [];
      return list.map((raw, idx) => normalizeTrace(raw as Record<string, unknown>, idx));
    },
    enabled: Boolean(sessionId),
    staleTime: 30_000,
  });

  const traces = useMemo<TraceInfo[]>(() => {
    // Copy before sorting — tracesQuery.data is the cached array from
    // React Query and must not be mutated in place.
    const data = [...(tracesQuery.data ?? [])];
    // Newest first.
    data.sort((a, b) => tsOf(b.startTime) - tsOf(a.startTime));
    return data.map((t, i) => ({ ...t, index: i }));
  }, [tracesQuery.data]);

  // Auto-select latest trace (now at index 0 after desc sort).
  useMemo(() => {
    if (traces.length > 0 && selectedTraceId === null) {
      setSelectedTraceId(traces[0].id);
    }
  }, [traces, selectedTraceId]);

  // OBS-3 — Unified trace + descendants for the selected trace. One fetch
  // returns parent spans (depth=0) + descendant trace spans (depth>0) +
  // descendant trace metadata (status, durations). React Query key includes
  // depth/descendant caps so future tweaks invalidate cleanly.
  const unifiedTraceQuery = useQuery({
    queryKey: [
      'unified-trace',
      selectedTraceId,
      userId,
      OBS3_MAX_DEPTH,
      OBS3_MAX_DESCENDANTS,
    ],
    queryFn: async () => {
      if (!userId || !selectedTraceId) {
        throw new Error('missing userId / selectedTraceId');
      }
      const res = await getTraceWithDescendants(selectedTraceId, userId, {
        maxDepth: OBS3_MAX_DEPTH,
        maxDescendants: OBS3_MAX_DESCENDANTS,
      });
      return res.data;
    },
    enabled: Boolean(userId && selectedTraceId),
    staleTime: 30_000,
  });

  // OBS-3 — unified spans (parent + descendants), pre-sorted by BE.
  const unifiedSpans = useMemo<UnifiedSpan[]>(() => {
    return unifiedTraceQuery.data?.spans ?? [];
  }, [unifiedTraceQuery.data]);

  const descendants = useMemo<DescendantTraceMeta[]>(() => {
    return unifiedTraceQuery.data?.descendants ?? [];
  }, [unifiedTraceQuery.data]);

  // Flat SpanSummary view (used by SessionStatsBar + selected-span lookup).
  const spans = useMemo<SpanSummary[]>(() => {
    return unifiedSpans.map((u) => u.span);
  }, [unifiedSpans]);

  // Lazy-load handler for "Show more" button under truncated descendants.
  // Fetches the child sub-tree (max_depth=2) and merges its spans + descendants
  // back into the cached unified trace via setQueryData.
  const handleLoadMore = useCallback(
    async (childTraceId: string) => {
      if (!userId) return;
      try {
        const res = await getTraceWithDescendants(childTraceId, userId, {
          maxDepth: 2,
          maxDescendants: OBS3_MAX_DESCENDANTS,
        });
        const extra = res.data;
        queryClient.setQueryData<TraceWithDescendants>(
          ['unified-trace', selectedTraceId, userId, OBS3_MAX_DEPTH, OBS3_MAX_DESCENDANTS],
          (prev) => {
            if (!prev) return prev;
            // Merge — de-dup descendants and spans by traceId / spanId.
            const seenDescendants = new Set(prev.descendants.map((d) => d.traceId));
            const newDescendants = extra.descendants.filter((d) => !seenDescendants.has(d.traceId));
            const seenSpans = new Set(prev.spans.map((u) => u.span.spanId));
            const newSpans = extra.spans.filter((u) => !seenSpans.has(u.span.spanId));
            const mergedSpans = [...prev.spans, ...newSpans].sort((a, b) => {
              const ta = Date.parse(a.span.startedAt);
              const tb = Date.parse(b.span.startedAt);
              return (Number.isFinite(ta) ? ta : 0) - (Number.isFinite(tb) ? tb : 0);
            });
            return {
              ...prev,
              descendants: [...prev.descendants, ...newDescendants],
              spans: mergedSpans,
              truncated: false,
            };
          },
        );
      } catch (err) {
        // Swallow — UI just won't get the extra rows. Real surfaces should
        // log via a proper logger; per `frontend.md` no console.log in prod.
        void err;
      }
    },
    [queryClient, selectedTraceId, userId],
  );

  const messages = useMemo<TimelineMessage[]>(() => {
    const raw = messagesQuery.data ?? [];
    return raw.map((m, i) => normalizeMessage(m, i));
  }, [messagesQuery.data]);

  const selectedSpan = useMemo<SpanSummary | null>(() => {
    if (!selectedSpanId) return null;
    return spans.find((s) => s.spanId === selectedSpanId) ?? null;
  }, [spans, selectedSpanId]);

  // OBS-2 M3 — `spans` is already filtered to the selected trace by the
  // backend (per-trace fetch), no client-side filtering needed.

  /**
   * Selected trace overview for the right panel.
   *
   * OBS-2 M3 cut-over: `t_llm_trace` doesn't carry input/output text columns
   * any more (those used to live on `t_trace_span.AGENT_LOOP`). We derive
   * "User query" + "Assistant result" from the per-trace messages slice
   * instead — `t_session_message.trace_id` is populated for new traces post-M1.
   *
   * Fallback chain when messages don't carry trace_id (historical pre-M1
   * traces): trace.input / trace.output from the list endpoint (also null
   * post-M3), ultimately rendered as "—" by TraceDetailPanel.
   */
  const selectedTraceOverview = useMemo<TraceOverview | null>(() => {
    if (!selectedTraceId) return null;
    const trace = traces.find((t) => t.id === selectedTraceId);
    if (!trace) return null;

    const traceMessages = (messagesQuery.data ?? [])
      .filter((m) => m.traceId === selectedTraceId)
      .slice()
      .sort((a, b) => {
        // Prefer seqNo (monotonic per session); fall back to createdAt parse.
        const aSeq = typeof a.seqNo === 'number' ? a.seqNo : null;
        const bSeq = typeof b.seqNo === 'number' ? b.seqNo : null;
        if (aSeq != null && bSeq != null) return aSeq - bSeq;
        return tsOf(a.createdAt) - tsOf(b.createdAt);
      });

    const firstUser = traceMessages.find((m) => m.role === 'user');
    // Walk from the end without mutating the sorted copy.
    let lastAssistant: RawMessage | undefined;
    for (let i = traceMessages.length - 1; i >= 0; i--) {
      if (traceMessages[i].role === 'assistant') {
        lastAssistant = traceMessages[i];
        break;
      }
    }

    const derivedInput = firstUser ? flattenContentText(firstUser.content) : '';
    const derivedOutput = lastAssistant ? flattenContentText(lastAssistant.content) : '';

    return {
      id: trace.id,
      name: trace.name,
      input: derivedInput || trace.input,
      output: derivedOutput || trace.output,
      status: trace.status,
      totalMs: trace.totalMs,
      tokensIn: trace.tokensIn,
      tokensOut: trace.tokensOut,
      model: trace.model,
      startTime: trace.startTime,
    };
  }, [traces, selectedTraceId, messagesQuery.data]);

  const sessionTitle =
    (sessionQuery.data?.title as string | undefined) ??
    (sessionId ? `Session ${sessionId.slice(0, 12)}` : 'Session');

  const isLoading = sessionQuery.isLoading || tracesQuery.isLoading || unifiedTraceQuery.isLoading;
  const isError = sessionQuery.isError || tracesQuery.isError || unifiedTraceQuery.isError;

  // OBS-3 — clear expansion state when the user picks a different trace.
  // Visual contract: each new trace starts collapsed.
  useEffect(() => {
    setExpandedSubtrees(new Set());
  }, [selectedTraceId]);

  const handleToggleSubtree = useCallback((childTraceId: string) => {
    setExpandedSubtrees((prev) => {
      const next = new Set(prev);
      if (next.has(childTraceId)) next.delete(childTraceId);
      else next.add(childTraceId);
      return next;
    });
  }, []);

  /**
   * OBS-3 §2.3 — WebSocket subscribers for `trace_finalized`.
   *
   * BE contract (2026-05-03 BE Dev confirmed): each trace's `trace_finalized`
   * event broadcasts on its **own** session's WS channel. So for a trace tree
   * with N descendant child sessions, we open the parent session sub PLUS one
   * sub per descendant.sessionId. The parent's own trace_finalized arrives on
   * the parent channel; each child's arrives on the child's channel.
   *
   * Why per-session sockets (not a single multiplexed channel): the existing
   * `/ws/chat/{sessionId}` endpoint is session-scoped (matches OBS-1 infra);
   * BE didn't add cross-session routing.
   *
   * Cleanup contract (frontend.md footgun #2): we close every socket and
   * null the ref array on unmount / dep change.
   */
  const handleTraceFinalized = useCallback(
    (evt: TraceFinalizedPayload) => {
      queryClient.setQueryData<TraceWithDescendants>(
        ['unified-trace', selectedTraceId, userId, OBS3_MAX_DEPTH, OBS3_MAX_DESCENDANTS],
        (prev) => {
          if (!prev) return prev;
          // Update root trace status if this is the root, else update the
          // matching descendant entry. Both update paths preserve referential
          // identity for unaffected entries so React Query bails out cheaply.
          if (evt.traceId === prev.rootTrace.traceId) {
            return {
              ...prev,
              rootTrace: {
                ...prev.rootTrace,
                status: evt.status as DescendantTraceMeta['status'],
                error: evt.error ?? prev.rootTrace.error,
                totalDurationMs:
                  typeof evt.totalDurationMs === 'number'
                    ? evt.totalDurationMs
                    : prev.rootTrace.totalDurationMs,
                toolCallCount:
                  typeof evt.toolCallCount === 'number'
                    ? evt.toolCallCount
                    : prev.rootTrace.toolCallCount,
                eventCount:
                  typeof evt.eventCount === 'number'
                    ? evt.eventCount
                    : prev.rootTrace.eventCount,
              },
            };
          }
          const nextDescendants = prev.descendants.map((d) =>
            d.traceId === evt.traceId
              ? {
                  ...d,
                  status: evt.status as DescendantTraceMeta['status'],
                  totalDurationMs:
                    typeof evt.totalDurationMs === 'number'
                      ? evt.totalDurationMs
                      : d.totalDurationMs,
                  toolCallCount:
                    typeof evt.toolCallCount === 'number'
                      ? evt.toolCallCount
                      : d.toolCallCount,
                  eventCount:
                    typeof evt.eventCount === 'number' ? evt.eventCount : d.eventCount,
                }
              : d,
          );
          return { ...prev, descendants: nextDescendants };
        },
      );
    },
    [queryClient, selectedTraceId, userId],
  );

  // Content-stable union of session ids needing WS subs. We use a sorted
  // string key as the useEffect dep so a `setQueryData` mutation that
  // produces a new descendants array but identical session ids does NOT
  // churn the open sockets (would otherwise close+reopen on every
  // trace_finalized event).
  const wsSessionIdsKey = useMemo<string>(() => {
    const ids = new Set<string>();
    if (sessionId) ids.add(sessionId);
    for (const d of descendants) {
      if (d.sessionId) ids.add(d.sessionId);
    }
    return Array.from(ids).sort().join('|');
  }, [sessionId, descendants]);

  const wsListRef = useRef<WebSocket[]>([]);
  useEffect(() => {
    const sessionIds = wsSessionIdsKey ? wsSessionIdsKey.split('|').filter(Boolean) : [];
    if (sessionIds.length === 0) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const sockets: WebSocket[] = [];
    for (const sid of sessionIds) {
      const ws = new WebSocket(
        `${proto}://${window.location.host}/ws/chat/${sid}?token=${encodeURIComponent(token)}`,
      );
      ws.onmessage = (ev) => {
        try {
          const evt: unknown = JSON.parse(ev.data);
          if (isTraceFinalizedEvent(evt)) handleTraceFinalized(evt);
        } catch {
          // ignore malformed
        }
      };
      sockets.push(ws);
    }
    wsListRef.current = sockets;
    return () => {
      for (const ws of sockets) {
        try { ws.close(); } catch { /* ignore */ }
      }
      if (wsListRef.current === sockets) wsListRef.current = [];
    };
  }, [wsSessionIdsKey, handleTraceFinalized]);

  return (
    <div className="obs-session-detail">
      {/* Header */}
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
          {sessionId && (
            <div className="obs-session-detail-meta mono-sm">
              <span>{sessionId}</span>
            </div>
          )}
        </div>
        {sessionId && (
          <SessionStatsBar spans={spans} />
        )}
      </header>

      {isError && (
        <div className="obs-empty-state">Failed to load session detail.</div>
      )}

      {/* Three-column layout */}
      <div className="obs-session-detail-grid">
        {/* Left: Trace sidebar */}
        <TraceSidebar
          traces={traces}
          selectedTraceId={selectedTraceId}
          onSelectTrace={(id) => {
            setSelectedTraceId(id);
            setSelectedSpanId(null);
          }}
        />

        {/* Middle: Waterfall for selected trace (OBS-3 unified). */}
        <SessionWaterfallPanel
          spans={unifiedSpans}
          descendants={descendants}
          messages={messages}
          selectedSpanId={selectedSpan?.spanId ?? null}
          onSelectSpan={(s) => setSelectedSpanId(s.spanId)}
          traceRoot={selectedTraceOverview ? {
            id: selectedTraceOverview.id,
            name: selectedTraceOverview.name,
            totalMs: selectedTraceOverview.totalMs,
            startTime: selectedTraceOverview.startTime,
            status: selectedTraceOverview.status,
          } : null}
          onSelectRoot={() => setSelectedSpanId(null)}
          expandedSubtrees={expandedSubtrees}
          onToggleSubtree={handleToggleSubtree}
          truncated={unifiedTraceQuery.data?.truncated ?? false}
          onLoadMore={handleLoadMore}
        />

        {/* Right: Trace/Span detail */}
        <TraceDetailPanel
          trace={selectedTraceOverview}
          span={selectedSpan}
        />
      </div>

      {isLoading && traces.length === 0 && (
        <div className="obs-empty-state">Loading session…</div>
      )}
    </div>
  );
};

export default SessionDetail;
