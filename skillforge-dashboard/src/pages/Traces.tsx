import React, { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getTraces, getTraceSpans, extractList } from '../api';
import '../components/traces/traces.css';
import '../components/skills/skills.css';

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

function spanKind(spanType: string): string {
  if (spanType === 'LLM_CALL') return 'llm';
  if (spanType === 'TOOL_CALL') return 'tool';
  return 'agent';
}

function normalizeSpan(raw: Record<string, unknown>, rootStartMs: number): Span {
  const startMs = raw.startTime ? new Date(String(raw.startTime)).getTime() : 0;
  return {
    id: String(raw.id || ''),
    kind: spanKind(String(raw.spanType || '')),
    name: String(raw.name || ''),
    parent: (raw.parentSpanId as string) || null,
    start: Math.max(0, startMs - rootStartMs),
    dur: Number(raw.durationMs || 0),
    ok: raw.success !== false,
    input: String(raw.input || ''),
    output: String(raw.output || ''),
    error: (raw.error as string) || null,
    tokensIn: Number(raw.inputTokens || 0),
    tokensOut: Number(raw.outputTokens || 0),
    model: String(raw.modelId || ''),
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
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);

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

  // Fetch spans for selected run
  const { data: spansData } = useQuery({
    queryKey: ['trace-spans', activeRunId],
    queryFn: () => getTraceSpans(activeRunId!).then(res => res.data),
    enabled: !!activeRunId,
  });

  const spans = useMemo<Span[]>(() => {
    if (!spansData) return [];
    const root = spansData.root as Record<string, unknown> | null;
    const rootStart = root?.startTime ? new Date(String(root.startTime)).getTime() : 0;
    const rawSpans = Array.isArray(spansData.spans) ? spansData.spans : [];
    const all: Span[] = [];
    if (root) all.push(normalizeSpan(root, rootStart));
    rawSpans.forEach((s: Record<string, unknown>) => all.push(normalizeSpan(s, rootStart)));
    return all;
  }, [spansData]);

  const selectedSpan = spans.find(s => s.id === selectedSpanId) || spans[0] || null;

  const toggleStatus = (v: string) => setStatusFilter(s => s === v ? null : v);

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
            <Waterfall
              spans={spans}
              totalMs={selectedRun.totalMs || 1}
              selectedSpanId={selectedSpan?.id || null}
              onSelect={id => setSelectedSpanId(id)}
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

function Waterfall({ spans, totalMs, selectedSpanId, onSelect }: {
  spans: Span[];
  totalMs: number;
  selectedSpanId: string | null;
  onSelect: (id: string) => void;
}) {
  const rows = useMemo(() => {
    const byParent: Record<string, Span[]> = {};
    spans.forEach(s => {
      const p = s.parent || '__root';
      (byParent[p] = byParent[p] || []).push(s);
    });
    const out: { span: Span; depth: number }[] = [];
    const flatten = (parent = '__root', depth = 0) => {
      (byParent[parent] || []).forEach(span => {
        out.push({ span, depth });
        flatten(span.id, depth + 1);
      });
    };
    flatten();
    return out;
  }, [spans]);

  return (
    <div className="tr-waterfall">
      <div className="tr-waterfall-h">
        <div className="tr-waterfall-h-name">Spans</div>
        <div className="tr-waterfall-h-bar">
          <div className="tr-timescale">
            {[0, 0.25, 0.5, 0.75, 1].map(t => (
              <div key={t} className="tr-timescale-tick" style={{ left: `${t * 100}%` }}>
                <span>{fmtMs(totalMs * t)}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
      <div className="tr-waterfall-body">
        {rows.map(({ span, depth }) => {
          const pct = Math.max(0.5, (span.dur / totalMs) * 100);
          const left = (span.start / totalMs) * 100;
          return (
            <button
              key={span.id}
              className={`tr-span-row ${span.id === selectedSpanId ? 'sel' : ''} ${span.ok ? '' : 'err'}`}
              onClick={() => onSelect(span.id)}
            >
              <div className="tr-span-name" style={{ paddingLeft: 4 + depth * 18 }}>
                {depth > 0 && <span className="tr-tree-line" aria-hidden="true">└─</span>}
                <span className={`tr-kind-tag k-${span.kind}`}>{span.kind}</span>
                <span className="tr-span-label mono-sm">{span.name}</span>
              </div>
              <div className="tr-span-bar-track">
                <div
                  className={`tr-span-bar k-${span.kind} ${span.ok ? '' : 'err'}`}
                  style={{ left: `${left}%`, width: `${pct}%` }}
                  title={`${span.name} · ${fmtMs(span.dur)}`}
                />
                {(() => {
                  const endPct = left + pct;
                  const isNearEdge = endPct > 75;
                  return (
                    <span
                      className="tr-span-dur mono-sm"
                      style={isNearEdge
                        ? { left: `calc(${endPct}% - 4px)`, transform: 'translateY(-50%) translateX(-100%)', color: 'rgba(255,255,255,0.88)' }
                        : { left: `calc(${endPct}% + 4px)` }
                      }
                    >
                      {fmtMs(span.dur)}
                    </span>
                  );
                })()}
              </div>
            </button>
          );
        })}
        {rows.length === 0 && (
          <div className="tr-empty">No spans for this run.</div>
        )}
      </div>
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
