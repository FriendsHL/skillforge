import React from 'react';
import type { AutoEvolvingRecentAnomaly } from '../../api/autoevolving';

/**
 * AUTOEVOLVING V1 Sprint 4 — anomaly diagnostics panel.
 *
 * Simplified anomaly feed = recent failed workflow runs + their error reason
 * (sprint4-plan-draft.md §5; full anomaly aggregation deferred to V2).
 */
interface AnomalyPanelProps {
  anomalies: AutoEvolvingRecentAnomaly[];
  loading?: boolean;
  onSelect?: (runId: string) => void;
}

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return '—';
  return new Date(t).toLocaleString();
}

const AnomalyPanel: React.FC<AnomalyPanelProps> = ({
  anomalies,
  loading,
  onSelect,
}) => {
  return (
    <section className="ae-anomaly" aria-label="Anomaly diagnostics">
      <header className="ae-panel-head">
        <h3 className="ae-panel-title">Anomaly diagnostics</h3>
        <span className="ae-panel-sub">Recent failed runs</span>
      </header>
      <div className="ae-panel-body">
        {loading && <p className="ae-panel-hint">Loading…</p>}
        {!loading && anomalies.length === 0 && (
          <p className="ae-panel-hint">No recent failures. All clear.</p>
        )}
        <ul className="ae-anomaly-list">
          {anomalies.map((a) => {
            const inner = (
              <>
                <div className="ae-anomaly-row-top">
                  <span className="ae-anomaly-name">
                    {a.name ?? '(unnamed)'}
                  </span>
                  <span className="ae-anomaly-time">
                    {formatTime(a.updatedAt)}
                  </span>
                </div>
                {a.errorReason && (
                  <p className="ae-anomaly-reason">{a.errorReason}</p>
                )}
              </>
            );
            return (
              <li key={a.runId}>
                {onSelect ? (
                  <button
                    type="button"
                    className="ae-anomaly-row ae-anomaly-row--clickable"
                    onClick={() => onSelect(a.runId)}
                    data-testid={`ae-anomaly-row-${a.runId}`}
                  >
                    {inner}
                  </button>
                ) : (
                  // No handler → render a plain, readable info row (not a
                  // grayed-out disabled button). The errorReason is inline,
                  // so the row is self-contained.
                  <div
                    className="ae-anomaly-row ae-anomaly-row--static"
                    data-testid={`ae-anomaly-row-${a.runId}`}
                  >
                    {inner}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      </div>
    </section>
  );
};

export default AnomalyPanel;
