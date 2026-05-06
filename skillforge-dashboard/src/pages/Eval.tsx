import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Select, message } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import {
  applyEvalTaskImprovement,
  compareEvalTasks,
  createEvalAnnotation,
  createEvalScenarioVersion,
  getEvalAnnotations,
  getEvalTask,
  getEvalTaskAnalysisSessions,
  getEvalTaskItems,
  getEvalTasks,
  triggerEvalTask,
  updateEvalAnnotation,
  getAgents,
  extractList,
  type EvalAnnotation,
  type EvalTaskAnalysisSession,
  type EvalTaskCompareEntry,
  type EvalTaskItem,
  type EvalTaskSummary,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import ScenarioDraftPanel from '../components/ScenarioDraftPanel';
import DatasetBrowser from '../components/evals/DatasetBrowser';
import AnalyzeCaseModal, { type AnalyzeTarget } from '../components/evals/AnalyzeCaseModal';
import '../components/agents/agents.css';
import '../components/evals/evals.css';
import '../components/skills/skills.css';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);
const PLAY_ICON = (
  <svg width={10} height={10} viewBox="0 0 16 16" fill="currentColor"><path d="M4 3l10 5-10 5z" /></svg>
);

interface EvalRow {
  id: string;
  name: string;
  suite: string;
  target: string;
  agentId: string;
  lastRun: string;
  cases: number;
  pass: number;
  fail: number;
  score: number;
  trend: number[];
  runs: number;
  status: string;
  raw: EvalTaskSummary;
}

// Per-row live progress state populated from `eval_progress` WS events.
// Stored separately from the run row so we don't have to refetch the whole
// task detail just to update a progress bar.
interface ProgressState {
  passedCount: number;
  totalCount: number;
  currentScenarioName?: string;
  completed?: boolean;
}

function normalizeEval(raw: EvalTaskSummary, agents: Record<string, unknown>[]): EvalRow {
  const agentId = String(raw.agentDefinitionId || '');
  const agent = agents.find(a => String(a.id) === agentId);
  const total = Number(raw.scenarioCount ?? raw.totalScenarios ?? 0);
  const passed = Number(raw.passCount ?? 0);
  const failed = Number(raw.failCount ?? 0);
  const scorePct = Number(
    raw.compositeAvg
      ?? raw.overallPassRate
      ?? (total > 0 ? (passed / total) * 100 : 0),
  );
  const score = Number.isFinite(scorePct) ? scorePct / 100 : 0;

  let status = 'fail';
  if (raw.status === 'RUNNING' || raw.status === 'PENDING') status = 'warn';
  else if (raw.status === 'FAILED' || raw.status === 'CANCELLED') status = 'fail';
  else if (score >= 0.9) status = 'pass';
  else if (score >= 0.7) status = 'warn';

  return {
    id: raw.id,
    name: agent ? String(agent.name || `Agent #${agentId}`) : `Eval ${String(raw.id || '').slice(0, 8)}`,
    suite: 'default',
    target: agent ? String(agent.name || agentId) : agentId,
    agentId,
    lastRun: fmtTime(String(raw.completedAt || raw.startedAt || '')),
    cases: total,
    pass: passed,
    fail: failed,
    score,
    trend: generateTrend(score),
    runs: 1,
    status,
    raw,
  };
}

function generateTrend(currentScore: number): number[] {
  const trend: number[] = [];
  for (let i = 0; i < 7; i++) {
    const noise = (Math.random() - 0.5) * 0.15;
    trend.push(Math.max(0, Math.min(1, currentScore + noise)));
  }
  trend[6] = currentScore;
  return trend;
}

function scoreColor(s: number): string {
  if (s >= 0.9) return '#3a7d54';
  if (s >= 0.75) return '#3a527d';
  if (s >= 0.6) return '#b07a3a';
  return '#8a2a2a';
}

function fmtTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  const now = Date.now();
  const diff = now - d.getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

function MiniSpark({ data, color, w = 140, h = 30 }: { data: number[]; color: string; w?: number; h?: number }) {
  if (data.length < 2) return null;
  const min = Math.min(...data) * 0.9;
  const max = Math.max(...data) * 1.1 || 1;
  const pts = data.map((v, i) => {
    const x = (i / (data.length - 1)) * w;
    const y = h - ((v - min) / (max - min)) * h;
    return `${x},${y}`;
  });
  return (
    <div className="mini-spark">
      <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`}>
        <polyline points={pts.join(' ')} fill="none" stroke={color} strokeWidth="1.5" strokeLinejoin="round" />
      </svg>
    </div>
  );
}

function FilterItem({ label, count, active, onClick }: { label: string; count: number; active: boolean; onClick: () => void }) {
  return (
    <button className={`filter-item ${active ? 'on' : ''}`} onClick={onClick}>
      <span>{label}</span>
      <span className="filter-item-count">{count}</span>
    </button>
  );
}

type TopTab = 'runs' | 'datasets' | 'annotations';
type EvalMetric = 'composite' | 'quality' | 'efficiency' | 'latency' | 'cost';

const METRIC_OPTIONS: Array<{ value: EvalMetric; label: string }> = [
  { value: 'composite', label: 'Composite' },
  { value: 'quality', label: 'Quality' },
  { value: 'efficiency', label: 'Efficiency' },
  { value: 'latency', label: 'Latency' },
  { value: 'cost', label: 'Cost' },
];

function getMetricValue(item: Pick<EvalTaskItem, 'compositeScore' | 'qualityScore' | 'efficiencyScore' | 'latencyScore' | 'costScore'>, metric: EvalMetric): number | null {
  switch (metric) {
    case 'quality':
      return item.qualityScore ?? null;
    case 'efficiency':
      return item.efficiencyScore ?? null;
    case 'latency':
      return item.latencyScore ?? null;
    case 'cost':
      return item.costScore ?? null;
    case 'composite':
    default:
      return item.compositeScore ?? null;
  }
}

function formatMetricValue(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value) ? '—' : `${Math.round(value)}%`;
}

function computeMetricDelta(entries: EvalTaskCompareEntry[], metric: EvalMetric): number | null {
  const values = entries
    .map((entry) => getMetricValue(entry, metric))
    .filter((value): value is number => value != null && Number.isFinite(value));
  if (values.length < 2) {
    return null;
  }
  return Math.max(...values) - Math.min(...values);
}

const Eval: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [topTab, setTopTab] = useState<TopTab>('runs');
  const [q, setQ] = useState('');
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [open, setOpen] = useState<EvalRow | null>(null);
  const [drawerTab, setDrawerTab] = useState('cases');
  const [runDialog, setRunDialog] = useState(false);
  const [compareSelection, setCompareSelection] = useState<string[]>([]);
  const [compareOpen, setCompareOpen] = useState(false);
  const [annotationSeed, setAnnotationSeed] = useState<EvalTaskItem | null>(null);
  // EVAL-V2 M1: per-evalRunId live progress state, fed by `eval_progress` WS events.
  const [progressByRun, setProgressByRun] = useState<Record<string, ProgressState>>({});

  const { data: rawTasks = [] } = useQuery({
    queryKey: ['eval-tasks'],
    queryFn: () => getEvalTasks().then(res => extractList<EvalTaskSummary>(res)),
    refetchInterval: (query) => {
      const data = query.state.data as EvalTaskSummary[] | undefined;
      return data?.some(r => r.status === 'RUNNING' || r.status === 'PENDING') ? 3000 : false;
    },
  });

  const { data: rawAgents = [] } = useQuery({
    queryKey: ['agents'],
    queryFn: () => getAgents().then(res => extractList<Record<string, unknown>>(res)),
  });

  const all = useMemo<EvalRow[]>(() => rawTasks.map(r => normalizeEval(r, rawAgents)), [rawTasks, rawAgents]);

  // EVAL-V2 M1/M3a: WS subscription for live progress events. Backend event
  // payload still uses evalRunId as the identifier, but it now maps 1:1 to
  // the task id on the new task/item model.
  //
  // We attach to the
  // user-level WS channel (same channel used by SessionList for runtimeStatus
  // updates). Reconnect-after-disconnect intentionally has no replay logic;
  // when WS reconnects we just invalidate the task list so any in-flight
  // RUNNING row gets a fresh GET /eval/tasks/{id} via the existing 3s poll.
  const wsRef = useRef<WebSocket | null>(null);
  const unmountedRef = useRef(false);
  useEffect(() => {
    unmountedRef.current = false;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const url = `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`;
    let ws: WebSocket | null = null;
    try {
      ws = new WebSocket(url);
      wsRef.current = ws;
      ws.onmessage = (e) => {
        if (unmountedRef.current) return;
        try {
          const msg = JSON.parse(e.data) as Record<string, unknown>;
          if (msg.type !== 'eval_progress') return;
          const evt = String(msg.event || '');
          const evalRunId = String(msg.evalRunId || '');
          if (!evalRunId) return;
          if (evt === 'eval_run_completed') {
            setProgressByRun(prev => ({
              ...prev,
              [evalRunId]: {
                passedCount: Number(msg.passedCount || 0),
                totalCount: Number(msg.totalCount || 0),
                completed: true,
              },
            }));
            // Trigger a refetch so the row's terminal score/attribution is
            // pulled from the freshly persisted EvalTaskEntity.
            queryClient.invalidateQueries({ queryKey: ['eval-tasks'] });
            queryClient.invalidateQueries({ queryKey: ['eval-task', evalRunId] });
            return;
          }
          // case_running / case_passed / case_failed
          //
          // Note: the backend sends `passedCount` post-increment for
          // case_passed (and unchanged for case_running / case_failed). So we
          // trust the backend value as-is — do NOT add 1 again on the FE,
          // which would double-count.
          setProgressByRun(prev => {
            const prior = prev[evalRunId];
            const passedCount = Number(msg.passedCount ?? prior?.passedCount ?? 0);
            const totalCount = Number(msg.totalCount ?? prior?.totalCount ?? 0);
            const next: ProgressState = {
              ...prior,
              passedCount,
              totalCount,
              currentScenarioName: typeof msg.scenarioName === 'string' ? msg.scenarioName : prior?.currentScenarioName,
            };
            return { ...prev, [evalRunId]: next };
          });
        } catch {
          // swallow malformed messages — avoids cascading state updates
        }
      };
      ws.onerror = () => { /* noop */ };
      ws.onclose = () => {
        // On disconnect we don't try to replay; refetching the runs list on
        // next mount or poll tick will reconcile. (D2(a) reconnect strategy.)
        if (wsRef.current === ws) wsRef.current = null;
      };
    } catch {
      // ignore — WS unavailable, table polling still keeps rows current
    }
    return () => {
      unmountedRef.current = true;
      const cur = wsRef.current;
      wsRef.current = null;
      if (cur) {
        try { cur.close(); } catch { /* noop */ }
      }
    };
  }, [userId, queryClient]);

  const rows = useMemo(() => {
    return all.filter(e => {
      if (q && !`${e.name} ${e.suite} ${e.target}`.toLowerCase().includes(q.toLowerCase())) return false;
      if (filterStatus && e.status !== filterStatus) return false;
      return true;
    });
  }, [all, q, filterStatus]);

  const toggleStatus = (v: string) => setFilterStatus(s => s === v ? null : v);
  const openDetail = (e: EvalRow) => { setOpen(e); setDrawerTab('cases'); };
  const toggleCompareSelection = (taskId: string) => {
    setCompareSelection(prev => {
      if (prev.includes(taskId)) {
        return prev.filter(id => id !== taskId);
      }
      if (prev.length >= 2) {
        return [prev[1], taskId];
      }
      return [...prev, taskId];
    });
  };

  return (
    <div className="agents-view">
      <aside className="agents-filters">
        <div className="agents-filters-h">Search</div>
        <input className="agents-search" placeholder="name, suite, target…" value={q} onChange={e => setQ(e.target.value)} />

        <div className="agents-filters-h">Status</div>
        {['pass', 'warn', 'fail'].map(s => (
          <FilterItem key={s} label={s} count={all.filter(x => x.status === s).length} active={filterStatus === s} onClick={() => toggleStatus(s)} />
        ))}
      </aside>

      <section className="agents-main">
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Evals</h1>
            <p className="agents-head-sub">{rows.length} eval tasks · {all.reduce((a, e) => a + e.cases, 0)} cases total</p>
          </div>
          <div className="agents-head-actions">
            {compareSelection.length > 0 && (
              <>
                <button
                  className="btn-ghost-sf"
                  disabled={compareSelection.length !== 2}
                  onClick={() => setCompareOpen(true)}
                >
                  Compare {compareSelection.length}/2
                </button>
                <button className="btn-ghost-sf" onClick={() => setCompareSelection([])}>
                  Clear compare
                </button>
              </>
            )}
            <button className="btn-ghost-sf" onClick={() => setRunDialog(true)}>{PLAY_ICON} Run eval</button>
          </div>
        </header>

        <div className="eval-toptab-row" role="tablist">
          {(['runs', 'datasets', 'annotations'] as TopTab[]).map(t => (
            <button
              key={t}
              role="tab"
              aria-selected={topTab === t}
              className={`eval-toptab ${topTab === t ? 'on' : ''}`}
              onClick={() => setTopTab(t)}
            >
              {t === 'runs' ? 'Tasks' : t === 'datasets' ? 'Datasets' : 'Annotations'}
              {t === 'runs' && <span className="eval-toptab-count">{all.length}</span>}
            </button>
          ))}
        </div>

        <div className="agents-body">
          {topTab === 'datasets' ? (
            <DatasetBrowser agents={rawAgents} userId={userId} />
          ) : topTab === 'annotations' ? (
            <AnnotationQueue
              userId={userId}
              seed={annotationSeed}
              onSeedConsumed={() => setAnnotationSeed(null)}
            />
          ) : rows.length === 0 ? (
            <div className="sf-empty-state">No eval runs yet. Select an agent and run an eval.</div>
          ) : (
            <div className="eval-grid">
              {rows.map(e => {
                const live = progressByRun[e.id];
                const isRunning = String(e.raw.status) === 'RUNNING';
                const liveTotal = live?.totalCount || e.cases;
                const livePassed = live?.passedCount ?? e.pass;
                const pct = liveTotal > 0 ? Math.min(100, Math.round((livePassed / liveTotal) * 100)) : 0;
                return (
                  <div key={e.id} className={`eval-card s-${e.status}`}>
                    <button className="eval-card-body" onClick={() => openDetail(e)}>
                      <div className="eval-card-h">
                        <h3>{e.name}</h3>
                        <span className={`sess-status s-${e.status === 'pass' ? 'idle' : e.status === 'warn' ? 'waiting' : 'error'}`}>
                          {e.status}
                        </span>
                      </div>
                      <div className="eval-card-meta">
                        <span className="kv-chip-sf">suite · {e.suite}</span>
                        <span className="kv-chip-sf">target · {e.target}</span>
                      </div>
                      <div className="eval-big">
                        <div className="eval-score" style={{ color: scoreColor(e.score) }}>
                          {(e.score * 100).toFixed(1)}<em>%</em>
                        </div>
                        <div className="eval-trend">
                          <MiniSpark data={e.trend} color={scoreColor(e.score)} />
                          <span className="mono-sm">{e.runs} runs</span>
                        </div>
                      </div>
                      {isRunning && liveTotal > 0 && (
                        <div className="eval-progress" aria-label="eval progress">
                          <div className="eval-progress-bar">
                            <div className="eval-progress-bar-fill" style={{ width: `${pct}%` }} />
                          </div>
                          <div className="eval-progress-meta">
                            <span className="cur" title={live?.currentScenarioName ?? ''}>
                              {live?.currentScenarioName ? `▶ ${live.currentScenarioName}` : 'running…'}
                            </span>
                            <span>{livePassed}/{liveTotal} ({pct}%)</span>
                          </div>
                        </div>
                      )}
                      <div className="eval-stats">
                        <div><span className="n ok">{e.pass}</span><span className="lbl">pass</span></div>
                        <div><span className="n err">{e.fail}</span><span className="lbl">fail</span></div>
                        <div><span className="n">{e.cases}</span><span className="lbl">total</span></div>
                        <div><span className="n">{e.lastRun}</span><span className="lbl">last run</span></div>
                      </div>
                    </button>
                    <div className="eval-card-actions">
                      <button
                        className={`sf-mini-btn ${compareSelection.includes(e.id) ? 'on' : ''}`}
                        onClick={(ev) => {
                          ev.stopPropagation();
                          toggleCompareSelection(e.id);
                        }}
                      >
                        {compareSelection.includes(e.id) ? 'Selected' : 'Select compare'}
                      </button>
                      <button className="sf-mini-btn" onClick={(ev) => { ev.stopPropagation(); setRunDialog(true); }}>
                        {PLAY_ICON} Run
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </section>

      {open && (
        <EvalDrawer
          evalRow={open}
          tab={drawerTab}
          setTab={setDrawerTab}
          onClose={() => setOpen(null)}
          onRun={() => setRunDialog(true)}
          onOpenAnnotations={(item) => {
            setAnnotationSeed(item);
            setTopTab('annotations');
          }}
          agents={rawAgents}
          userId={userId}
        />
      )}

      {runDialog && (
        <RunEvalDialog
          agents={rawAgents}
          userId={userId}
          onClose={() => setRunDialog(false)}
          onSuccess={() => {
            setRunDialog(false);
            queryClient.invalidateQueries({ queryKey: ['eval-tasks'] });
          }}
        />
      )}

      {compareOpen && compareSelection.length === 2 && (
        <CompareTasksModal
          taskIds={compareSelection}
          onClose={() => setCompareOpen(false)}
        />
      )}
    </div>
  );
};

interface EvalDrawerProps {
  evalRow: EvalRow;
  tab: string;
  setTab: (t: string) => void;
  onClose: () => void;
  onRun: () => void;
  onOpenAnnotations: (item: EvalTaskItem) => void;
  agents: Record<string, unknown>[];
  userId: number;
}

function EvalDrawer({ evalRow, tab, setTab, onClose, onRun, onOpenAnnotations, agents, userId }: EvalDrawerProps) {
  const queryClient = useQueryClient();
  const [metric, setMetric] = useState<EvalMetric>('composite');
  const [applyingImprovement, setApplyingImprovement] = useState(false);
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const { data: runDetail } = useQuery({
    queryKey: ['eval-task', evalRow.id],
    queryFn: () => getEvalTask(evalRow.id).then(res => res.data),
    enabled: !!evalRow.id,
  });

  const { data: taskItems = [] } = useQuery({
    queryKey: ['eval-task-items', evalRow.id],
    queryFn: () => getEvalTaskItems(evalRow.id).then(res => res.data ?? []),
    enabled: !!evalRow.id,
  });

  const { data: taskAnalysisSessions = [] } = useQuery({
    queryKey: ['eval-task-analysis-sessions', evalRow.id, userId],
    queryFn: () => getEvalTaskAnalysisSessions(evalRow.id, userId).then(res => res.data ?? []),
    enabled: !!evalRow.id,
  });

  const [analyzing, setAnalyzing] = useState<AnalyzeTarget | null>(null);

  const handleApplyImprovement = async () => {
    if (!runDetail?.id || !runDetail.improvementSuggestion || applyingImprovement) {
      return;
    }
    setApplyingImprovement(true);
    try {
      const res = await applyEvalTaskImprovement(runDetail.id, userId);
      message.success(`Improvement started: ${res.data.abRunId}`);
      queryClient.invalidateQueries({ queryKey: ['eval-task', evalRow.id] });
    } catch (error: unknown) {
      const apiMessage =
        error && typeof error === 'object' && 'response' in error
          ? (error as { response?: { data?: { message?: string; error?: string } } }).response?.data?.message
            ?? (error as { response?: { data?: { message?: string; error?: string } } }).response?.data?.error
          : null;
      message.error(apiMessage || 'Failed to apply improvement suggestion');
    } finally {
      setApplyingImprovement(false);
    }
  };

  const tabs = [
    { id: 'cases', label: 'Cases' },
    { id: 'history', label: 'History' },
    { id: 'scenarios', label: 'Scenarios' },
    { id: 'config', label: 'Config' },
  ];

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div>
              <h2 className="sf-drawer-title">{evalRow.name}</h2>
              <p className="sf-drawer-subtitle">suite · {evalRow.suite} → target · {evalRow.target}</p>
            </div>
            <div className="sf-drawer-actions">
              {runDetail && (
                <button
                  className="btn-ghost-sf"
                  onClick={() => setAnalyzing({ kind: 'task', task: runDetail })}
                >
                  Analyze task
                </button>
              )}
              <button className="btn-ghost-sf" onClick={onRun}>{PLAY_ICON} Run now</button>
            </div>
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`sess-status s-${evalRow.status === 'pass' ? 'idle' : evalRow.status === 'warn' ? 'waiting' : 'error'}`}>
              {evalRow.status}
            </span>
            <span className="kv-chip-sf" style={{ fontWeight: 500, color: scoreColor(evalRow.score) }}>
              {(evalRow.score * 100).toFixed(1)}%
            </span>
            <span className="kv-chip-sf">{evalRow.pass}/{evalRow.cases} pass</span>
            <span className="kv-chip-sf">last · {evalRow.lastRun}</span>
          </div>
        </div>

        <nav className="sf-drawer-tabs">
          {tabs.map(t => (
            <button key={t.id} className={`sf-drawer-tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>

        <div className="sf-drawer-body">
          {tab === 'cases' && (
            <div>
              {Boolean(runDetail?.errorMessage) && (
                <div style={{ marginBottom: 12, padding: '10px 14px', background: 'var(--error-bg, #2a1010)', border: '1px solid var(--error-border, #5c2020)', borderRadius: 6, fontSize: 12, color: 'var(--error-fg, #d97b5c)', fontFamily: 'var(--font-mono)' }}>
                  {String(runDetail?.errorMessage)}
                </div>
              )}
              {(runDetail?.attributionSummary || runDetail?.improvementSuggestion) && (
                <div style={{ marginBottom: 16, display: 'grid', gap: 10 }}>
                  {runDetail?.attributionSummary && (
                    <div className="scn-detail-section" style={{ marginBottom: 0 }}>
                      <h4>Attribution summary</h4>
                      <pre>{runDetail.attributionSummary}</pre>
                    </div>
                  )}
                  {runDetail?.improvementSuggestion && (
                    <div className="scn-detail-section" style={{ marginBottom: 0 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}>
                        <h4 style={{ marginBottom: 0 }}>Improvement suggestion</h4>
                        <button
                          className="sf-mini-btn"
                          disabled={applyingImprovement}
                          onClick={handleApplyImprovement}
                        >
                          {applyingImprovement ? 'Applying…' : 'Apply suggestion'}
                        </button>
                      </div>
                      <pre>{runDetail.improvementSuggestion}</pre>
                    </div>
                  )}
                </div>
              )}
              <div className="sf-section-h" style={{ marginBottom: 10 }}>
                <span>{taskItems.length} cases</span>
                <Select<EvalMetric>
                  size="small"
                  value={metric}
                  onChange={setMetric}
                  style={{ minWidth: 140 }}
                  options={METRIC_OPTIONS}
                />
              </div>
              {taskItems.length === 0 ? (
                <div className="sf-empty-state">No task items available.</div>
              ) : (
                <div className="scn-result-list">
                  {taskItems.map(item => (
                    <TaskItemCard
                      key={item.id}
                      item={item}
                      metric={metric}
                      onAnalyze={() => {
                        if (!runDetail) return;
                        setAnalyzing({ kind: 'item', task: runDetail, item });
                      }}
                      onAnnotate={() => {
                        onOpenAnnotations(item);
                        onClose();
                      }}
                    />
                  ))}
                </div>
              )}
              {taskAnalysisSessions.length > 0 && (
                <div style={{ marginTop: 20 }}>
                  <div className="sf-section-h" style={{ marginBottom: 8 }}>
                    Analysis sessions ({taskAnalysisSessions.length})
                  </div>
                  <div style={{ display: 'grid', gap: 8 }}>
                    {taskAnalysisSessions.map(session => (
                      <TaskAnalysisSessionRow key={session.sessionId} session={session} />
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
          {tab === 'history' && (
            <div className="history-chart">
              <MiniSpark data={evalRow.trend} color={scoreColor(evalRow.score)} w={640} h={140} />
              <div className="sf-empty-state" style={{ marginTop: 16 }}>
                Historical runs will appear here as more evals are executed.
              </div>
            </div>
          )}
          {tab === 'scenarios' && (
            <ScenarioDraftPanel agentId={evalRow.agentId} />
          )}
          {tab === 'config' && (
            <pre className="sf-code-block">{JSON.stringify(evalRow.raw, null, 2)}</pre>
          )}
        </div>
      </aside>

      {analyzing && (
        <AnalyzeCaseModal
          target={analyzing}
          agents={agents}
          userId={userId}
          onClose={() => setAnalyzing(null)}
        />
      )}
    </>
  );
}

interface TaskItemCardProps {
  item: EvalTaskItem;
  metric: EvalMetric;
  onAnalyze: () => void;
  onAnnotate: () => void;
}

function TaskItemCard({ item, metric, onAnalyze, onAnnotate }: TaskItemCardProps) {
  const [showRationale, setShowRationale] = useState(false);
  const [showOutput, setShowOutput] = useState(false);
  const score = getMetricValue(item, metric) ?? item.compositeScore ?? 0;
  const score01 = score / 100;
  const tier = score >= 80 ? 'pass' : score >= 60 ? 'warn' : item.status === 'PASS' ? 'pass' : 'fail';

  const attribution = item.attribution ?? 'NONE';
  const isFailedAttr = attribution !== 'NONE';

  return (
    <div className={`scn-result-card s-${tier}`}>
      <div className="scn-result-h">
        <div className="scn-result-h-l">
          <span className="scn-result-name">{item.scenarioId}</span>
          <span className={`sess-status s-${item.status === 'PASS' ? 'idle' : item.status === 'TIMEOUT' ? 'waiting' : 'error'}`}>
            {item.status}
          </span>
        </div>
        <div className="scn-result-score" style={{ color: scoreColor(score01) }}>
          {formatMetricValue(score)}<em>{metric === 'composite' ? '' : ` · ${metric}`}</em>
        </div>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 8 }}>
        {item.scenarioSource && <span className="kv-chip-sf">{item.scenarioSource}</span>}
        {item.sessionId && <span className="kv-chip-sf" title={item.sessionId}>session · {item.sessionId.slice(0, 8)}</span>}
        {isFailedAttr && (
          <span className="scn-result-attr attr-fail">
            {attribution.toLowerCase().replace(/_/g, ' ')}
          </span>
        )}
        {item.rootTraceId && (
          <span className="kv-chip-sf" title={item.rootTraceId}>
            trace · {item.rootTraceId.slice(0, 8)}
          </span>
        )}
        <span className={`kv-chip-sf ${metric === 'composite' ? 'on' : ''}`}>composite · {formatMetricValue(item.compositeScore)}</span>
        <span className={`kv-chip-sf ${metric === 'quality' ? 'on' : ''}`}>quality · {formatMetricValue(item.qualityScore)}</span>
        <span className={`kv-chip-sf ${metric === 'efficiency' ? 'on' : ''}`}>efficiency · {formatMetricValue(item.efficiencyScore)}</span>
        <span className={`kv-chip-sf ${metric === 'latency' ? 'on' : ''}`}>latency score · {formatMetricValue(item.latencyScore)}</span>
        <span className={`kv-chip-sf ${metric === 'cost' ? 'on' : ''}`}>cost score · {formatMetricValue(item.costScore)}</span>
        {item.costUsd != null && <span className="kv-chip-sf">cost · ${item.costUsd.toFixed(4)}</span>}
        {item.loopCount != null && <span className="kv-chip-sf">loops · {item.loopCount}</span>}
        {item.toolCallCount != null && <span className="kv-chip-sf">tools · {item.toolCallCount}</span>}
        {item.latencyMs != null && <span className="kv-chip-sf">latency · {item.latencyMs}ms</span>}
        {item.scoreFormulaVersion && <span className="kv-chip-sf">formula · {item.scoreFormulaVersion}</span>}
      </div>

      {item.judgeRationale && (
        <>
          <button className="scn-result-disclosure" onClick={() => setShowRationale(v => !v)}>
            {showRationale ? '▾' : '▸'} judge rationale
          </button>
          {showRationale && <div className="scn-result-section">{item.judgeRationale}</div>}
        </>
      )}
      {item.agentFinalOutput && (
        <>
          <button className="scn-result-disclosure" onClick={() => setShowOutput(v => !v)}>
            {showOutput ? '▾' : '▸'} agent final output
          </button>
          {showOutput && <div className="scn-result-section mono">{item.agentFinalOutput}</div>}
        </>
      )}

      <div className="scn-result-actions">
        {item.rootTraceId && (
          <Link className="sf-mini-btn" to={`/traces?traceId=${encodeURIComponent(item.rootTraceId)}`}>
            View trace
          </Link>
        )}
        <button className="sf-mini-btn" onClick={onAnnotate}>Annotate</button>
        <button className="sf-mini-btn" onClick={onAnalyze}>Analyze</button>
      </div>
    </div>
  );
}

function RunEvalDialog({ agents, userId, onClose, onSuccess }: {
  agents: Record<string, unknown>[];
  userId: number;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [agentId, setAgentId] = useState('');
  const [starting, setStarting] = useState(false);

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const handleRun = async () => {
    if (!agentId) return;
    setStarting(true);
    try {
      await triggerEvalTask(agentId, userId);
      onSuccess();
    } catch {
      setStarting(false);
    }
  };

  return (
    <div className="sf-modal-scrim" onClick={onClose}>
      <div className="sf-modal" onClick={e => e.stopPropagation()} style={{ width: 'min(480px, 94vw)' }}>
        <div className="sf-modal-h">
          <h3>Run Eval</h3>
          <button className="sf-drawer-close" onClick={onClose}>{CLOSE_ICON}</button>
        </div>
        <div className="sf-modal-body">
          <div className="sf-modal-field">
            <label>Target agent</label>
            <Select
              value={agentId || undefined}
              onChange={(v) => setAgentId(v ?? '')}
              placeholder="Select agent…"
              style={{ width: '100%' }}
              options={agents.map((a) => ({
                value: String(a.id),
                label: String(a.name || `Agent #${a.id}`),
              }))}
            />
          </div>
        </div>
        <div className="sf-modal-f">
          <button className="btn-ghost-sf" onClick={onClose}>Cancel</button>
          <button className="btn-primary-sf" disabled={!agentId || starting} onClick={handleRun}>
            {starting ? 'Starting…' : <>{PLAY_ICON} Run eval</>}
          </button>
        </div>
      </div>
    </div>
  );
}

function CompareTasksModal({ taskIds, onClose }: { taskIds: string[]; onClose: () => void }) {
  const [metric, setMetric] = useState<EvalMetric>('composite');
  const { data, isLoading } = useQuery({
    queryKey: ['eval-task-compare', taskIds],
    queryFn: () => compareEvalTasks(taskIds).then(res => res.data),
    enabled: taskIds.length === 2,
  });

  return (
    <div className="sf-modal-scrim" onClick={onClose}>
      <div className="sf-modal sf-compare-modal" onClick={e => e.stopPropagation()}>
        <div className="sf-modal-h">
          <h3>Compare tasks</h3>
          <Select<EvalMetric>
            size="small"
            value={metric}
            onChange={setMetric}
            style={{ minWidth: 140, marginLeft: 'auto', marginRight: 12 }}
            options={METRIC_OPTIONS}
          />
          <button className="sf-drawer-close" onClick={onClose} aria-label="Close">{CLOSE_ICON}</button>
        </div>
        <div className="sf-modal-body">
          {isLoading ? (
            <div className="sf-empty-state">Loading compare view…</div>
          ) : !data ? (
            <div className="sf-empty-state">No compare data.</div>
          ) : (
            <>
              <div className="compare-summary-grid">
                {data.tasks.map(task => (
                  <div key={task.id} className="compare-summary-card">
                    <div className="compare-summary-title">{task.id}</div>
                    <div className="compare-summary-stats">
                      <span className="kv-chip-sf">{task.status}</span>
                      {task.compositeAvg != null && <span className="kv-chip-sf">{Math.round(task.compositeAvg)}%</span>}
                      {task.passCount != null && task.failCount != null && (
                        <span className="kv-chip-sf">{task.passCount}/{task.failCount}</span>
                      )}
                    </div>
                  </div>
                ))}
              </div>

              <div className="compare-table-wrap">
                <table className="compare-table">
                  <thead>
                    <tr>
                      <th>Scenario</th>
                      {taskIds.map(taskId => (
                        <th key={taskId}>{taskId}</th>
                      ))}
                      <th>Delta</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map(row => (
                      <tr key={row.scenarioId}>
                        <td className="mono-sm">{row.scenarioId}</td>
                        {taskIds.map(taskId => (
                          <td key={`${row.scenarioId}-${taskId}`}>
                            <CompareEntryCell
                              entry={row.entries.find(entry => entry.taskId === taskId) ?? null}
                              metric={metric}
                            />
                          </td>
                        ))}
                        <td>
                          <div className="compare-delta">
                            <span>{formatMetricValue(computeMetricDelta(row.entries, metric))}</span>
                            {row.outputDiffers && <span className="kv-chip-sf">output diff</span>}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function CompareEntryCell({ entry, metric }: { entry: EvalTaskCompareEntry | null; metric: EvalMetric }) {
  if (!entry) {
    return <div className="compare-entry-empty">—</div>;
  }
  const metricScore = getMetricValue(entry, metric);
  return (
    <div className="compare-entry-cell">
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 6 }}>
        <span className={`sess-status s-${entry.status === 'PASS' ? 'idle' : entry.status === 'TIMEOUT' ? 'waiting' : 'error'}`}>
          {entry.status}
        </span>
        <span className="kv-chip-sf">{formatMetricValue(metricScore)}</span>
        {entry.compositeScore != null && metric !== 'composite' && <span className="kv-chip-sf">composite · {formatMetricValue(entry.compositeScore)}</span>}
        {entry.attribution && <span className="kv-chip-sf">{entry.attribution}</span>}
      </div>
      <div className="compare-entry-meta">
        {entry.qualityScore != null && <span>quality {formatMetricValue(entry.qualityScore)}</span>}
        {entry.efficiencyScore != null && <span>efficiency {formatMetricValue(entry.efficiencyScore)}</span>}
        {entry.latencyScore != null && <span>latency {formatMetricValue(entry.latencyScore)}</span>}
        {entry.costScore != null && <span>cost {formatMetricValue(entry.costScore)}</span>}
        {entry.costUsd != null && <span>${entry.costUsd.toFixed(4)}</span>}
        {entry.latencyMs != null && <span>{entry.latencyMs}ms</span>}
        {entry.loopCount != null && <span>{entry.loopCount} loops</span>}
        {entry.toolCallCount != null && <span>{entry.toolCallCount} tools</span>}
      </div>
      {entry.rootTraceId && (
        <Link className="sf-mini-btn" to={`/traces?traceId=${encodeURIComponent(entry.rootTraceId)}`}>
          View trace
        </Link>
      )}
    </div>
  );
}

function AnnotationQueue({
  userId,
  seed,
  onSeedConsumed,
}: {
  userId: number;
  seed: EvalTaskItem | null;
  onSeedConsumed: () => void;
}) {
  const queryClient = useQueryClient();
  const [statusFilter, setStatusFilter] = useState<'pending' | 'applied' | 'all'>('pending');
  const [draft, setDraft] = useState<{ taskItemId: number; correctedScore?: number | null; correctedExpected?: string | null } | null>(null);

  useEffect(() => {
    if (!seed) return;
    setDraft({
      taskItemId: seed.id,
      correctedScore: seed.compositeScore ?? undefined,
      correctedExpected: '',
    });
    onSeedConsumed();
  }, [onSeedConsumed, seed]);

  const { data: annotations = [], isLoading } = useQuery({
    queryKey: ['eval-annotations', statusFilter],
    queryFn: () => getEvalAnnotations(statusFilter === 'all' ? undefined : statusFilter).then(res => res.data ?? []),
  });

  const create = async () => {
    if (!draft) return;
    await createEvalAnnotation({
      taskItemId: draft.taskItemId,
      annotatorId: userId,
      correctedScore: draft.correctedScore ?? null,
      correctedExpected: draft.correctedExpected ?? null,
    });
    setDraft(null);
    queryClient.invalidateQueries({ queryKey: ['eval-annotations'] });
  };

  const markApplied = async (annotation: EvalAnnotation) => {
    await updateEvalAnnotation(annotation.id, {
      status: 'applied',
      correctedScore: annotation.correctedScore ?? null,
      correctedExpected: annotation.correctedExpected ?? null,
    });
    queryClient.invalidateQueries({ queryKey: ['eval-annotations'] });
  };

  const createScenarioVersionFromAnnotation = async (annotation: EvalAnnotation) => {
    if (!annotation.scenarioId || annotation.scenarioSource !== 'db') {
      return;
    }
    await createEvalScenarioVersion(annotation.scenarioId, {
      oracleExpected: annotation.correctedExpected ?? undefined,
      status: 'active',
    });
    await updateEvalAnnotation(annotation.id, {
      status: 'applied',
      correctedScore: annotation.correctedScore ?? null,
      correctedExpected: annotation.correctedExpected ?? null,
    });
    queryClient.invalidateQueries({ queryKey: ['eval-annotations'] });
    queryClient.invalidateQueries({ queryKey: ['eval-dataset-scenarios'] });
  };

  return (
    <div className="annotation-queue">
      <div className="annotation-queue-head">
        <div>
          <h3>Annotation Queue</h3>
          <p>Capture human score corrections and expected-output fixes before versioning lands in M3g.</p>
        </div>
        <div className="annotation-filter-row">
          {(['pending', 'applied', 'all'] as const).map(status => (
            <button
              key={status}
              className={`sf-mini-btn ${statusFilter === status ? 'on' : ''}`}
              onClick={() => setStatusFilter(status)}
            >
              {status}
            </button>
          ))}
        </div>
      </div>

      {draft && (
        <div className="annotation-draft-card">
          <div className="annotation-draft-grid">
            <label>
              <span>Task item</span>
              <input value={String(draft.taskItemId)} disabled />
            </label>
            <label>
              <span>Corrected score</span>
              <input
                value={draft.correctedScore ?? ''}
                onChange={e => setDraft(prev => prev ? {
                  ...prev,
                  correctedScore: e.target.value === '' ? null : Number(e.target.value),
                } : prev)}
              />
            </label>
          </div>
          <label className="annotation-draft-textarea">
            <span>Corrected expected</span>
            <textarea
              rows={4}
              value={draft.correctedExpected ?? ''}
              onChange={e => setDraft(prev => prev ? { ...prev, correctedExpected: e.target.value } : prev)}
            />
          </label>
          <div className="annotation-draft-actions">
            <button className="btn-ghost-sf" onClick={() => setDraft(null)}>Cancel</button>
            <button className="btn-primary-sf" onClick={create}>Create annotation</button>
          </div>
        </div>
      )}

      {isLoading ? (
        <div className="sf-empty-state">Loading annotations…</div>
      ) : annotations.length === 0 ? (
        <div className="sf-empty-state">No annotations in this queue.</div>
      ) : (
        <div className="annotation-list">
          {annotations.map(annotation => (
            <div key={annotation.id} className="annotation-card">
              <div className="annotation-card-h">
                <div>
                  <div className="annotation-title mono-sm">
                    {annotation.taskId ?? 'task'} / {annotation.scenarioId ?? `item-${annotation.taskItemId}`}
                  </div>
                  <div className="annotation-meta">
                    <span className="kv-chip-sf">{annotation.status}</span>
                    {annotation.itemStatus && <span className="kv-chip-sf">item · {annotation.itemStatus}</span>}
                    {annotation.attribution && <span className="kv-chip-sf">{annotation.attribution}</span>}
                  </div>
                </div>
                {annotation.status === 'pending' && (
                  <div style={{ display: 'flex', gap: 8 }}>
                    {annotation.scenarioSource === 'db' && annotation.correctedExpected && (
                      <button
                        className="sf-mini-btn"
                        onClick={() => createScenarioVersionFromAnnotation(annotation)}
                      >
                        Create version
                      </button>
                    )}
                    <button className="sf-mini-btn" onClick={() => markApplied(annotation)}>Mark applied</button>
                  </div>
                )}
              </div>
              <div className="annotation-score-row">
                <span>Original {annotation.originalScore ?? '—'}</span>
                <span>→</span>
                <span>Corrected {annotation.correctedScore ?? '—'}</span>
              </div>
              {annotation.correctedExpected && (
                <pre className="annotation-pre">{annotation.correctedExpected}</pre>
              )}
              {annotation.judgeRationale && (
                <details>
                  <summary>Judge rationale</summary>
                  <pre className="annotation-pre">{annotation.judgeRationale}</pre>
                </details>
              )}
              {annotation.rootTraceId && (
                <Link className="sf-mini-btn" to={`/traces?traceId=${encodeURIComponent(annotation.rootTraceId)}`}>
                  View trace
                </Link>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TaskAnalysisSessionRow({ session }: { session: EvalTaskAnalysisSession }) {
  const navigate = useNavigate();
  const label =
    session.analysisType === 'run_overall'
      ? 'overall'
      : session.analysisType === 'run_case'
        ? `case · ${session.scenarioId ?? session.itemId ?? ''}`
        : session.analysisType;

  return (
    <button
      type="button"
      className="scn-recent-run"
      style={{
        width: '100%',
        background: 'var(--bg-surface)',
        border: '1px solid var(--border-1)',
        cursor: 'pointer',
        font: 'inherit',
        color: 'inherit',
        textAlign: 'left',
      }}
      onClick={() => {
        navigate(`/chat/${session.sessionId}`);
      }}
      title={`Open analysis session ${session.sessionId}`}
    >
      <div style={{ minWidth: 0 }}>
        <div className="rid" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {session.title || session.sessionId.slice(0, 8)}
        </div>
        <div style={{ fontSize: 11, color: 'var(--fg-4)', marginTop: 2 }}>
          {label} · {fmtTime(session.updatedAt ?? session.createdAt ?? undefined)}
        </div>
      </div>
      <span className={`sess-status s-${session.runtimeStatus === 'idle' ? 'idle' : session.runtimeStatus === 'running' ? 'running' : 'waiting'}`}>
        {session.runtimeStatus ?? '—'}
      </span>
    </button>
  );
}

export default Eval;
