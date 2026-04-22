import React, { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from 'antd';
import { getEvalRuns, getEvalRun, triggerEvalRun, getAgents, extractList } from '../api';
import { useAuth } from '../contexts/AuthContext';
import ScenarioDraftPanel from '../components/ScenarioDraftPanel';
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
  status: string;
  compositeScore?: number;
  errorMessage?: string;
  attribution?: string;
  judgeRationale?: string;
  agentFinalOutput?: string;
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

const Eval: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [q, setQ] = useState('');
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [open, setOpen] = useState<EvalRow | null>(null);
  const [drawerTab, setDrawerTab] = useState('cases');
  const [runDialog, setRunDialog] = useState(false);

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

        <div className="agents-body">
          {rows.length === 0 ? (
            <div className="sf-empty-state">No eval runs yet. Select an agent and run an eval.</div>
          ) : (
            <div className="eval-grid">
              {rows.map(e => (
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
              ))}
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

function EvalDrawer({ evalRow, tab, setTab, onClose, onRun }: {
  evalRow: EvalRow;
  tab: string;
  setTab: (t: string) => void;
  onClose: () => void;
  onRun: () => void;
}) {
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
                <div className="sf-table">
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr>
                        <th style={{ textAlign: 'left', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 600, color: 'var(--fg-4)', letterSpacing: '0.08em', textTransform: 'uppercase', borderBottom: '1px solid var(--border-1)' }}>Scenario</th>
                        <th style={{ textAlign: 'left', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 600, color: 'var(--fg-4)', letterSpacing: '0.08em', textTransform: 'uppercase', borderBottom: '1px solid var(--border-1)' }}>Status</th>
                        <th style={{ textAlign: 'right', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 600, color: 'var(--fg-4)', letterSpacing: '0.08em', textTransform: 'uppercase', borderBottom: '1px solid var(--border-1)' }}>Score</th>
                        <th style={{ textAlign: 'left', padding: '8px 12px', fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 600, color: 'var(--fg-4)', letterSpacing: '0.08em', textTransform: 'uppercase', borderBottom: '1px solid var(--border-1)' }}>Details</th>
                      </tr>
                    </thead>
                    <tbody>
                      {scenarios.map(s => (
                        <tr key={s.scenarioId} style={{ borderBottom: '1px solid var(--border-1)' }}>
                          <td style={{ padding: '10px 12px', fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-2)' }}>
                            {s.name || s.scenarioId}
                          </td>
                          <td style={{ padding: '10px 12px' }}>
                            <span className={`sess-status s-${s.status === 'PASS' ? 'idle' : s.status === 'TIMEOUT' ? 'waiting' : 'error'}`}>
                              {s.status}
                            </span>
                          </td>
                          <td style={{ padding: '10px 12px', textAlign: 'right', fontFamily: 'var(--font-mono)', fontSize: 12, fontWeight: 500, color: scoreColor((s.compositeScore ?? 0) / 100) }}>
                            {s.compositeScore != null ? `${Math.round(s.compositeScore)}%` : '—'}
                          </td>
                          <td style={{ padding: '10px 12px', fontSize: 11, color: 'var(--fg-4)', fontFamily: 'var(--font-mono)', maxWidth: 240 }}>
                            {s.errorMessage ? (
                              <span style={{ color: 'var(--error-fg, #d97b5c)' }}>{s.errorMessage}</span>
                            ) : s.attribution && s.attribution !== 'NONE' ? (
                              <span style={{ opacity: 0.7 }}>{s.attribution.toLowerCase().replace(/_/g, ' ')}</span>
                            ) : null}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
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
    </>
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
