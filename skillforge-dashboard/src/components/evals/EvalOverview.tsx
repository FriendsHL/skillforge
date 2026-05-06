import { useMemo } from 'react';
import type { EvalRow, ProgressState } from './evalUtils';
import { scoreColor, PLAY_ICON, TRACE_ICON } from './evalUtils';

interface EvalOverviewProps {
  rows: EvalRow[];
  progressByRun: Record<string, ProgressState>;
  pendingAnnotations: number;
  totalDatasets: number;
  onRunEval: () => void;
  onOpenTask: (row: EvalRow) => void;
  onSwitchTab: (tab: 'tasks' | 'datasets' | 'review') => void;
}

function KpiCard({ label, value, sub, color, onClick }: {
  label: string; value: string | number; sub?: string; color?: string; onClick?: () => void;
}) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      className="eval-kpi-card"
      {...(onClick ? { onClick, type: 'button' as const } : {})}
    >
      <span className="eval-kpi-label">{label}</span>
      <span className="eval-kpi-value" style={color ? { color } : undefined}>{value}</span>
      {sub && <span className="eval-kpi-sub">{sub}</span>}
    </Tag>
  );
}

export default function EvalOverview({
  rows,
  progressByRun,
  pendingAnnotations,
  totalDatasets,
  onRunEval,
  onOpenTask,
  onSwitchTab,
}: EvalOverviewProps) {
  const stats = useMemo(() => {
    const total = rows.reduce((a, r) => a + r.cases, 0);
    const passed = rows.reduce((a, r) => a + r.pass, 0);
    const avgScore = rows.length > 0
      ? rows.reduce((a, r) => a + r.score, 0) / rows.length
      : 0;
    const running = rows.filter(r => r.status === 'warn' && r.raw.status === 'RUNNING').length;
    return { total, passed, avgScore, running };
  }, [rows]);

  const recentTasks = useMemo(() => {
    return [...rows]
      .sort((a, b) => {
        const ta = String(a.raw.completedAt || a.raw.startedAt || '');
        const tb = String(b.raw.completedAt || b.raw.startedAt || '');
        return tb.localeCompare(ta);
      })
      .slice(0, 5);
  }, [rows]);

  return (
    <div className="eval-overview">
      {/* KPI row */}
      <div className="eval-kpi-grid">
        <KpiCard
          label="Total Scenarios"
          value={stats.total}
          sub={`${rows.length} tasks`}
          onClick={() => onSwitchTab('tasks')}
        />
        <KpiCard
          label="Pass Rate"
          value={stats.total > 0 ? `${Math.round((stats.passed / stats.total) * 100)}%` : '—'}
          sub={`${stats.passed} passed`}
          color={stats.total > 0 ? scoreColor(stats.passed / stats.total) : undefined}
        />
        <KpiCard
          label="Avg Score"
          value={`${(stats.avgScore * 100).toFixed(1)}%`}
          color={scoreColor(stats.avgScore)}
        />
        <KpiCard
          label="Running"
          value={stats.running}
          sub={stats.running > 0 ? 'in progress' : 'idle'}
          color={stats.running > 0 ? 'var(--color-warn)' : undefined}
        />
      </div>

      {/* Two-column: recent tasks + dataset summary */}
      <div className="eval-overview-grid">
        <div className="eval-overview-section">
          <div className="eval-overview-section-h">
            <h3>Recent Tasks</h3>
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="sf-mini-btn" onClick={() => onSwitchTab('tasks')}>View all</button>
              <button className="btn-primary-sf" onClick={onRunEval} style={{ fontSize: 11, padding: '2px 10px' }}>
                {PLAY_ICON} Run
              </button>
            </div>
          </div>
          {recentTasks.length === 0 ? (
            <div className="sf-empty-state" style={{ padding: '24px 0' }}>No eval tasks yet. Run your first eval.</div>
          ) : (
            <div className="eval-recent-list">
              {recentTasks.map(row => {
                const live = progressByRun[row.id];
                const isRunning = String(row.raw.status) === 'RUNNING';
                return (
                  <button
                    key={row.id}
                    className="eval-recent-row"
                    onClick={() => onOpenTask(row)}
                  >
                    <div className="eval-recent-row-l">
                      <span className={`eval-dot s-${row.status}`} />
                      <span className="eval-recent-name">{row.name}</span>
                    </div>
                    <div className="eval-recent-row-r">
                      <span className="eval-recent-score" style={{ color: scoreColor(row.score) }}>
                        {(row.score * 100).toFixed(0)}%
                      </span>
                      <span className="eval-recent-meta">{row.pass}/{row.cases} pass</span>
                      <span className="eval-recent-time">{row.lastRun}</span>
                    </div>
                    {isRunning && live && (
                      <div className="eval-recent-progress">
                        <div className="eval-recent-progress-fill" style={{ width: `${live.totalCount > 0 ? Math.round((live.passedCount / live.totalCount) * 100) : 0}%` }} />
                      </div>
                    )}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <div className="eval-overview-section">
          <div className="eval-overview-section-h">
            <h3>Dataset</h3>
            <button className="sf-mini-btn" onClick={() => onSwitchTab('datasets')}>Browse</button>
          </div>
          <div className="eval-dataset-summary">
            <div className="eval-dataset-stat">
              <span className="eval-dataset-stat-n">{totalDatasets}</span>
              <span className="eval-dataset-stat-l">Scenarios</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
