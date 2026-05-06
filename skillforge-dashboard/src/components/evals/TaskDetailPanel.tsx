import { useEffect, useState, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Select, message } from 'antd';
import {
  applyEvalTaskImprovement,
  getEvalTask,
  getEvalTaskAnalysisSessions,
  getEvalTaskItems,
  getEvalRuns, // Re-import legacy runs API
  type EvalTaskItem,
  type EvalTaskSummary,
} from '../../api';
import ScenarioDraftPanel from '../ScenarioDraftPanel';
import AnalyzeCaseModal, { type AnalyzeTarget } from './AnalyzeCaseModal';
import TaskItemCard from './TaskItemCard';
import { useNavigate } from 'react-router-dom';
import {
  CLOSE_ICON, PLAY_ICON, ARROW_ICON,
  scoreColor, scoreTier, fmtTime,
  METRIC_OPTIONS, type EvalMetric,
  type EvalRow,
} from './evalUtils';

/* ── Trend Chart Component (SVG) ── */
function TrendChart({ runs }: { runs: any[] }) {
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);
  
  if (runs.length < 2) return <div style={{ textAlign: 'center', color: 'var(--fg-4)', padding: 20 }}>Need at least 2 runs to show trend.</div>;

  const data = runs.slice().reverse().map((r, i) => ({
    index: i,
    score: r.avgOracleScore ?? r.overallPassRate ?? 0,
    date: new Date(r.startedAt || Date.now()).toLocaleDateString(),
    id: r.id
  }));

  const maxScore = 100;
  const height = 160;
  
  const points = data.map((d, i) => {
    const x = (i / (data.length - 1)) * 100;
    const y = height - (d.score / maxScore) * height;
    return `${x},${y}`;
  }).join(' ');

  return (
    <div style={{ position: 'relative', height: height + 40 }}>
      <svg viewBox={`0 0 100 ${height}`} preserveAspectRatio="none" style={{ width: '100%', height: height, overflow: 'visible' }}>
        {/* Grid lines */}
        {[0, 25, 50, 75, 100].map(pct => (
          <line key={pct} x1="0" y1={height - (pct/100)*height} x2="100" y2={height - (pct/100)*height} stroke="var(--border-1)" strokeWidth="0.5" strokeDasharray="2" />
        ))}
        
        {/* Area under curve */}
        <polygon points={`0,${height} ${points} 100,${height}`} fill="rgba(217, 99, 58, 0.05)" />
        
        {/* Line */}
        <polyline points={points} fill="none" stroke="var(--accent-primary, #d9633a)" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
        
        {/* Dots */}
        {data.map((d, i) => (
          <circle 
            key={i}
            cx={(i / (data.length - 1)) * 100}
            cy={height - (d.score / maxScore) * height}
            r="1.5"
            fill="var(--bg-surface)"
            stroke="var(--accent-primary, #d9633a)"
            strokeWidth="1"
            vectorEffect="non-scaling-stroke"
            style={{ transition: 'r 0.2s', cursor: 'pointer' }}
            onMouseEnter={() => setHoveredIndex(i)}
            onMouseLeave={() => setHoveredIndex(null)}
          />
        ))}
      </svg>
      
      {/* Tooltip */}
      {hoveredIndex !== null && data[hoveredIndex] && (
        <div style={{
          position: 'absolute',
          left: `${(hoveredIndex / (data.length - 1)) * 100}%`,
          top: 0,
          transform: 'translateX(-50%)',
          background: 'var(--bg-surface)',
          border: '1px solid var(--border-2)',
          padding: '8px 12px',
          borderRadius: 6,
          boxShadow: 'var(--shadow-2)',
          zIndex: 10,
          fontSize: 12,
          pointerEvents: 'none',
          whiteSpace: 'nowrap'
        }}>
          <div style={{ fontWeight: 600, color: 'var(--fg-1)' }}>Score: {data[hoveredIndex].score.toFixed(1)}</div>
          <div style={{ color: 'var(--fg-3)' }}>{data[hoveredIndex].date}</div>
        </div>
      )}
    </div>
  );
}

/* ── Score breakdown bar ── */
function ScoreBar({ label, value }: { label: string; value: number | null }) {
  if (value == null) return null;
  const pct = Math.round(value);
  return (
    <div className="tdp-score-bar">
      <span className="tdp-score-bar-label">{label}</span>
      <div className="tdp-score-bar-track">
        <div className="tdp-score-bar-fill" style={{ width: `${pct}%`, background: scoreColor(value / 100) }} />
      </div>
      <span className="tdp-score-bar-val" style={{ color: scoreColor(value / 100) }}>{pct}%</span>
    </div>
  );
}

/* ── TaskAnalysisSessionRow ── */
function TaskAnalysisSessionRow({ session }: { session: { sessionId: string; title?: string; analysisType?: string; scenarioId?: string; itemId?: number; runtimeStatus?: string; updatedAt?: string; createdAt?: string } }) {
  const navigate = useNavigate();
  const label =
    session.analysisType === 'run_overall' ? 'overall'
      : session.analysisType === 'run_case' ? `case · ${session.scenarioId ?? session.itemId ?? ''}`
        : session.analysisType ?? '';

  return (
    <button
      type="button"
      className="scn-recent-run"
      style={{ width: '100%', background: 'var(--bg-surface)', border: '1px solid var(--border-1)', cursor: 'pointer', font: 'inherit', color: 'inherit', textAlign: 'left' }}
      onClick={() => navigate(`/chat/${session.sessionId}`)}
      title={`Open analysis session ${session.sessionId}`}
    >
      <div style={{ minWidth: 0 }}>
        <div className="rid" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
          {session.title || session.sessionId.slice(0, 8)}
        </div>
        <div style={{ fontSize: 11, color: 'var(--fg-4)', marginTop: 2 }}>
          {label} · {fmtTime(session.updatedAt ?? session.createdAt)}
        </div>
      </div>
      <span className={`sess-status s-${session.runtimeStatus === 'idle' ? 'idle' : session.runtimeStatus === 'running' ? 'running' : 'waiting'}`}>
        {session.runtimeStatus ?? '—'}
      </span>
    </button>
  );
}

/* ── Main Component ── */
interface TaskDetailPanelProps {
  evalRow: EvalRow;
  onClose: () => void;
  onRun: () => void;
  onOpenAnnotations: (item: EvalTaskItem) => void;
  agents: Record<string, unknown>[];
  userId: number;
}

type DetailTab = 'cases' | 'history' | 'scenarios' | 'config';

export default function TaskDetailPanel({ evalRow, onClose, onRun, onOpenAnnotations, agents, userId }: TaskDetailPanelProps) {
  const queryClient = useQueryClient();
  const [metric, setMetric] = useState<EvalMetric>('composite');
  const [tab, setTab] = useState<DetailTab>('cases');
  const [caseFilter, setCaseFilter] = useState<'all' | 'failed' | 'attributed'>('all');
  const [applyingImprovement, setApplyingImprovement] = useState(false);
  const [analyzing, setAnalyzing] = useState<AnalyzeTarget | null>(null);

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

  // Fetch legacy runs for history tab
  const { data: allRuns = [], isLoading: runsLoading, error: runsError } = useQuery({
    queryKey: ['eval-runs'],
    queryFn: async () => {
      console.log('[TaskDetailPanel] Fetching eval runs...');
      try {
        const res = await getEvalRuns();
        console.log('[TaskDetailPanel] Eval runs response:', res.data);
        return res.data ?? [];
      } catch (e) {
        console.error('[TaskDetailPanel] Failed to fetch eval runs:', e);
        return [];
      }
    },
    staleTime: 0, // Always refetch when component mounts to ensure fresh data
  });

  // Filter runs related to this task's agent
  const historyRuns = useMemo(() => {
    if (!evalRow.agentId) return [];
    const targetId = String(evalRow.agentId);
    
    const filtered = (allRuns as any[]).filter((r: any) => {
      const runAgentId = String(r.agentDefinitionId || r.agentId);
      return runAgentId === targetId;
    });
    
    return filtered;
  }, [allRuns, evalRow.agentId]);

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

  const filteredItems = taskItems.filter(item => {
    if (caseFilter === 'failed') return item.status !== 'PASS';
    if (caseFilter === 'attributed') return item.attribution && item.attribution !== 'NONE';
    return true;
  });

  const handleApplyImprovement = async () => {
    if (!runDetail?.id || !runDetail.improvementSuggestion || applyingImprovement) return;
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

  const detailTabs: Array<{ id: DetailTab; label: string }> = [
    { id: 'cases', label: `Cases (${taskItems.length})` },
    { id: 'history', label: 'History' },
    { id: 'scenarios', label: 'Scenarios' },
    { id: 'config', label: 'Config' },
  ];

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer sf-drawer-wide" role="dialog">
        {/* ── Sticky header ── */}
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div>
              <h2 className="sf-drawer-title">{evalRow.name}</h2>
              <p className="sf-drawer-subtitle">suite · {evalRow.suite} → target · {evalRow.target}</p>
            </div>
            <div className="sf-drawer-actions">
              {runDetail && (
                <button className="btn-ghost-sf" onClick={() => setAnalyzing({ kind: 'task', task: runDetail })}>
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
          {detailTabs.map(t => (
            <button key={t.id} className={`sf-drawer-tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>

        {/* ── Body ── */}
        <div className="sf-drawer-body">
          {tab === 'cases' && (
            <div className="tdp-layout">
              {/* ── Left: Score + Attribution + Improvement ── */}
              <div className="tdp-sidebar">
                {/* Score breakdown */}
                <div className="tdp-section">
                  <h4 className="tdp-section-h">Score Breakdown</h4>
                  <ScoreBar label="Composite" value={runDetail?.compositeAvg ?? evalRow.score * 100} />
                  <ScoreBar label="Quality" value={runDetail?.qualityAvg ?? null} />
                  <ScoreBar label="Efficiency" value={runDetail?.efficiencyAvg ?? null} />
                  <ScoreBar label="Latency" value={runDetail?.latencyAvg ?? null} />
                  <ScoreBar label="Cost" value={runDetail?.costAvg ?? null} />
                </div>

                {/* Attribution */}
                {runDetail?.attributionSummary && (
                  <div className="tdp-section">
                    <h4 className="tdp-section-h">Attribution</h4>
                    <pre className="tdp-pre">{runDetail.attributionSummary}</pre>
                  </div>
                )}

                {/* Improvement suggestion — accent CTA */}
                {runDetail?.improvementSuggestion && (
                  <div className="tdp-section tdp-improvement">
                    <h4 className="tdp-section-h">Improvement Suggestion</h4>
                    <pre className="tdp-pre">{runDetail.improvementSuggestion}</pre>
                    <button
                      className="btn-primary-sf tdp-apply-btn"
                      disabled={applyingImprovement}
                      onClick={handleApplyImprovement}
                    >
                      {applyingImprovement ? 'Applying…' : <>{ARROW_ICON} Apply Suggestion</>}
                    </button>
                  </div>
                )}

                {/* Analysis sessions */}
                {taskAnalysisSessions.length > 0 && (
                  <div className="tdp-section">
                    <h4 className="tdp-section-h">Analysis Sessions ({taskAnalysisSessions.length})</h4>
                    <div style={{ display: 'grid', gap: 6 }}>
                      {taskAnalysisSessions.map(session => (
                        <TaskAnalysisSessionRow key={session.sessionId} session={session} />
                      ))}
                    </div>
                  </div>
                )}
              </div>

              {/* ── Right: Per-case results ── */}
              <div className="tdp-main">
                {Boolean(runDetail?.errorMessage) && (
                  <div style={{ marginBottom: 12, padding: '10px 14px', background: 'var(--color-error-bg)', border: '1px solid var(--color-error-border)', borderRadius: 6, fontSize: 12, color: 'var(--color-error)', fontFamily: 'var(--font-mono)' }}>
                    {String(runDetail?.errorMessage)}
                  </div>
                )}

                {/* Filter bar */}
                <div className="tdp-case-toolbar">
                  <div className="tdp-case-filters">
                    {(['all', 'failed', 'attributed'] as const).map(f => (
                      <button
                        key={f}
                        className={`sf-mini-btn ${caseFilter === f ? 'on' : ''}`}
                        onClick={() => setCaseFilter(f)}
                      >
                        {f === 'all' ? 'All' : f === 'failed' ? 'Failed' : 'Attributed'}
                        {f === 'failed' && <span className="tdp-filter-count">{taskItems.filter(i => i.status !== 'PASS').length}</span>}
                        {f === 'attributed' && <span className="tdp-filter-count">{taskItems.filter(i => i.attribution && i.attribution !== 'NONE').length}</span>}
                      </button>
                    ))}
                  </div>
                  <Select<EvalMetric>
                    size="small"
                    value={metric}
                    onChange={setMetric}
                    style={{ minWidth: 130 }}
                    options={METRIC_OPTIONS}
                  />
                </div>

                {filteredItems.length === 0 ? (
                  <div className="sf-empty-state">
                    {caseFilter === 'all' ? 'No task items available.' : `No ${caseFilter} cases.`}
                  </div>
                ) : (
                  <div className="scn-result-list">
                    {filteredItems.map(item => (
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
              </div>
            </div>
          )}

          {tab === 'history' && (
            <div className="history-chart">
              {runsLoading ? (
                <div className="sf-empty-state">Loading history...</div>
              ) : historyRuns.length === 0 ? (
                <div className="tdp-section" style={{ minHeight: '200px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', textAlign: 'center' }}>
                  <div style={{ fontSize: '48px', marginBottom: '16px', opacity: 0.3 }}>📈</div>
                  <div style={{ fontSize: '14px', color: 'var(--fg-3)' }}>
                    No historical runs found for this agent yet.
                  </div>
                </div>
              ) : (
                <>
                  {/* KPI Cards */}
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginBottom: 16 }}>
                    {[
                      { label: 'Avg Score', val: (historyRuns.reduce((a, b) => a + (b.avgOracleScore || 0), 0) / historyRuns.length).toFixed(1), color: 'var(--fg-1)' },
                      { label: 'Best Run', val: Math.max(...historyRuns.map(r => r.avgOracleScore || 0)).toFixed(1), color: 'var(--color-ok)' },
                      { label: 'Total Runs', val: historyRuns.length, color: 'var(--accent-primary, #d9633a)' },
                    ].map((kpi, i) => (
                      <div key={i} className="tdp-section" style={{ padding: 12, textAlign: 'center' }}>
                        <div style={{ fontSize: 10, color: 'var(--fg-4)', textTransform: 'uppercase', letterSpacing: 1 }}>{kpi.label}</div>
                        <div style={{ fontSize: 20, fontWeight: 700, color: kpi.color, marginTop: 4 }}>{kpi.val}</div>
                      </div>
                    ))}
                  </div>

                  {/* Interactive Trend Chart */}
                  <div className="tdp-section" style={{ padding: 20, marginBottom: 16 }}>
                    <h4 className="tdp-section-h">Performance Trend</h4>
                    <TrendChart runs={historyRuns.slice(0, 15)} />
                  </div>

                  {/* Detailed List */}
                  <div className="tdp-section">
                    <h4 className="tdp-section-h">Run Details</h4>
                    <div style={{ display: 'grid', gap: 8 }}>
                      {historyRuns.slice(0, 10).map((run) => {
                        const score = run.avgOracleScore ?? run.overallPassRate ?? 0;
                        const scoreColorVal = score >= 80 ? 'var(--color-ok)' : score >= 60 ? 'var(--color-warn)' : 'var(--color-err)';
                        return (
                          <div key={run.id} className="history-run-card">
                            <div className="history-run-info">
                              <span className="history-run-id">Run #{run.id.slice(0, 8)}</span>
                              <span className="history-run-time">
                                {run.startedAt ? new Date(run.startedAt).toLocaleDateString() : 'N/A'}
                              </span>
                            </div>
                            <div className="history-run-metrics">
                              <div className="history-run-metric">
                                <div className="history-run-metric-label">Score</div>
                                <div className="history-run-metric-value" style={{ color: scoreColorVal }}>{score.toFixed(1)}</div>
                              </div>
                              <span className={`history-run-status ${run.status === 'COMPLETED' ? 'success' : 'warning'}`}>
                                {run.status}
                              </span>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                </>
              )}
            </div>
          )}

          {tab === 'scenarios' && <ScenarioDraftPanel agentId={evalRow.agentId} />}

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
