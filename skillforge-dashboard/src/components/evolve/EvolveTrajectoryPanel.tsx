/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D — EvolveTrajectoryPanel
 *
 * Assembles the full trajectory view:
 *   - Agent ID input + Load button
 *   - Run list sidebar (EvolveRunList) — multi-select via checkboxes
 *   - Score-over-iteration chart (EvolveTrajectoryChart) with overlaid series
 *
 * Data loading via react-query:
 *   1. listEvolveRuns (when agentId is entered)
 *   2. getEvolveRun   (for each selected run — parallel queries)
 *
 * No WebSocket needed — react-query refetch (staleTime 30s) is sufficient
 * because this is a trajectory history view, not a live feed.
 */
import React, { useState, useCallback } from 'react';
import { useQuery, useQueries } from '@tanstack/react-query';
import { Select } from 'antd';
import { useAuth } from '../../contexts/AuthContext';
import { listEvolveRuns, getEvolveRun } from '../../api/evolve';
import type {
  EvolveRunSummary,
  EvolveRunDetail,
  EvolveIteration,
} from '../../api/evolve';
import { getAgents } from '../../api/index';
import EvolveRunList from './EvolveRunList';
import EvolveTrajectoryChart from './EvolveTrajectoryChart';
import EvolveAdoptCard from './EvolveAdoptCard';
import EvolvePredictionPanel from './EvolvePredictionPanel';
import SemanticDeltaPanel from './SemanticDeltaPanel';
import './evolve.css';

const MAX_OVERLAY_RUNS = 4;

interface AgentLite {
  id: number;
  name: string;
}

/**
 * The last kept iteration that carries a candidate bundle — the adoptable
 * winner for a run. Returns null when the run has no adoptable iteration
 * (nothing kept, or kept rows predate bundle recording).
 */
function lastAdoptableIteration(run: EvolveRunDetail): EvolveIteration | null {
  for (let i = run.iterations.length - 1; i >= 0; i--) {
    const it = run.iterations[i];
    if (it.kept && it.candidateBundle != null) return it;
  }
  return null;
}

const EvolveTrajectoryPanel: React.FC = () => {
  const { userId } = useAuth();
  // The selected agent (drives the run list). Selecting from the dropdown
  // commits immediately — no separate Load step.
  const [committedAgentId, setCommittedAgentId] = useState<number | null>(null);
  // Selected run IDs for charting (up to MAX_OVERLAY_RUNS)
  const [selectedRunIds, setSelectedRunIds] = useState<string[]>([]);

  // ── agents for the picker ──
  const { data: agents, isLoading: agentsLoading } = useQuery({
    queryKey: ['agents', 'evolve-trajectory'],
    queryFn: () => getAgents().then((r) => (r.data as AgentLite[]) ?? []),
    staleTime: 60_000,
  });

  // ── list runs ──
  const {
    data: runsEnvelope,
    isLoading: runsLoading,
    isError: runsError,
    error: runsErrObj,
  } = useQuery({
    queryKey: ['evolve-runs', committedAgentId],
    queryFn: () =>
      committedAgentId != null
        ? listEvolveRuns(committedAgentId).then((r) => r.data)
        : Promise.resolve({ items: [] as EvolveRunSummary[] }),
    enabled: committedAgentId != null,
    staleTime: 30_000,
  });

  const runs = runsEnvelope?.items ?? [];

  // ── detail queries for each selected run (parallel) ──
  // MUST use useQueries (a single, stable hook) — NOT selectedRunIds.map(useQuery),
  // which calls a variable number of hooks per render and violates the Rules of
  // Hooks: selecting a run changes the hook count → React's areHookInputsEqual
  // reads `.length` on undefined prevDeps → "Cannot read properties of undefined
  // (reading 'length')" crash the moment a run row is clicked.
  const selectedRunQueries = useQueries({
    queries: selectedRunIds.map((id) => ({
      queryKey: ['evolve-run-detail', id],
      queryFn: () => getEvolveRun(id).then((r) => r.data),
      staleTime: 30_000,
    })),
  });

  const detailsLoading = selectedRunQueries.some((q) => q.isLoading);
  const detailRuns: EvolveRunDetail[] = selectedRunQueries
    .filter((q) => q.data != null)
    .map((q) => q.data as EvolveRunDetail);

  const handleSelectAgent = useCallback((agentId: number) => {
    setCommittedAgentId(agentId);
    setSelectedRunIds([]);
  }, []);

  const handleSelectRun = useCallback((runId: string) => {
    setSelectedRunIds((prev) => {
      if (prev.includes(runId)) {
        return prev.filter((id) => id !== runId);
      }
      // Enforce overlay cap — drop the oldest selection
      const next = [...prev, runId];
      return next.length > MAX_OVERLAY_RUNS ? next.slice(next.length - MAX_OVERLAY_RUNS) : next;
    });
  }, []);

  const runsErrMsg =
    runsError
      ? runsErrObj instanceof Error
        ? runsErrObj.message
        : 'Failed to load evolve runs.'
      : null;

  return (
    <section
      className="etraj-section"
      aria-label="Evolution trajectory"
      data-testid="evolve-trajectory-panel"
    >
      <div className="etraj-head">
        <h3 className="etraj-title">Evolution trajectory</h3>
        {detailsLoading && (
          <span className="etraj-subtitle">Loading…</span>
        )}
      </div>

      {/* Agent picker */}
      <div className="etraj-agent-select">
        <label className="etraj-agent-label" htmlFor="etraj-agent-select">
          Agent
        </label>
        <Select
          id="etraj-agent-select"
          className="etraj-agent-dropdown"
          style={{ minWidth: 240 }}
          showSearch
          optionFilterProp="label"
          placeholder={agentsLoading ? 'Loading agents…' : 'Select an agent'}
          loading={agentsLoading || runsLoading}
          value={committedAgentId ?? undefined}
          onChange={handleSelectAgent}
          options={(agents ?? []).map((a) => ({
            label: `${a.name} (#${a.id})`,
            value: a.id,
          }))}
          data-testid="etraj-agent-select"
        />
      </div>

      {runsErrMsg && (
        <p className="etraj-error" role="alert">
          {runsErrMsg}
        </p>
      )}

      <div className="etraj-body">
        {/* Run list sidebar */}
        <aside className="etraj-sidebar">
          <EvolveRunList
            runs={runs}
            selectedRunId={selectedRunIds[selectedRunIds.length - 1] ?? null}
            loading={runsLoading}
            onSelect={handleSelectRun}
          />
        </aside>

        {/* Chart area */}
        <div className="etraj-chart-area">
          <EvolveTrajectoryChart
            runs={detailRuns}
            height={320}
          />
        </div>
      </div>

      {/* Prediction reconciliation — one per selected run that recorded G3
          predictions; renders nothing for runs predating G3. */}
      {detailRuns.map((run) => (
        <EvolvePredictionPanel key={`pred-${run.evolveRunId}`} run={run} />
      ))}

      {/* Candidate changes (P2a) — per-surface before/after/diff for each
          selected run's iterations; renders nothing when no semantic delta. */}
      {detailRuns.map((run) => (
        <SemanticDeltaPanel key={`sd-${run.evolveRunId}`} run={run} />
      ))}

      {/* Adopt cards — one per selected run that has an adoptable winner */}
      {detailRuns.map((run) => {
        const adoptable = lastAdoptableIteration(run);
        if (!adoptable) return null;
        return (
          <EvolveAdoptCard
            key={run.evolveRunId}
            evolveRunId={run.evolveRunId}
            agentId={run.agentId}
            iteration={adoptable}
            userId={userId}
          />
        );
      })}
    </section>
  );
};

export default EvolveTrajectoryPanel;
