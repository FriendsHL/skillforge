import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Select, message } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import {
  compareEvalTasks,
  getEvalAnnotations,
  getEvalTasks,
  getAgents,
  extractList,
  type EvalAnnotation,
  type EvalTaskItem,
  type EvalTaskSummary,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import DatasetBrowser from '../components/evals/DatasetBrowser';
import EvalOverview from '../components/evals/EvalOverview';
import TaskDetailPanel from '../components/evals/TaskDetailPanel';
import RunEvalDialog from '../components/evals/RunEvalDialog';
import CompareTasksModal from '../components/evals/CompareTasksModal';
import AnnotationQueue from '../components/evals/AnnotationQueue';
import { normalizeEval, scoreColor, fmtTime, PLAY_ICON, ARROW_ICON, type EvalRow, type ProgressState } from '../components/evals/evalUtils';
import '../components/evals/evals.css';

type TopTab = 'overview' | 'tasks' | 'datasets' | 'review';

const TAB_LABELS: Array<{ id: TopTab; label: string }> = [
  { id: 'overview', label: 'Overview' },
  { id: 'tasks', label: 'Tasks' },
  { id: 'datasets', label: 'Dataset' },
  { id: 'review', label: 'Review Queue' },
];

export default function Eval() {
  const { userId } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const [topTab, setTopTab] = useState<TopTab>('overview');
  const [evalDetail, setEvalDetail] = useState<EvalRow | null>(null);
  const [runDialog, setRunDialog] = useState(false);
  const [compareSelection, setCompareSelection] = useState<string[]>([]);
  const [compareOpen, setCompareOpen] = useState(false);
  const [annotationSeed, setAnnotationSeed] = useState<EvalTaskItem | null>(null);

  /* ── Agents ── */
  const { data: agentsResp } = useQuery({
    queryKey: ['agents'],
    queryFn: () => getAgents(),
  });
  const rawAgents = useMemo(() => (agentsResp ? extractList(agentsResp) : []), [agentsResp]);

  /* ── Tasks ── */
  const { data: evalsData } = useQuery({
    queryKey: ['eval-tasks'],
    queryFn: () => getEvalTasks().then(r => r.data ?? []),
    refetchInterval: 10000,
  });

  const rows: EvalRow[] = useMemo(() => {
    const list = Array.isArray(evalsData) ? evalsData as EvalTaskSummary[] : [];
    return list.map(r => normalizeEval(r as unknown as Record<string, unknown>, rawAgents));
  }, [evalsData, rawAgents]);

  /* ── Pending annotations count ── */
  const { data: pendingAnnotationsData } = useQuery({
    queryKey: ['eval-annotations', 'pending'],
    queryFn: () => getEvalAnnotations('pending').then(res => res.data ?? []),
  });
  const pendingCount = pendingAnnotationsData?.length ?? 0;

  /* ── WS live progress ── */
  const [progressByRun, setProgressByRun] = useState<Record<string, ProgressState>>({});
  const wsRef = useRef<WebSocket | null>(null);
  const unmountedRef = useRef(false);

  useEffect(() => {
    unmountedRef.current = false;
    try {
      const loc = window.location;
      const wsProto = loc.protocol === 'https:' ? 'wss' : 'ws';
      const ws = new WebSocket(`${wsProto}://${loc.host}/ws/eval-progress`);
      wsRef.current = ws;
      ws.onmessage = (evt) => {
        try {
          const msg = JSON.parse(evt.data);
          if (!msg?.evalRunId) return;
          const evalRunId = String(msg.evalRunId);
          setProgressByRun(prev => {
            const prior = prev[evalRunId] ?? { passedCount: 0, totalCount: 0 };
            return {
              ...prev,
              [evalRunId]: {
                passedCount: typeof msg.passedCount === 'number' ? msg.passedCount : prior.passedCount,
                totalCount: typeof msg.totalCount === 'number' ? msg.totalCount : prior.totalCount,
                completed: typeof msg.completed === 'boolean' ? msg.completed : prior.completed,
                currentScenarioName: typeof msg.scenarioName === 'string' ? msg.scenarioName : prior.currentScenarioName,
              },
            };
          });
        } catch { /* swallow */ }
      };
      ws.onerror = () => {};
      ws.onclose = () => { if (wsRef.current === ws) wsRef.current = null; };
    } catch { /* WS unavailable */ }
    return () => {
      unmountedRef.current = true;
      const cur = wsRef.current;
      if (cur) { try { cur.close(); } catch {} wsRef.current = null; }
    };
  }, []);

  const toggleCompare = (id: string) => {
    setCompareSelection(prev => {
      if (prev.includes(id)) return prev.filter(x => x !== id);
      if (prev.length >= 2) return [prev[1], id];
      return [...prev, id];
    });
  };

  return (
    <div className="eval-page">
      {/* ── Page header ── */}
      <header className="eval-page-header">
        <div className="eval-page-header-l">
          <h1 className="eval-page-title">Eval</h1>
          <p className="eval-page-sub">评测集管理 · 任务执行 · 评分归因 · 迭代改进</p>
        </div>
        <div className="eval-page-header-r">
          {topTab === 'tasks' && compareSelection.length > 0 && (
            <div className="eval-compare-bar">
              <span className="eval-compare-label">已选 {compareSelection.length}/2</span>
              <button
                className="eval-btn"
                disabled={compareSelection.length !== 2}
                onClick={() => setCompareOpen(true)}
              >
                Compare
              </button>
              <button className="eval-btn-ghost" onClick={() => setCompareSelection([])}>Clear</button>
            </div>
          )}
          <button className="eval-btn-primary" onClick={() => setRunDialog(true)}>
            {PLAY_ICON} Run Eval
          </button>
        </div>
      </header>

      {/* ── Tabs ── */}
      <nav className="eval-tabs" role="tablist">
        {TAB_LABELS.map(t => (
          <button
            key={t.id}
            className={`eval-tab ${topTab === t.id ? 'on' : ''}`}
            onClick={() => setTopTab(t.id)}
            role="tab"
            aria-selected={topTab === t.id}
          >
            {t.label}
            {t.id === 'review' && pendingCount > 0 && (
              <span className="eval-tab-badge">{pendingCount}</span>
            )}
          </button>
        ))}
      </nav>

      {/* ── Content ── */}
      <main className="eval-content">
        {topTab === 'overview' && (
          <EvalOverview
            rows={rows}
            progressByRun={progressByRun}
            pendingAnnotations={pendingCount}
            totalDatasets={0}
            onRunEval={() => setRunDialog(true)}
            onOpenTask={setEvalDetail}
            onSwitchTab={setTopTab}
          />
        )}

        {topTab === 'tasks' && (
          <div className="eval-task-grid">
            {rows.length === 0 ? (
              <div className="eval-empty">
                <div className="eval-empty-icon">📊</div>
                <div className="eval-empty-title">No eval tasks yet</div>
                <div className="eval-empty-desc">Run your first eval to see results here.</div>
                <button className="eval-btn-primary" onClick={() => setRunDialog(true)} style={{ marginTop: 16 }}>
                  {PLAY_ICON} Run Eval
                </button>
              </div>
            ) : (
              rows.map(row => {
                const live = progressByRun[row.id];
                const isRunning = String(row.raw.status) === 'RUNNING';
                const isSelected = compareSelection.includes(row.id);
                return (
                  <div
                    key={row.id}
                    className={`eval-task-card ${isSelected ? 'selected' : ''}`}
                    onClick={() => setEvalDetail(row)}
                  >
                    {/* Top: status dot + name + compare toggle */}
                    <div className="eval-task-card-head">
                      <span className={`eval-dot s-${row.status}`} />
                      <span className="eval-task-card-name">{row.name}</span>
                      {compareSelection.length > 0 && (
                        <button
                          className={`eval-compare-toggle ${isSelected ? 'on' : ''}`}
                          onClick={e => { e.stopPropagation(); toggleCompare(row.id); }}
                        >
                          {isSelected ? '✓' : '+'}
                        </button>
                      )}
                    </div>

                    {/* Score — dominant visual element */}
                    <div className="eval-task-card-score">
                      <span className="eval-task-card-score-num" style={{ color: scoreColor(row.score) }}>
                        {(row.score * 100).toFixed(1)}
                      </span>
                      <span className="eval-task-card-score-unit">%</span>
                    </div>

                    {/* Pass/fail bar */}
                    <div className="eval-task-card-bar-track">
                      <div
                        className="eval-task-card-bar-fill"
                        style={{
                          width: `${row.cases > 0 ? Math.round((row.pass / row.cases) * 100) : 0}%`,
                          background: scoreColor(row.pass / Math.max(row.cases, 1)),
                        }}
                      />
                    </div>

                    {/* Meta row */}
                    <div className="eval-task-card-meta">
                      <span>{row.pass}/{row.cases} passed</span>
                      <span className="eval-task-card-dot">·</span>
                      <span>{row.lastRun}</span>
                    </div>

                    {/* Running progress */}
                    {isRunning && live && (
                      <div className="eval-task-card-progress">
                        <div className="eval-task-card-progress-bar">
                          <div
                            className="eval-task-card-progress-fill"
                            style={{ width: `${live.totalCount > 0 ? Math.round((live.passedCount / live.totalCount) * 100) : 0}%` }}
                          />
                        </div>
                        <span className="eval-task-card-progress-text">
                          {live.passedCount}/{live.totalCount}
                          {live.currentScenarioName && ` · ${live.currentScenarioName}`}
                        </span>
                      </div>
                    )}

                    {/* Target chip */}
                    <div className="eval-task-card-foot">
                      <span className="eval-chip">{row.target}</span>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        )}

        {topTab === 'datasets' && <DatasetBrowser agents={rawAgents} userId={userId} />}
        {topTab === 'review' && <AnnotationQueue userId={userId} seed={annotationSeed} onSeedConsumed={() => setAnnotationSeed(null)} />}
      </main>

      {/* ── Modals & Drawers ── */}
      {evalDetail && (
        <TaskDetailPanel
          evalRow={evalDetail}
          onClose={() => setEvalDetail(null)}
          onRun={() => { setEvalDetail(null); setRunDialog(true); }}
          onOpenAnnotations={(item) => { setAnnotationSeed(item); setTopTab('review'); setEvalDetail(null); }}
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
}
