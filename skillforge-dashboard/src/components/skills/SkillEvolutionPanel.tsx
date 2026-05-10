import React, { useEffect, useMemo, useRef, useState } from 'react';
import { message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  startSkillEvolution, getSkillEvolutions,
  type SkillEvolutionRun,
} from '../../api';

interface SkillEvolutionPanelProps {
  skillId: number;
  agentId: string | number;
  currentUserId?: number;
}

function evolutionBadgeStyle(run: SkillEvolutionRun): { background: string; color: string; label: string; dotColor: string } {
  if (run.status === 'RUNNING') {
    return { background: 'rgba(99,102,241,0.12)', color: '#8b8df5', dotColor: '#6366f1', label: 'Running' };
  }
  if (run.status === 'PENDING') {
    return { background: 'var(--bg-hover, #1d1d22)', color: 'var(--fg-4, #8a8a93)', dotColor: '#8a8a93', label: 'Pending' };
  }
  if (run.status === 'FAILED') {
    return { background: 'rgba(240,97,109,0.12)', color: '#f0616d', dotColor: '#f0616d', label: 'Failed' };
  }
  if (run.status === 'PARTIAL') {
    return { background: 'rgba(255,159,67,0.14)', color: '#ff9f43', dotColor: '#ff9f43', label: 'Partial' };
  }
  // COMPLETED
  return { background: 'rgba(54,179,126,0.14)', color: '#36b37e', dotColor: '#36b37e', label: 'Completed' };
}

function fmtDate(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  const now = Date.now();
  const diff = now - d.getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  if (diff < 604800000) return `${Math.floor(diff / 86400000)}d ago`;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

export const SkillEvolutionPanel: React.FC<SkillEvolutionPanelProps> = ({ skillId, agentId, currentUserId }) => {
  const queryClient = useQueryClient();
  const [flash, setFlash] = useState<string | null>(null);
  const flashTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (flashTimerRef.current !== null) clearTimeout(flashTimerRef.current);
    };
  }, []);

  const { data: runs, isLoading, isError } = useQuery<SkillEvolutionRun[]>({
    queryKey: ['skill-evolution-runs', skillId],
    queryFn: () => getSkillEvolutions(skillId).then(r => r.data),
    refetchInterval: (query) => {
      const data = query.state.data;
      return data?.some(r => r.status === 'PENDING' || r.status === 'RUNNING') ? 5000 : false;
    },
  });

  // Sort runs by creation time (oldest first for timeline)
  const sortedRuns = useMemo(() => {
    if (!runs || runs.length === 0) return [];
    return [...runs].sort((a, b) => {
      const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      return ta - tb;
    });
  }, [runs]);

  const latest = useMemo(() => {
    if (!runs || runs.length === 0) return undefined;
    return [...runs].sort((a, b) => {
      const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      return tb - ta;
    })[0];
  }, [runs]);

  // Count successful evolutions
  const evolvedCount = useMemo(() => {
    return sortedRuns.filter(r => r.status === 'COMPLETED').length;
  }, [sortedRuns]);

  // Best score achieved (use successRateBefore as proxy since successRateAfter not available)
  const bestScore = useMemo(() => {
    const scores = sortedRuns
      .filter(r => r.status === 'COMPLETED' && r.successRateBefore != null)
      .map(r => r.successRateBefore!);
    return scores.length > 0 ? Math.max(...scores) : null;
  }, [sortedRuns]);

  const evolveMutation = useMutation({
    mutationFn: () => startSkillEvolution(skillId, {
      agentId: String(agentId),
      triggeredByUserId: currentUserId,
    }),
    onSuccess: () => {
      setFlash('Evolution started');
      queryClient.invalidateQueries({ queryKey: ['skill-evolution-runs', skillId] });
      queryClient.invalidateQueries({ queryKey: ['skills'] });
      if (flashTimerRef.current !== null) clearTimeout(flashTimerRef.current);
      flashTimerRef.current = setTimeout(() => { setFlash(null); flashTimerRef.current = null; }, 4000);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Failed to start evolution');
    },
  });

  const inProgress = latest?.status === 'PENDING' || latest?.status === 'RUNNING';
  const disabled = evolveMutation.isPending || !currentUserId || inProgress;

  return (
    <div className="skill-evolution-panel">
      {/* Header with stats */}
      <div className="evolution-header">
        <span className="evolution-title">Evolution</span>
        {isLoading && <span className="evolution-loading">Loading…</span>}
        {isError && <span className="evolution-error">Failed to load</span>}
        {!isLoading && !isError && !latest && <span className="evolution-empty">No runs yet</span>}
        {latest && (() => {
          const s = evolutionBadgeStyle(latest);
          return (
            <span className="evolution-badge" style={{ background: s.background, color: s.color }}>
              <span className="evolution-dot" style={{ background: s.dotColor }} />
              {s.label}
            </span>
          );
        })()}
      </div>

      {/* Stats summary */}
      {evolvedCount > 0 && (
        <div className="evolution-stats">
          <span className="evolution-stat">
            <span className="stat-icon">📈</span>
            {evolvedCount} evolutions
          </span>
          {bestScore != null && (
            <span className="evolution-stat best-score">
              <span className="stat-icon">🏆</span>
              Best: {Math.round(bestScore)}%
            </span>
          )}
          {latest?.createdAt && (
            <span className="evolution-stat">
              Last: {fmtDate(latest.createdAt)}
            </span>
          )}
        </div>
      )}

      {/* Timeline visualization */}
      {sortedRuns.length > 0 && (
        <div className="evolution-timeline">
          {sortedRuns.map((run, idx) => {
            const s = evolutionBadgeStyle(run);
            const score = run.successRateBefore;

            return (
              <div key={run.id ?? idx} className="timeline-node">
                <div className="timeline-line" />
                <div className="timeline-dot" style={{ background: s.dotColor }} />
                <div className="timeline-content">
                  <div className="timeline-header">
                    <span className="timeline-version">v{(idx + 1).toFixed(1)}</span>
                    <span className="timeline-status" style={{ background: s.background, color: s.color }}>
                      {s.label}
                    </span>
                    <span className="timeline-time">{fmtDate(run.createdAt)}</span>
                  </div>
                  {score != null && (
                    <div className="timeline-scores">
                      <span className="score-after">{Math.round(score)}%</span>
                    </div>
                  )}
                  {run.failureReason && (
                    <div className="timeline-error">{run.failureReason}</div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Current run details */}
      {latest?.successRateBefore != null && (
        <div className="evolution-current">
          Before: {Math.round(latest.successRateBefore)}% · Usage: {latest.usageCountBefore ?? 0}
        </div>
      )}

      {/* Error message */}
      {latest?.failureReason && (
        <div className="evolution-failure">{latest.failureReason}</div>
      )}

      {/* Action button */}
      <div className="evolution-actions">
        <button
          className="btn-ghost-sf"
          disabled={disabled}
          onClick={() => evolveMutation.mutate()}
          title="Generate an improved SKILL.md via LLM and trigger an A/B test"
        >
          {evolveMutation.isPending ? 'Starting…' : inProgress ? 'Running…' : 'Evolve Skill'}
        </button>
        {flash && <span className="evolution-flash">{flash}</span>}
      </div>
    </div>
  );
};