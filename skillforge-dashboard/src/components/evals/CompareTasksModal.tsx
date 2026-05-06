import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Select } from 'antd';
import { Link } from 'react-router-dom';
import { compareEvalTasks, type EvalTaskCompareEntry } from '../../api';
import { CLOSE_ICON, METRIC_OPTIONS, type EvalMetric, getMetricValue, formatMetricValue, computeMetricDelta } from './evalUtils';

function CompareEntryCell({ entry, metric }: { entry: EvalTaskCompareEntry | null; metric: EvalMetric }) {
  if (!entry) return <div className="compare-entry-empty">—</div>;
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

export default function CompareTasksModal({ taskIds, onClose }: { taskIds: string[]; onClose: () => void }) {
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
                      {taskIds.map(taskId => <th key={taskId}>{taskId}</th>)}
                      <th>Delta</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map(row => (
                      <tr key={row.scenarioId}>
                        <td className="mono-sm">{row.scenarioId}</td>
                        {taskIds.map(taskId => (
                          <td key={`${row.scenarioId}-${taskId}`}>
                            <CompareEntryCell entry={row.entries.find(e => e.taskId === taskId) ?? null} metric={metric} />
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
