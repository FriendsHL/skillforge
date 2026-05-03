import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getTraces, getTraceWithDescendants, extractList } from '../api';
import { useAuth } from '../contexts/AuthContext';
import NestedWaterfallRenderer from '../components/observability/NestedWaterfallRenderer';
import type {
  SpanSummary,
  UnifiedSpan,
  DescendantTraceMeta,
  TraceWithDescendants,
} from '../types/observability';
import '../components/traces/traces.css';
import '../components/skills/skills.css';

const OBS3_MAX_DEPTH = 3;
const OBS3_MAX_DESCENDANTS = 20;

interface TraceFinalizedPayload {
  type: 'trace_finalized';
  sessionId?: string;
  traceId: string;
  status: 'ok' | 'error' | 'cancelled' | 'running';
  totalDurationMs?: number;
  toolCallCount?: number;
  eventCount?: number;
}

function isTraceFinalizedEvent(value: unknown): value is TraceFinalizedPayload {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return v.type === 'trace_finalized' && typeof v.traceId === 'string' && typeof v.status === 'string';
}

interface TraceRun {
  id: string;
  name: string;
  title: string;
  session: string;
  /** Full session id (un-truncated) for cross-page navigation. */
  sessionFullId: string;
  agent: string;
  status: 'ok' | 'error';
  totalMs: number;
  tokensIn: number;
  tokensOut: number;
  cost: number;
  model: string;
  llmCalls: number;
  toolCalls: number;
  at: string;
}

/**
 * Legacy local Span shape, retained for the right-pane SpanDetail (it pulls
 * fields from the legacy `getTraceSpans` response). The waterfall now uses
 * OBS-3 UnifiedSpan; we map UnifiedSpan → Span for the detail panel only.
 */
interface Span {
  id: string;
  kind: string;
  name: string;
  parent: string | null;
  start: number;
  dur: number;
  ok: boolean;
  input: string;
  output: string;
  error: string | null;
  tokensIn: number;
  tokensOut: number;
  model: string;
}

function normalizeRun(raw: Record<string, unknown>): TraceRun {
  const tokensIn = Number(raw.inputTokens || 0);
  const tokensOut = Number(raw.outputTokens || 0);
  const inputStr = String(raw.input || '');
  const title = inputStr.length > 50 ? inputStr.slice(0, 50) + '…' : inputStr;
  const sessionFullId = String(raw.sessionId || '');
  return {
    id: String(raw.traceId || ''),
    name: String(raw.name || 'Agent loop'),
    title: title || String(raw.name || 'Agent loop'),
    session: sessionFullId.slice(0, 12),
    sessionFullId,
    agent: String(raw.agentName || raw.name || 'agent'),
    status: raw.success === false ? 'error' : 'ok',
    totalMs: Number(raw.durationMs || 0),
    tokensIn,
    tokensOut,
    cost: (tokensIn * 3 + tokensOut * 15) / 1_000_000,
    model: String(raw.modelId || '—'),
    llmCalls: Number(raw.llmCallCount || 0),
    toolCalls: Number(raw.toolCallCount || 0),
    at: fmtTime(String(raw.startTime || '')),
  };
}

/** Convert OBS-3 UnifiedSpan → legacy Span shape used by SpanDetail. */
function unifiedToLegacySpan(unified: UnifiedSpan, rootStartMs: number): Span {
  const span = unified.span;
  const startMs = Date.parse(span.startedAt);
  const startOffset = Math.max(0, (Number.isFinite(startMs) ? startMs : 0) - rootStartMs);
  if (span.kind === 'llm') {
    return {
      id: span.spanId,
      kind: 'llm',
      name: span.model || 'LLM',
      parent: span.parentSpanId,
      start: startOffset,
      dur: span.latencyMs || 0,
      ok: span.error == null && span.errorType == null,
      input: '',
      output: '',
      error: span.error ?? null,
      tokensIn: span.inputTokens,
      tokensOut: span.outputTokens,
      model: span.model || '',
    };
  }
  if (span.kind === 'tool') {
    return {
      id: span.spanId,
      kind: 'tool',
      name: span.toolName || 'Tool',
      parent: span.parentSpanId,
      start: startOffset,
      dur: span.latencyMs || 0,
      ok: span.success,
      input: span.inputPreview ?? '',
      output: span.outputPreview ?? '',
      error: span.error ?? null,
      tokensIn: 0,
      tokensOut: 0,
      model: '',
    };
  }
  return {
    id: span.spanId,
    kind: 'event',
    name: span.name || span.eventType || 'Event',
    parent: span.parentSpanId,
    start: startOffset,
    dur: span.latencyMs || 0,
    ok: span.success,
    input: span.inputPreview ?? '',
    output: span.outputPreview ?? '',
    error: span.error ?? null,
    tokensIn: 0,
    tokensOut: 0,
    model: '',
  };
}

function fmtMs(ms: number): string {
  if (ms < 1000) return ms + 'ms';
  if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
  return (ms / 60000).toFixed(1) + 'm';
}

function fmtTime(iso: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const now = Date.now();
  const diff = now - d.getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

const Traces: React.FC = () => {
  const { userId } = useAuth();
  const queryClient = useQueryClient();
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  /** OBS-3 — page-owned expansion state for nested descendant sub-trees. */
  const [expandedSubtrees, setExpandedSubtrees] = useState<Set<string>>(new Set());

  const { data: rawTraces = [] } = useQuery({
    queryKey: ['traces'],
    queryFn: () => getTraces().then(res => extractList<Record<string, unknown>>(res)),
  });

  const runs = useMemo<TraceRun[]>(() => rawTraces.map(normalizeRun), [rawTraces]);

  const filteredRuns = useMemo(() => {
    return runs.filter(r => {
      if (q && !`${r.name} ${r.session} ${r.agent} ${r.model}`.toLowerCase().includes(q.toLowerCase())) return false;
      if (statusFilter === 'ok' && r.status !== 'ok') return false;
      if (statusFilter === 'error' && r.status === 'ok') return false;
      return true;
    });
  }, [runs, q, statusFilter]);

  const selectedRun = filteredRuns.find(r => r.id === selectedRunId) || filteredRuns[0] || null;
  const activeRunId = selectedRun?.id || null;

  // OBS-3 — unified trace + descendants for selected run.
  const { data: unifiedData } = useQuery({
    queryKey: ['unified-trace', activeRunId, userId, OBS3_MAX_DEPTH, OBS3_MAX_DESCENDANTS],
    queryFn: async () => {
      if (!activeRunId || !userId) throw new Error('missing activeRunId / userId');
      const res = await getTraceWithDescendants(activeRunId, userId, {
        maxDepth: OBS3_MAX_DEPTH,
        maxDescendants: OBS3_MAX_DESCENDANTS,
      });
      return res.data;
    },
    enabled: Boolean(activeRunId && userId),
    staleTime: 30_000,
  });

  const unifiedSpans = useMemo<UnifiedSpan[]>(() => unifiedData?.spans ?? [], [unifiedData]);
  const descendants = useMemo<DescendantTraceMeta[]>(() => unifiedData?.descendants ?? [], [unifiedData]);

  // Legacy Span list — derived from UnifiedSpan, used by the right SpanDetail panel.
  const spans = useMemo<Span[]>(() => {
    if (unifiedSpans.length === 0) return [];
    const starts = unifiedSpans
      .map((u) => Date.parse(u.span.startedAt))
      .filter((t) => Number.isFinite(t));
    const rootStart = starts.length > 0 ? Math.min(...starts) : 0;
    return unifiedSpans.map((u) => unifiedToLegacySpan(u, rootStart));
  }, [unifiedSpans]);

  const selectedSpan = spans.find(s => s.id === selectedSpanId) || spans[0] || null;

  const toggleStatus = (v: string) => setStatusFilter(s => s === v ? null : v);

  // Clear expanded sub-trees when switching runs (visual contract).
  useEffect(() => {
    setExpandedSubtrees(new Set());
  }, [activeRunId]);

  const handleToggleSubtree = useCallback((childTraceId: string) => {
    setExpandedSubtrees((prev) => {
      const next = new Set(prev);
      if (next.has(childTraceId)) next.delete(childTraceId);
      else next.add(childTraceId);
      return next;
    });
  }, []);

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
          ['unified-trace', activeRunId, userId, OBS3_MAX_DEPTH, OBS3_MAX_DESCENDANTS],
          (prev) => {
            if (!prev) return prev;
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
      } catch {
        // Best-effort lazy load; ignore network failures.
      }
    },
    [queryClient, activeRunId, userId],
  );

  /**
   * OBS-3 §2.3 — WS subscribers for `trace_finalized` (BE Dev confirmed 2026-05-03):
   * each trace's `trace_finalized` arrives on its **own** session's WS channel.
   * We open the parent session sub PLUS one per descendant.sessionId so child
   * status flips propagate immediately into the cached unified trace.
   */
  const handleTraceFinalized = useCallback(
    (evt: TraceFinalizedPayload) => {
      queryClient.setQueryData<TraceWithDescendants>(
        ['unified-trace', activeRunId, userId, OBS3_MAX_DEPTH, OBS3_MAX_DESCENDANTS],
        (prev) => {
          if (!prev) return prev;
          if (evt.traceId === prev.rootTrace.traceId) {
            return {
              ...prev,
              rootTrace: {
                ...prev.rootTrace,
                status: evt.status as DescendantTraceMeta['status'],
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
                    typeof evt.totalDurationMs === 'number' ? evt.totalDurationMs : d.totalDurationMs,
                  toolCallCount:
                    typeof evt.toolCallCount === 'number' ? evt.toolCallCount : d.toolCallCount,
                  eventCount:
                    typeof evt.eventCount === 'number' ? evt.eventCount : d.eventCount,
                }
              : d,
          );
          return { ...prev, descendants: nextDescendants };
        },
      );
    },
    [queryClient, activeRunId, userId],
  );

  // Content-stable session-id key (sorted '|'-joined) — see SessionDetail.tsx
  // for rationale (prevents WS churn on setQueryData mutations).
  const wsSessionIdsKey = useMemo<string>(() => {
    const ids = new Set<string>();
    if (selectedRun?.sessionFullId) ids.add(selectedRun.sessionFullId);
    for (const d of descendants) {
      if (d.sessionId) ids.add(d.sessionId);
    }
    return Array.from(ids).sort().join('|');
  }, [selectedRun?.sessionFullId, descendants]);

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
    <div className="tr-surface">
      {/* Left: runs list */}
      <aside className="tr-runs">
        <div className="tr-runs-h">
          <input className="tr-search" placeholder="Search runs, sessions…" value={q} onChange={e => setQ(e.target.value)} />
          <div className="tr-filter-chips">
            <button className={`tr-chip ${statusFilter === null ? 'on' : ''}`} onClick={() => setStatusFilter(null)}>all · {runs.length}</button>
            <button className={`tr-chip ${statusFilter === 'ok' ? 'on' : ''}`} onClick={() => toggleStatus('ok')}>ok · {runs.filter(r => r.status === 'ok').length}</button>
            <button className={`tr-chip err ${statusFilter === 'error' ? 'on' : ''}`} onClick={() => toggleStatus('error')}>err · {runs.filter(r => r.status !== 'ok').length}</button>
          </div>
        </div>
        <div className="tr-runs-list">
          {filteredRuns.map(r => (
            <button
              key={r.id}
              className={`tr-run ${r.id === activeRunId ? 'sel' : ''} ${r.status !== 'ok' ? 'err' : ''}`}
              onClick={() => { setSelectedRunId(r.id); setSelectedSpanId(null); }}
            >
              <div className="tr-run-top">
                <span className={`tr-dot ${r.status === 'ok' ? 'ok' : 'err'}`} />
                <span className="tr-run-name">{r.title}</span>
              </div>
              <div className="tr-run-meta">
                <span className="mono-sm">{r.agent}</span>
                <span className="tr-run-sep">·</span>
                <span className="mono-sm tr-session">{r.session}</span>
              </div>
              <div className="tr-run-stats">
                <span className="mono-sm">{fmtMs(r.totalMs)}</span>
                <span className="tr-run-sep">·</span>
                <span className="mono-sm">{(r.tokensIn + r.tokensOut).toLocaleString()} tok</span>
                <span className="tr-run-sep">·</span>
                <span className="mono-sm">${r.cost.toFixed(3)}</span>
                <span className="tr-run-when">{r.at}</span>
              </div>
            </button>
          ))}
          {filteredRuns.length === 0 && (
            <div className="tr-empty">No runs match your filters.</div>
          )}
        </div>
      </aside>

      {/* Right: detail */}
      {selectedRun ? (
        <section className="tr-detail">
          <RunHeader run={selectedRun} />
          <div className="tr-detail-split">
            <NestedWaterfallRenderer
              spans={unifiedSpans}
              descendants={descendants}
              totalMs={selectedRun.totalMs || 1}
              selectedSpanId={selectedSpan?.id ?? null}
              onSelectSpan={(s) => setSelectedSpanId(s.spanId)}
              mode="full"
              expandedSubtrees={expandedSubtrees}
              onToggleSubtree={handleToggleSubtree}
              truncated={unifiedData?.truncated ?? false}
              onLoadMore={handleLoadMore}
            />
            <SpanDetail span={selectedSpan} runId={selectedRun.id} session={selectedRun.session} />
          </div>
        </section>
      ) : (
        <section className="tr-detail">
          <div className="tr-empty">Select a run to inspect.</div>
        </section>
      )}
    </div>
  );
};

function RunHeader({ run }: { run: TraceRun }) {
  return (
    <div className="tr-run-header">
      <div className="tr-run-header-top">
        <div>
          <div className="tr-run-header-title">
            <span className={`tr-dot ${run.status === 'ok' ? 'ok' : 'err'}`} />
            <h2>{run.name}</h2>
            <span className="kv-chip-sf">{run.status}</span>
          </div>
          <div className="tr-run-header-sub">
            <span>{run.id.slice(0, 16)}</span>
            <span>·</span>
            <span>{run.session}</span>
            <span>·</span>
            <span>{run.at}</span>
          </div>
        </div>
        {run.sessionFullId && (
          <Link
            to={`/sessions/${run.sessionFullId}`}
            className="btn-ghost-sf"
            title="Open this trace's session in the merged LLM/Tool view"
          >
            🔬 在 Session 视图打开
          </Link>
        )}
      </div>
      <div className="tr-stats-bar">
        <StatItem label="Latency" value={fmtMs(run.totalMs)} />
        <StatItem label="LLM calls" value={String(run.llmCalls)} />
        <StatItem label="Tool calls" value={String(run.toolCalls)} />
        <StatItem label="Tokens in" value={run.tokensIn.toLocaleString()} />
        <StatItem label="Tokens out" value={run.tokensOut.toLocaleString()} />
        <StatItem label="Cost" value={`$${run.cost.toFixed(4)}`} />
        <StatItem label="Model" value={run.model} mono />
      </div>
    </div>
  );
}

function StatItem({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="tr-stat">
      <span className="tr-stat-lbl">{label}</span>
      <span className={`tr-stat-v ${mono ? 'mono' : ''}`}>{value}</span>
    </div>
  );
}

function SpanDetail({ span, runId, session }: { span: Span | null; runId: string; session: string }) {
  const [tab, setTab] = useState('io');

  useEffect(() => { setTab('io'); }, [span?.id]);

  if (!span) return <div className="tr-span-detail"><div className="tr-empty">No span selected.</div></div>;

  return (
    <div className="tr-span-detail">
      <div className="tr-span-detail-h">
        <div className="tr-span-detail-title">
          <span className={`tr-kind-tag k-${span.kind}`}>{span.kind}</span>
          <b className="mono-sm">{span.name}</b>
          <span className="kv-chip-sf">{fmtMs(span.dur)}</span>
          <span className="kv-chip-sf">{span.tokensIn + span.tokensOut} tok</span>
          {!span.ok && <span className="kv-chip-sf" style={{ color: 'var(--color-err)' }}>error</span>}
        </div>
        <div className="tr-span-detail-tabs">
          {['io', 'metadata', 'raw'].map(t => (
            <button key={t} className={`tr-tab ${tab === t ? 'on' : ''}`} onClick={() => setTab(t)}>
              {t === 'io' ? 'I/O' : t}
            </button>
          ))}
        </div>
      </div>
      <div className="tr-span-detail-body">
        {tab === 'io' && (
          <>
            <div className="tr-io-block">
              <div className="tr-io-label">
                <span>Input</span>
                <button className="mini-btn" onClick={() => navigator.clipboard?.writeText(span.input || '')}>copy</button>
              </div>
              <pre className="tr-io-pre">{span.input || '—'}</pre>
            </div>
            <div className="tr-io-block">
              <div className="tr-io-label">
                <span>Output</span>
                <button className="mini-btn" onClick={() => navigator.clipboard?.writeText(span.output || '')}>copy</button>
              </div>
              <pre className={`tr-io-pre ${span.ok ? '' : 'err'}`}>
                {span.ok ? (span.output || '—') : (span.error || span.output || 'error')}
              </pre>
            </div>
          </>
        )}
        {tab === 'metadata' && (
          <div className="tr-meta-grid">
            <MetaRow k="span.id" v={span.id} />
            <MetaRow k="kind" v={span.kind} />
            <MetaRow k="name" v={span.name} />
            <MetaRow k="parent" v={span.parent || '(root)'} />
            <MetaRow k="start" v={fmtMs(span.start) + ' (offset)'} />
            <MetaRow k="duration" v={fmtMs(span.dur)} />
            <MetaRow k="status" v={span.ok ? 'ok' : 'error'} />
            <MetaRow k="tokens.in" v={String(span.tokensIn)} />
            <MetaRow k="tokens.out" v={String(span.tokensOut)} />
            {span.model && <MetaRow k="model" v={span.model} />}
            <MetaRow k="run.id" v={runId} />
            <MetaRow k="session" v={session} />
          </div>
        )}
        {tab === 'raw' && (
          <pre className="tr-io-pre">{JSON.stringify(span, null, 2)}</pre>
        )}
      </div>
    </div>
  );
}

function MetaRow({ k, v }: { k: string; v: string }) {
  return (
    <div className="tr-meta-row">
      <span className="tr-meta-k mono-sm">{k}</span>
      <span className="tr-meta-v mono-sm">{v}</span>
    </div>
  );
}

export default Traces;
