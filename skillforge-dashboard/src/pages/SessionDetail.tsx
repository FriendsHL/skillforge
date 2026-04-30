import React, { useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getSession, getSessionMessages, getTraces, getTraceSpans, getSessionSpans } from '../api';
import { useAuth } from '../contexts/AuthContext';
import TraceSidebar, { type TraceInfo } from '../components/sessions/detail/TraceSidebar';
import SessionWaterfallPanel from '../components/sessions/detail/SessionWaterfallPanel';
import TraceDetailPanel, { type TraceOverview } from '../components/sessions/detail/TraceDetailPanel';
import SessionStatsBar from '../components/sessions/detail/SessionStatsBar';
import type { SpanSummary } from '../types/observability';
import '../components/sessions/sessions.css';
import '../components/skills/skills.css';
import '../components/traces/traces.css';
import '../components/sessions/detail/session-detail.css';

interface RawMessage {
  id?: string | number;
  role?: string;
  content?: unknown;
  createdAt?: string;
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
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);

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

  // Messages for stats bar
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

  // Full input/output for the selected trace. The /api/traces list endpoint
  // truncates input/output to 200 chars; /api/traces/{id}/spans returns the
  // root span with full text. We only fetch when a trace is selected.
  const selectedTraceFullQuery = useQuery({
    queryKey: ['trace-spans', selectedTraceId],
    queryFn: async () => {
      if (!selectedTraceId) throw new Error('missing traceId');
      const res = await getTraceSpans(selectedTraceId);
      const data = res.data as { root?: Record<string, unknown> } | null;
      return data?.root ?? null;
    },
    enabled: Boolean(selectedTraceId),
    staleTime: 30_000,
  });

  // Spans for this session (using observability API for correct span IDs)
  const sessionSpansQuery = useQuery({
    queryKey: ['session-spans', sessionId, userId],
    queryFn: async () => {
      if (!sessionId || !userId) throw new Error('missing sessionId or userId');
      const res = await getSessionSpans(sessionId, userId);
      const data = res.data;
      return Array.isArray(data.spans) ? data.spans : [];
    },
    enabled: Boolean(sessionId && userId),
    staleTime: 30_000,
  });

  // Convert observability spans to SpanSummary format
  const spans = useMemo<SpanSummary[]>(() => {
    const rawSpans = sessionSpansQuery.data ?? [];
    return rawSpans.map((raw) => {
      if (raw.kind === 'llm') {
        return {
          kind: 'llm',
          spanId: String(raw.spanId || ''),
          traceId: String(raw.traceId || ''),
          parentSpanId: raw.parentSpanId as string | null,
          startedAt: String(raw.startedAt || ''),
          endedAt: raw.endedAt as string | null,
          latencyMs: Number(raw.latencyMs || 0),
          provider: raw.provider as string | null,
          model: raw.model as string | null,
          inputTokens: Number(raw.inputTokens || 0),
          outputTokens: Number(raw.outputTokens || 0),
          source: (raw.source as string) || 'live',
          stream: Boolean(raw.stream),
          hasRawRequest: Boolean(raw.hasRawRequest),
          hasRawResponse: Boolean(raw.hasRawResponse),
          hasRawSse: Boolean(raw.hasRawSse),
          blobStatus: raw.blobStatus as string | null,
          finishReason: raw.finishReason as string | null,
          error: raw.error as string | null,
          errorType: raw.errorType as string | null,
        } as SpanSummary;
      } else {
        return {
          kind: 'tool',
          spanId: String(raw.spanId || ''),
          traceId: String(raw.traceId || ''),
          parentSpanId: raw.parentSpanId as string | null,
          startedAt: String(raw.startedAt || ''),
          endedAt: raw.endedAt as string | null,
          latencyMs: Number(raw.latencyMs || 0),
          toolName: raw.toolName as string | null,
          toolUseId: raw.toolUseId as string | null,
          success: Boolean(raw.success),
          error: raw.error as string | null,
          inputPreview: raw.inputPreview as string | null,
          outputPreview: raw.outputPreview as string | null,
          subagentSessionId: raw.subagentSessionId as string | null,
        } as SpanSummary;
      }
    });
  }, [sessionSpansQuery.data]);

  const messages = useMemo<TimelineMessage[]>(() => {
    const raw = messagesQuery.data ?? [];
    return raw.map((m, i) => normalizeMessage(m, i));
  }, [messagesQuery.data]);

  const selectedSpan = useMemo<SpanSummary | null>(() => {
    if (!selectedSpanId) return null;
    return spans.find((s) => s.spanId === selectedSpanId) ?? null;
  }, [spans, selectedSpanId]);

  // Spans belonging to the selected trace. Both /api/traces.traceId and the
  // observability span feed share the AGENT_LOOP root span id (see
  // AgentLoopEngine line 608-610 and SessionSpansService.toToolSummary), so
  // filtering by traceId is safe.
  const traceSpans = useMemo<SpanSummary[]>(() => {
    if (!selectedTraceId) return [];
    return spans.filter((s) => s.traceId === selectedTraceId);
  }, [spans, selectedTraceId]);

  // Get selected trace overview for right panel. Prefer the un-truncated
  // input/output from /api/traces/{id}/spans (root span); fall back to the
  // 200-char preview from the trace list while the full payload is loading.
  const selectedTraceOverview = useMemo<TraceOverview | null>(() => {
    if (!selectedTraceId) return null;
    const trace = traces.find((t) => t.id === selectedTraceId);
    if (!trace) return null;
    const fullRoot = selectedTraceFullQuery.data;
    const fullInput =
      fullRoot && typeof fullRoot.input === 'string' ? (fullRoot.input as string) : null;
    const fullOutput =
      fullRoot && typeof fullRoot.output === 'string' ? (fullRoot.output as string) : null;
    return {
      id: trace.id,
      name: trace.name,
      input: fullInput ?? trace.input,
      output: fullOutput ?? trace.output,
      status: trace.status,
      totalMs: trace.totalMs,
      tokensIn: trace.tokensIn,
      tokensOut: trace.tokensOut,
      model: trace.model,
      startTime: trace.startTime,
    };
  }, [traces, selectedTraceId, selectedTraceFullQuery.data]);

  const sessionTitle =
    (sessionQuery.data?.title as string | undefined) ??
    (sessionId ? `Session ${sessionId.slice(0, 12)}` : 'Session');

  const isLoading = sessionQuery.isLoading || tracesQuery.isLoading || sessionSpansQuery.isLoading;
  const isError = sessionQuery.isError || tracesQuery.isError || sessionSpansQuery.isError;

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
          <SessionStatsBar
            messages={messages}
            spans={spans}
          />
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

        {/* Middle: Waterfall for selected trace */}
        <SessionWaterfallPanel
          spans={traceSpans}
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
