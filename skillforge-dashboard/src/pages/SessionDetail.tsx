import React, { useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { message } from 'antd';
import { getSession, getSessionMessages, getTraces, getTraceTree, createEvalScenarioFromTrace } from '../api';
import { useAuth } from '../contexts/AuthContext';
import TraceSidebar, { type TraceInfo } from '../components/sessions/detail/TraceSidebar';
import SessionWaterfallPanel from '../components/sessions/detail/SessionWaterfallPanel';
import { traceSpanToSummary } from '../components/sessions/detail/session-detail-utils';
import TraceDetailPanel, { type TraceOverview } from '../components/sessions/detail/TraceDetailPanel';
import SessionStatsBar from '../components/sessions/detail/SessionStatsBar';
import type { SpanSummary, TraceTreeDto, TraceNodeDto } from '../types/observability';
import '../components/sessions/sessions.css';
import '../components/skills/skills.css';
import '../components/traces/traces.css';
import '../components/sessions/detail/session-detail.css';

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
  const traceId = String(raw.traceId || '');
  // OBS-4 M2 — fall back to traceId if BE didn't ship rootTraceId. V45
  // backfill set root_trace_id = trace_id for all pre-M0 rows, so this
  // fallback only runs for legacy frontend cache hits or BE rollback.
  const rootTraceId = typeof raw.rootTraceId === 'string' && raw.rootTraceId.length > 0
    ? raw.rootTraceId
    : traceId;

  return {
    id: traceId,
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
    rootTraceId,
  };
}



const SessionDetail: React.FC = () => {
  const { id: sessionId } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { userId } = useAuth();
  const queryClient = useQueryClient();
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);

  // Mutation to add trace to dataset
  const importTraceMutation = useMutation({
    mutationFn: (rootTraceId: string) => createEvalScenarioFromTrace({ rootTraceId }),
    onSuccess: ({ data }) => {
      message.success(`Added to dataset: ${data.name || data.id}`);
      queryClient.invalidateQueries({ queryKey: ['eval-dataset-scenarios'] });
    },
    onError: (error: unknown) => {
      const text =
        error &&
        typeof error === 'object' &&
        'response' in error &&
        typeof (error as { response?: { data?: { error?: unknown } } }).response?.data?.error === 'string'
          ? String((error as { response?: { data?: { error?: string } } }).response?.data?.error)
          : 'Failed to add trace to dataset';
      message.error(text);
    },
  });

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

  // OBS-4 M3 — derive rootTraceId from the selected trace and fetch the
  // unified tree (replaces OBS-2's per-trace `getSessionSpans` call). For
  // pre-OBS-4 traces the V45 backfill set rootTraceId = traceId, so the
  // tree returns a single trace and the panel renders identically to OBS-2.
  const selectedTrace = useMemo<TraceInfo | null>(() => {
    if (!selectedTraceId) return null;
    return traces.find((t) => t.id === selectedTraceId) ?? null;
  }, [traces, selectedTraceId]);

  const rootTraceId = selectedTrace?.rootTraceId ?? null;

  const traceTreeQuery = useQuery({
    queryKey: ['trace-tree', rootTraceId],
    queryFn: async () => {
      if (!rootTraceId) throw new Error('missing rootTraceId');
      const res = await getTraceTree(rootTraceId);
      return res.data as TraceTreeDto;
    },
    enabled: Boolean(rootTraceId),
    staleTime: 30_000,
  });

  const traceTree: TraceTreeDto | null = traceTreeQuery.data ?? null;

  // Parent-session traces (depth=0) → their concatenated spans form the
  // "parent main timeline" passed into SessionWaterfallPanel. Stats bar
  // also uses this list (matches OBS-2 "stats reflect this session" feel).
  const parentTraces = useMemo<TraceNodeDto[]>(() => {
    if (!traceTree) return [];
    return traceTree.traces
      .filter((t) => t.depth === 0)
      .slice()
      .sort((a, b) => tsOf(a.startedAt) - tsOf(b.startedAt));
  }, [traceTree]);

  // SpanSummary list for the parent main timeline (depth=0 traces only).
  // Concat across multiple depth=0 traces in chronological order — when a
  // user message triggers multiple agent loops in the parent session they
  // all share the same root_trace_id and we want them on one timeline.
  const parentSpans = useMemo<SpanSummary[]>(() => {
    return parentTraces.flatMap((t) =>
      t.spans.map((s) => traceSpanToSummary(s, t.traceId)),
    );
  }, [parentTraces]);

  // Span lookup pool covers ALL traces in the tree so clicking a child
  // internal span (depth=2 in the waterfall) resolves to a SpanSummary the
  // right detail panel can consume.
  const allSpansInTree = useMemo<SpanSummary[]>(() => {
    if (!traceTree) return [];
    return traceTree.traces.flatMap((t) =>
      t.spans.map((s) => traceSpanToSummary(s, t.traceId)),
    );
  }, [traceTree]);

  const selectedSpan = useMemo<SpanSummary | null>(() => {
    if (!selectedSpanId) return null;
    return allSpansInTree.find((s) => s.spanId === selectedSpanId) ?? null;
  }, [allSpansInTree, selectedSpanId]);

  /**
   * Selected trace overview for the right panel.
   *
   * OBS-2 M3 cut-over: `t_llm_trace` doesn't carry input/output text columns
   * any more (those used to live on `t_trace_span.AGENT_LOOP`). We derive
   * "User query" + "Assistant result" from the per-trace messages slice
   * instead — `t_session_message.trace_id` is populated for new traces post-M1.
   *
   * OBS-4 M3 keeps this behaviour unchanged: the right panel still describes
   * the user-selected trace (single trace), not the whole tree. The waterfall
   * is the only surface that pivots to "tree-wide".
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

  const isLoading = sessionQuery.isLoading || tracesQuery.isLoading || traceTreeQuery.isLoading;
  const isError = sessionQuery.isError || tracesQuery.isError || traceTreeQuery.isError;

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
          <SessionStatsBar spans={parentSpans} />
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
          spans={parentSpans}
          traceTree={traceTree}
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
          onAddToDataset={selectedTraceOverview ? () => importTraceMutation.mutate(selectedTraceOverview.id) : undefined}
          isAddingToDataset={importTraceMutation.isPending}
        />
      </div>

      {isLoading && traces.length === 0 && (
        <div className="obs-empty-state">Loading session…</div>
      )}
    </div>
  );
};

export default SessionDetail;
