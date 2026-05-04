import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from 'antd';
import {
  getEvalRuns, getEvalRun, triggerEvalRun, getAgents, extractList,
  type EvalDatasetScenario,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import ScenarioDraftPanel from '../components/ScenarioDraftPanel';
import DatasetBrowser from '../components/evals/DatasetBrowser';
import AnalyzeCaseModal from '../components/evals/AnalyzeCaseModal';
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
  raw: Record<string, unknown>;
}

interface ScenarioResult {
  scenarioId: string;
  name?: string;
  category?: string;
  split?: string;
  status: string;
  compositeScore?: number;
  errorMessage?: string;
  attribution?: string;
  judgeRationale?: string;
  agentFinalOutput?: string;
  task?: string;
  oracleType?: string;
  oracleExpected?: string;
  traceId?: string;
}

// Per-row live progress state populated from `eval_progress` WS events.
// Stored separately from the run row so we don't have to refetch the whole
// run detail just to update a progress bar.
interface ProgressState {
  passedCount: number;
  totalCount: number;
  currentScenarioName?: string;
  completed?: boolean;
}

function normalizeEval(raw: Record<string, unknown>, agents: Record<string, unknown>[]): EvalRow {
  const agentId = String(raw.agentDefinitionId || '');
  const agent = agents.find(a => String(a.id) === agentId);
  const passRate = Number(raw.overallPassRate || 0);
  const score = passRate / 100;
  const total = Number(raw.totalScenarios || 0);
  const passed = Number(raw.passedScenarios || 0);
  const failed = Number(raw.failedScenarios || total - passed);

  let status = 'fail';
  if (raw.status === 'RUNNING') status = 'warn';
  else if (score >= 0.9) status = 'pass';
  else if (score >= 0.7) status = 'warn';

  return {
    id: String(raw.id || raw.evalRunId || ''),
    name: agent ? String(agent.name || `Agent #${agentId}`) : `Eval ${String(raw.id || '').slice(0, 8)}`,
    suite: 'default',
    target: agent ? String(agent.name || agentId) : agentId,
    agentId,
    lastRun: fmtTime(String(raw.startedAt || raw.completedAt || '')),
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

function fmtTime(iso: string): string {
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

type TopTab = 'runs' | 'datasets';

const Eval: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [topTab, setTopTab] = useState<TopTab>('runs');
  const [q, setQ] = useState('');
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [open, setOpen] = useState<EvalRow | null>(null);
  const [drawerTab, setDrawerTab] = useState('cases');
  const [runDialog, setRunDialog] = useState(false);
  // EVAL-V2 M1: per-evalRunId live progress state, fed by `eval_progress` WS events.
  const [progressByRun, setProgressByRun] = useState<Record<string, ProgressState>>({});

  const { data: rawRuns = [] } = useQuery({
    queryKey: ['eval-runs'],
    queryFn: () => getEvalRuns().then(res => extractList<Record<string, unknown>>(res)),
    refetchInterval: (query) => {
      const data = query.state.data as Record<string, unknown>[] | undefined;
      return data?.some(r => r.status === 'RUNNING') ? 3000 : false;
    },
  });

  const { data: rawAgents = [] } = useQuery({
    queryKey: ['agents'],
    queryFn: () => getAgents().then(res => extractList<Record<string, unknown>>(res)),
  });

  const all = useMemo<EvalRow[]>(() => rawRuns.map(r => normalizeEval(r, rawAgents)), [rawRuns, rawAgents]);

  // EVAL-V2 M1: WS subscription for live progress events. We attach to the
  // user-level WS channel (same channel used by SessionList for runtimeStatus
  // updates). Reconnect-after-disconnect intentionally has no replay logic;
  // when WS reconnects we just invalidate the runs list so any in-flight
  // RUNNING row gets a fresh GET /eval/runs/{id} via the existing 3s poll.
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
            // pulled from the freshly persisted EvalRunEntity.
            queryClient.invalidateQueries({ queryKey: ['eval-runs'] });
            queryClient.invalidateQueries({ queryKey: ['eval-run', evalRunId] });
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
            <p className="agents-head-sub">{rows.length} eval runs · {all.reduce((a, e) => a + e.cases, 0)} cases total</p>
          </div>
          <div className="agents-head-actions">
            <button className="btn-ghost-sf" onClick={() => setRunDialog(true)}>{PLAY_ICON} Run eval</button>
          </div>
        </header>

        <div className="eval-toptab-row" role="tablist">
          {(['runs', 'datasets'] as TopTab[]).map(t => (
            <button
              key={t}
              role="tab"
              aria-selected={topTab === t}
              className={`eval-toptab ${topTab === t ? 'on' : ''}`}
              onClick={() => setTopTab(t)}
            >
              {t === 'runs' ? 'Runs' : 'Datasets'}
              {t === 'runs' && <span className="eval-toptab-count">{all.length}</span>}
            </button>
          ))}
        </div>

        <div className="agents-body">
          {topTab === 'datasets' ? (
            <DatasetBrowser agents={rawAgents} userId={userId} />
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
            queryClient.invalidateQueries({ queryKey: ['eval-runs'] });
          }}
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
  agents: Record<string, unknown>[];
  userId: number;
}

function EvalDrawer({ evalRow, tab, setTab, onClose, onRun, agents, userId }: EvalDrawerProps) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const { data: runDetail } = useQuery({
    queryKey: ['eval-run', evalRow.id],
    queryFn: () => getEvalRun(evalRow.id).then(res => res.data),
    enabled: !!evalRow.id,
  });

  const scenarios: ScenarioResult[] = (runDetail as Record<string, unknown>)?.scenarioResults as ScenarioResult[] ?? [];
  const [analyzing, setAnalyzing] = useState<{ scenario: ScenarioResult; ctx: { evalRunId: string; compositeScore?: number; attribution?: string } } | null>(null);

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
              {Boolean((runDetail as Record<string, unknown>)?.errorMessage) && (
                <div style={{ marginBottom: 12, padding: '10px 14px', background: 'var(--error-bg, #2a1010)', border: '1px solid var(--error-border, #5c2020)', borderRadius: 6, fontSize: 12, color: 'var(--error-fg, #d97b5c)', fontFamily: 'var(--font-mono)' }}>
                  {String((runDetail as Record<string, unknown>).errorMessage)}
                </div>
              )}
              <div className="sf-section-h" style={{ marginBottom: 10 }}>
                {scenarios.length} scenarios
              </div>
              {scenarios.length === 0 ? (
                <div className="sf-empty-state">No scenario results available.</div>
              ) : (
                <div className="scn-result-list">
                  {scenarios.map(s => (
                    <ScenarioResultCard
                      key={s.scenarioId}
                      scenario={s}
                      onAnalyze={() => setAnalyzing({
                        scenario: s,
                        ctx: {
                          evalRunId: evalRow.id,
                          compositeScore: s.compositeScore,
                          attribution: s.attribution,
                        },
                      })}
                    />
                  ))}
                </div>
              )}
              {Array.isArray((runDetail as Record<string, unknown>)?.improvementSuggestions) &&
                ((runDetail as Record<string, unknown>).improvementSuggestions as unknown[]).length > 0 && (
                <div style={{ marginTop: 20 }}>
                  <div className="sf-section-h" style={{ marginBottom: 8 }}>Improvement Suggestions</div>
                  <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12, color: 'var(--fg-3)', lineHeight: 1.7 }}>
                    {((runDetail as Record<string, unknown>).improvementSuggestions as (string | Record<string, unknown>)[]).map((s, i) => (
                      <li key={i}>{typeof s === 'object' && s !== null ? String(s.suggestion ?? s.category ?? JSON.stringify(s)) : s}</li>
                    ))}
                  </ul>
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
          // The Cases tab works against scenarios from the run's
          // scenarioResults json, which carry id+name+task; reuse the same
          // dataset shape so the Analyze modal's prompt builder can read
          // sourceSessionId etc. from the EvalDatasetScenario contract.
          scenario={scenarioResultToDataset(analyzing.scenario, evalRow.agentId)}
          agents={agents}
          userId={userId}
          context={analyzing.ctx}
          onClose={() => setAnalyzing(null)}
        />
      )}
    </>
  );
}

// Adapt a per-run ScenarioResult (lives in scenarioResults jsonb) to the
// EvalDatasetScenario shape that AnalyzeCaseModal expects. The two share
// most fields but ScenarioResult lacks `agentId`/`createdAt`/`status`
// (case status ≠ scenario status), so we synthesize sensible defaults.
function scenarioResultToDataset(s: ScenarioResult, agentId: string): EvalDatasetScenario {
  return {
    id: s.scenarioId,
    agentId,
    name: s.name ?? s.scenarioId,
    category: s.category ?? 'session_derived',
    split: s.split ?? 'held_out',
    task: s.task ?? '',
    oracleType: s.oracleType ?? 'llm_judge',
    oracleExpected: s.oracleExpected,
    status: 'active',
    createdAt: '',
  };
}

interface ScenarioResultCardProps {
  scenario: ScenarioResult;
  onAnalyze: () => void;
}

function ScenarioResultCard({ scenario, onAnalyze }: ScenarioResultCardProps) {
  const [showRationale, setShowRationale] = useState(false);
  const [showOutput, setShowOutput] = useState(false);
  const [showTask, setShowTask] = useState(false);

  const score = scenario.compositeScore ?? 0;
  const score01 = score / 100;
  const tier = score >= 80 ? 'pass' : score >= 60 ? 'warn' : 'fail';

  const attribution = scenario.attribution ?? 'NONE';
  const isFailedAttr = attribution !== 'NONE';

  return (
    <div className={`scn-result-card s-${tier}`}>
      <div className="scn-result-h">
        <div className="scn-result-h-l">
          <span className="scn-result-name">{scenario.name || scenario.scenarioId}</span>
          <span className={`sess-status s-${scenario.status === 'PASS' ? 'idle' : scenario.status === 'TIMEOUT' ? 'waiting' : 'error'}`}>
            {scenario.status}
          </span>
        </div>
        <div className="scn-result-score" style={{ color: scoreColor(score01) }}>
          {scenario.compositeScore != null ? Math.round(score) : '—'}<em>%</em>
        </div>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 8 }}>
        {scenario.category && <span className="kv-chip-sf">{scenario.category}</span>}
        {scenario.split && <span className="kv-chip-sf">{scenario.split}</span>}
        {/* PRD §2: attribution badge only when meaningful (non-NONE). For
            passing scenarios attribution is always NONE — rendering an empty
            "none" pill there is visually noisy and was r3-FE-2 review feedback. */}
        {isFailedAttr && (
          <span className="scn-result-attr attr-fail">
            {attribution.toLowerCase().replace(/_/g, ' ')}
          </span>
        )}
        {scenario.traceId && (
          <span className="kv-chip-sf" title={scenario.traceId}>
            trace · {scenario.traceId.slice(0, 8)}
          </span>
        )}
      </div>

      {scenario.errorMessage && (
        <div className="scn-result-section" style={{ color: 'var(--error-fg, #d97b5c)' }}>
          {scenario.errorMessage}
        </div>
      )}

      {scenario.task && (
        <>
          <button className="scn-result-disclosure" onClick={() => setShowTask(v => !v)}>
            {showTask ? '▾' : '▸'} task
          </button>
          {showTask && <div className="scn-result-section">{scenario.task}</div>}
        </>
      )}
      {scenario.judgeRationale && (
        <>
          <button className="scn-result-disclosure" onClick={() => setShowRationale(v => !v)}>
            {showRationale ? '▾' : '▸'} judge rationale
          </button>
          {showRationale && <div className="scn-result-section">{scenario.judgeRationale}</div>}
        </>
      )}
      {scenario.agentFinalOutput && (
        <>
          <button className="scn-result-disclosure" onClick={() => setShowOutput(v => !v)}>
            {showOutput ? '▾' : '▸'} agent final output
          </button>
          {showOutput && <div className="scn-result-section mono">{scenario.agentFinalOutput}</div>}
        </>
      )}

      <div className="scn-result-actions">
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
      await triggerEvalRun(agentId, userId);
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

export default Eval;
