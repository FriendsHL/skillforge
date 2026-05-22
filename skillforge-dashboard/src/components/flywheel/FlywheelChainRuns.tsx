/**
 * FLYWHEEL-CHAIN-VISIBILITY — recent annotator → dispatcher chain runs panel.
 *
 * Surfaces the *process* of the on-demand "Run Opt Loop" trigger that
 * AgentDrawer fires. Without this panel the only visible artifact is the
 * terminal OptimizationEvent, which doesn't tell the operator how far the
 * chain got when no events were produced (annotator found nothing? dispatcher
 * errored?). Three-step row layout matches the BE chain shape:
 *
 *   [annotator] → [dispatcher] → [result]
 *
 * Polls every 5s via react-query so running chains visibly progress without
 * the operator forcing a refresh. Empty state + 404 tolerated — BE may not
 * have wired the endpoint yet during BE/FE parallel rollout.
 *
 * Filters:
 *   - URL `?agentId=N` (from useSearchParams) narrows to one agent's chain runs
 *
 * a11y:
 *   - role="list" on container, role="listitem" rows
 *   - relative-time text + sr-only absolute timestamp for accessibility
 *
 * Visual:
 *   - Reuses fw-runs-* design tokens for consistency with the per-run sidebar
 *   - Compact density: each row 2 lines (header + steps), no large padding
 */
import React, { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import {
  getFlywheelChainRuns,
  type ChainRunResult,
} from '../../api/flywheel';
import { formatLag } from './types';

const POLL_MS = 5_000;
const STALE_MS = 4_000;

interface FlywheelChainRunsProps {
  /** Cap rows; BE default applies when unset. */
  limit?: number;
}

const FlywheelChainRuns: React.FC<FlywheelChainRunsProps> = ({ limit = 20 }) => {
  const [searchParams] = useSearchParams();
  const agentIdRaw = searchParams.get('agentId');
  const agentIdFilter = useMemo<number | undefined>(() => {
    if (!agentIdRaw) return undefined;
    const n = Number(agentIdRaw);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [agentIdRaw]);

  const query = useQuery<ChainRunResult[], Error>({
    queryKey: ['flywheel-chain-runs', agentIdFilter ?? null, limit],
    queryFn: () =>
      getFlywheelChainRuns({ agentId: agentIdFilter, limit })
        .then((r) => r.data ?? [])
        // Tolerate 404 / 5xx — render empty list (BE may not be wired yet).
        .catch(() => [] as ChainRunResult[]),
    staleTime: STALE_MS,
    // Only poll while at least one chain is still running. Once every visible
    // run has `completedAt` populated the list is terminal — stop hammering
    // the BE. Toggling agentId filter / fresh chain trigger naturally rearms
    // the poll because the next refetch returns a non-completed row.
    refetchInterval: (query) => {
      const data = query.state.data ?? [];
      return data.some((r) => r.completedAt === null) ? POLL_MS : false;
    },
    retry: 0,
  });

  const runs = query.data ?? [];

  return (
    <section
      className="fw-chainruns"
      data-testid="flywheel-chain-runs"
      aria-label="Recent chain runs"
    >
      <header className="fw-chainruns-head">
        <h2 className="fw-chainruns-title">Recent Chain Runs</h2>
        <span className="fw-chainruns-sub">
          annotator → dispatcher chains from on-demand "Run Opt Loop"
        </span>
        {agentIdFilter !== undefined && (
          <span
            className="fw-chainruns-filter-chip"
            data-testid="fw-chainruns-agent-filter"
            title="Filtered by agentId — clear by removing ?agentId= from URL"
          >
            agent #{agentIdFilter}
          </span>
        )}
        <span className="fw-chainruns-count" data-testid="fw-chainruns-count">
          {query.isLoading ? '…' : runs.length}
        </span>
      </header>

      {query.isLoading && runs.length === 0 ? (
        <div className="fw-chainruns-empty">Loading…</div>
      ) : runs.length === 0 ? (
        <div
          className="fw-chainruns-empty"
          data-testid="fw-chainruns-empty"
        >
          No recent chain runs.
          {agentIdFilter !== undefined && ' (filtered by agentId)'}
        </div>
      ) : (
        <ul className="fw-chainruns-list" role="list">
          {runs.map((run) => (
            <ChainRunRow key={run.annotatorSessionId} run={run} />
          ))}
        </ul>
      )}
    </section>
  );
};

interface ChainRunRowProps {
  run: ChainRunResult;
}

const ChainRunRow: React.FC<ChainRunRowProps> = React.memo(({ run }) => {
  const isRunning = run.completedAt === null;
  const overallStatus = isRunning ? 'running' : 'completed';
  const annotatorMark = stepMarkAnnotator(run.annotatorStatus);
  const dispatcherMark = stepMarkDispatcher(run.dispatcherStatus);
  const resultMark = stepMarkResult(run.optEventCount, run.dispatcherStatus);
  const startedRelative = formatLag(run.startedAt);

  return (
    <li
      role="listitem"
      className={`fw-chainruns-row${isRunning ? ' fw-chainruns-row--running' : ''}`}
      data-testid={`fw-chainruns-row-${run.annotatorSessionId}`}
    >
      <div className="fw-chainruns-row-head">
        <span className="fw-chainruns-row-agent">{run.agentName}</span>
        <span className="fw-chainruns-row-age" title={run.startedAt}>
          {startedRelative}
        </span>
        <span
          className={`fw-chainruns-row-status fw-chainruns-row-status--${overallStatus}`}
          data-testid={`fw-chainruns-overall-${run.annotatorSessionId}`}
        >
          {isRunning ? 'running…' : 'completed'}
        </span>
      </div>
      <div className="fw-chainruns-row-steps" aria-label="Chain steps">
        <StepBadge
          label="annotator"
          mark={annotatorMark.emoji}
          tone={annotatorMark.tone}
          title={`annotator: ${run.annotatorStatus}`}
        />
        <span className="fw-chainruns-row-arrow" aria-hidden="true">
          →
        </span>
        <StepBadge
          label="dispatcher"
          mark={dispatcherMark.emoji}
          tone={dispatcherMark.tone}
          title={`dispatcher: ${run.dispatcherStatus ?? 'pending'}`}
        />
        <span className="fw-chainruns-row-arrow" aria-hidden="true">
          →
        </span>
        <ResultBadge run={run} mark={resultMark} />
      </div>
    </li>
  );
});

ChainRunRow.displayName = 'ChainRunRow';

interface StepBadgeProps {
  label: string;
  mark: string;
  tone: 'ok' | 'running' | 'error' | 'skipped' | 'pending';
  title: string;
}

const StepBadge: React.FC<StepBadgeProps> = ({ label, mark, tone, title }) => (
  <span
    className={`fw-chainruns-step fw-chainruns-step--${tone}`}
    title={title}
  >
    <span className="fw-chainruns-step-mark" aria-hidden="true">
      {mark}
    </span>
    <span className="fw-chainruns-step-label">{label}</span>
  </span>
);

interface ResultBadgeProps {
  run: ChainRunResult;
  mark: ResultMark;
}

const ResultBadge: React.FC<ResultBadgeProps> = ({ run, mark }) => {
  const text = resultText(run.optEventCount, run.dispatcherStatus);
  const hasLink = run.optEventCount > 0;
  return (
    <span
      className={`fw-chainruns-step fw-chainruns-step--${mark.tone}`}
      title={`result: ${text}`}
    >
      <span className="fw-chainruns-step-mark" aria-hidden="true">
        {mark.emoji}
      </span>
      {hasLink ? (
        <a
          className="fw-chainruns-step-link"
          href={`/insights/patterns?tab=optimization&agentId=${run.agentId}`}
          target="_blank"
          rel="noopener noreferrer"
          data-testid={`fw-chainruns-result-link-${run.annotatorSessionId}`}
        >
          {text}
        </a>
      ) : (
        <span className="fw-chainruns-step-label">{text}</span>
      )}
    </span>
  );
};

// ─── Helpers ───────────────────────────────────────────────────────────────

interface StepMark {
  emoji: string;
  tone: 'ok' | 'running' | 'error' | 'skipped' | 'pending';
}

type ResultMark = StepMark;

function stepMarkAnnotator(status: ChainRunResult['annotatorStatus']): StepMark {
  switch (status) {
    case 'idle':
      return { emoji: '✅', tone: 'ok' };
    case 'error':
      return { emoji: '❌', tone: 'error' };
    case 'running':
    default:
      return { emoji: '🔄', tone: 'running' };
  }
}

function stepMarkDispatcher(status: ChainRunResult['dispatcherStatus']): StepMark {
  switch (status) {
    case 'idle':
      return { emoji: '✅', tone: 'ok' };
    case 'error':
      return { emoji: '❌', tone: 'error' };
    case 'not_fired':
      return { emoji: '⏭', tone: 'skipped' };
    case 'pending':
    case null:
      // BE null = pre-dispatch window (annotator still running, dispatcher
      // not yet classified). Render same as explicit `pending`.
      return { emoji: '⏳', tone: 'pending' };
    case 'running':
      return { emoji: '🔄', tone: 'running' };
    default:
      return { emoji: '🔄', tone: 'running' };
  }
}

function stepMarkResult(
  count: number,
  dispatcherStatus: ChainRunResult['dispatcherStatus'],
): ResultMark {
  if (count > 0) return { emoji: '✨', tone: 'ok' };
  if (count === 0 && dispatcherStatus === 'not_fired') {
    return { emoji: '⏭', tone: 'skipped' };
  }
  if (count === 0) return { emoji: '∅', tone: 'skipped' };
  // count === -1 (unknown) — chain still resolving
  return { emoji: '·', tone: 'pending' };
}

function resultText(
  count: number,
  dispatcherStatus: ChainRunResult['dispatcherStatus'],
): string {
  if (count > 0) {
    return `${count} event${count > 1 ? 's' : ''} →`;
  }
  if (count === 0 && dispatcherStatus === 'not_fired') {
    return 'no eligible patterns';
  }
  if (count === 0) return 'no results';
  return 'unknown';
}

export default FlywheelChainRuns;
