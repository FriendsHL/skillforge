import React, { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  getScenarioRecentRuns,
  type EvalDatasetScenario,
  type ScenarioRecentRun,
} from '../../api';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);

interface ScenarioDetailDrawerProps {
  scenario: EvalDatasetScenario;
  onClose: () => void;
  onAnalyze?: (scenario: EvalDatasetScenario) => void;
}

function scoreColor(score01: number): string {
  if (score01 >= 0.9) return '#3a7d54';
  if (score01 >= 0.75) return '#3a527d';
  if (score01 >= 0.6) return '#b07a3a';
  return '#8a2a2a';
}

function fmtTime(iso: string | null | undefined): string {
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

function RecentRunsTrend({ runs }: { runs: ScenarioRecentRun[] }) {
  if (runs.length < 2) return null;
  const w = 320;
  const h = 48;
  const pts = runs
    .slice()
    .reverse() // chronological order, oldest left
    .map((r, i) => {
      const score = (r.compositeScore ?? 0) / 100;
      const x = (i / (runs.length - 1)) * w;
      const y = h - score * h;
      return `${x},${y}`;
    });
  return (
    <svg width={w} height={h} style={{ display: 'block', marginBottom: 8 }}>
      <polyline points={pts.join(' ')} fill="none" stroke="var(--accent, #6366f1)" strokeWidth="1.5" strokeLinejoin="round" />
    </svg>
  );
}

function ScenarioDetailDrawer({ scenario, onClose, onAnalyze }: ScenarioDetailDrawerProps) {
  // Esc to close
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const { data: recentRuns = [], isLoading } = useQuery({
    queryKey: ['scenario-recent-runs', scenario.id],
    queryFn: () => getScenarioRecentRuns(scenario.id, 5).then(r => r.data ?? []),
    enabled: !!scenario.id,
  });

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div style={{ minWidth: 0 }}>
              <h2 className="sf-drawer-title" style={{ wordBreak: 'break-word' }}>{scenario.name}</h2>
              <p className="sf-drawer-subtitle">
                {scenario.category} · {scenario.split} · {scenario.oracleType}
              </p>
            </div>
            {onAnalyze && (
              <div className="sf-drawer-actions">
                <button className="btn-ghost-sf" onClick={() => onAnalyze(scenario)}>
                  Analyze
                </button>
              </div>
            )}
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`sess-status s-${scenario.status === 'active' ? 'idle' : scenario.status === 'draft' ? 'waiting' : 'error'}`}>
              {scenario.status}
            </span>
            {scenario.sourceSessionId && (
              <span className="kv-chip-sf" title={scenario.sourceSessionId}>
                source · {scenario.sourceSessionId.slice(0, 8)}
              </span>
            )}
            <span className="kv-chip-sf">created · {fmtTime(scenario.createdAt)}</span>
          </div>
        </div>

        <div className="sf-drawer-body">
          <div className="scn-detail-section">
            <h4>Task</h4>
            <pre>{scenario.task}</pre>
          </div>

          {scenario.oracleExpected && (
            <div className="scn-detail-section">
              <h4>Expected output</h4>
              <pre>{scenario.oracleExpected}</pre>
            </div>
          )}

          {scenario.extractionRationale && (
            <div className="scn-detail-section">
              <h4>Extraction rationale</h4>
              <pre>{scenario.extractionRationale}</pre>
            </div>
          )}

          <div className="scn-detail-section">
            <h4>Recent runs (last {recentRuns.length})</h4>
            {isLoading ? (
              <div className="sf-empty-state" style={{ padding: 12 }}>Loading…</div>
            ) : recentRuns.length === 0 ? (
              <div className="sf-empty-state" style={{ padding: 12 }}>No runs yet.</div>
            ) : (
              <>
                <RecentRunsTrend runs={recentRuns} />
                {recentRuns.map(r => {
                  const score01 = (r.compositeScore ?? 0) / 100;
                  return (
                    <div key={r.evalRunId} className="scn-recent-run">
                      <div>
                        <div className="rid">{r.evalRunId.slice(0, 8)}</div>
                        <div style={{ fontSize: 11, color: 'var(--fg-4)', marginTop: 2 }}>
                          {fmtTime(r.completedAt ?? r.startedAt)}
                        </div>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span className={`sess-status s-${r.status === 'PASS' ? 'idle' : r.status === 'TIMEOUT' ? 'waiting' : 'error'}`}>
                          {r.status}
                        </span>
                        <span style={{ color: scoreColor(score01), fontFamily: 'var(--font-mono)', fontSize: 12, fontWeight: 500, minWidth: 48, textAlign: 'right' }}>
                          {r.compositeScore != null ? `${Math.round(r.compositeScore)}%` : '—'}
                        </span>
                      </div>
                    </div>
                  );
                })}
              </>
            )}
          </div>
        </div>
      </aside>
    </>
  );
}

export default ScenarioDetailDrawer;
