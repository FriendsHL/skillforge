import React from 'react';
import type { AutoEvolvingRecentReport } from '../../api/autoevolving';
import type { MemoryProposal } from '../../api/memoryProposalsApi';

/**
 * AUTOEVOLVING V1 Sprint 4 — three signal-source panels (bento).
 *
 *   ① Production  — recent cross-agent OPT-REPORTs → click /insights reports tab
 *   ② AutoResearch — static placeholder (V2 surface)
 *   ③ Memory      — pending memory proposals → click /memories
 */
interface SignalPanelsProps {
  recentReports: AutoEvolvingRecentReport[];
  memoryProposals: MemoryProposal[];
  /** Loading flag for the overview-backed panels (production reports). */
  loading?: boolean;
  /** Separate loading flag for the memory panel — memory proposals are an
   *  independent query, so panel ③ must not read the overview loading flag
   *  (would show "no proposals" during the memory query's initial load). */
  memoryLoading?: boolean;
  onNavigate: (to: string) => void;
}

function formatWindowEnd(iso: string | null): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return '—';
  return new Date(t).toLocaleDateString();
}

const SignalPanels: React.FC<SignalPanelsProps> = ({
  recentReports,
  memoryProposals,
  loading,
  memoryLoading,
  onNavigate,
}) => {
  return (
    <div className="ae-signals" data-testid="ae-signals">
      {/* ① Production — OPT-REPORT */}
      <section className="ae-panel" aria-label="Production signal">
        <header className="ae-panel-head">
          <h3 className="ae-panel-title">Production</h3>
          <button
            type="button"
            className="ae-panel-link"
            onClick={() => onNavigate('/insights/patterns?tab=reports')}
            data-testid="ae-signal-production-viewall"
          >
            View reports →
          </button>
        </header>
        <div className="ae-panel-body">
          {loading && <p className="ae-panel-hint">Loading…</p>}
          {!loading && recentReports.length === 0 && (
            <p className="ae-panel-hint">No recent optimization reports.</p>
          )}
          <ul className="ae-list">
            {recentReports.map((r) => (
              <li key={r.reportId}>
                <button
                  type="button"
                  className="ae-list-row"
                  onClick={() =>
                    onNavigate(
                      `/insights/patterns?tab=reports${
                        r.agentId != null ? `&agentId=${r.agentId}` : ''
                      }&reportId=${encodeURIComponent(r.reportId)}`,
                    )
                  }
                  data-testid={`ae-report-row-${r.reportId}`}
                >
                  <span className="ae-list-main">
                    {r.agentName ?? `agent ${r.agentId ?? '?'}`}
                  </span>
                  <span className="ae-list-sub">
                    {formatWindowEnd(r.windowEnd)}
                    {` · ${r.topIssueCount} issue${
                      r.topIssueCount === 1 ? '' : 's'
                    }`}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </section>

      {/* ② AutoResearch — placeholder */}
      <section className="ae-panel ae-panel--placeholder" aria-label="Auto-research signal">
        <header className="ae-panel-head">
          <h3 className="ae-panel-title">Auto-research</h3>
          <span className="ae-panel-badge">V2</span>
        </header>
        <div className="ae-panel-body">
          <p className="ae-panel-hint">
            Autonomous research signal is not wired yet. It will surface
            self-initiated investigation runs in a future release.
          </p>
        </div>
      </section>

      {/* ③ Memory proposals */}
      <section className="ae-panel" aria-label="Memory proposals signal">
        <header className="ae-panel-head">
          <h3 className="ae-panel-title">Memory proposals</h3>
          <button
            type="button"
            className="ae-panel-link"
            onClick={() => onNavigate('/memories')}
            data-testid="ae-signal-memory-viewall"
          >
            View memories →
          </button>
        </header>
        <div className="ae-panel-body">
          {memoryLoading && <p className="ae-panel-hint">Loading…</p>}
          {!memoryLoading && memoryProposals.length === 0 && (
            <p className="ae-panel-hint">No pending memory proposals.</p>
          )}
          <ul className="ae-list">
            {memoryProposals.map((p) => (
              <li key={p.id}>
                <button
                  type="button"
                  className="ae-list-row"
                  onClick={() => onNavigate('/memories')}
                  data-testid={`ae-memory-row-${p.id}`}
                >
                  <span className="ae-list-main">
                    {p.suggestedTitle ?? `${p.proposalType} proposal`}
                  </span>
                  <span className="ae-list-sub">{p.proposalType}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </section>
    </div>
  );
};

export default SignalPanels;
