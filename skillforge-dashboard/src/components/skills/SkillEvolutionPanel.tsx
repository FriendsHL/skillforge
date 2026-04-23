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
  return { background: 'rgba(54,179,126,0.14)', color: '#36b37e', dotColor: '#36b37e', label: 'Evolved' };
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

  const latest = useMemo(() => {
    if (!runs || runs.length === 0) return undefined;
    return [...runs].sort((a, b) => {
      const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      return tb - ta;
    })[0];
  }, [runs]);

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
    <div style={{
      marginTop: 10,
      paddingTop: 10,
      borderTop: '1px solid var(--border-subtle, #2a2a31)',
      display: 'flex', flexDirection: 'column', gap: 6,
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
        fontSize: 11, color: 'var(--fg-4, #8a8a93)',
      }}>
        <span style={{ fontWeight: 600, color: 'var(--fg-3, #a8a8b1)' }}>Evolution</span>
        {isLoading && <span>Loading…</span>}
        {isError && <span style={{ color: 'var(--color-err, #f0616d)' }}>Failed to load runs</span>}
        {!isLoading && !isError && !latest && <span>No runs yet</span>}
        {latest && (() => {
          const s = evolutionBadgeStyle(latest);
          return (
            <span style={{
              display: 'inline-flex', alignItems: 'center', gap: 5,
              fontSize: 10, padding: '1px 7px', borderRadius: 10,
              background: s.background, color: s.color,
              fontFamily: 'var(--font-mono, monospace)',
            }}>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: s.dotColor, display: 'inline-block' }} />
              {s.label}
            </span>
          );
        })()}
      </div>

      {latest?.successRateBefore != null && (
        <div style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)', fontFamily: 'var(--font-mono, monospace)' }}>
          Success before: {Math.round(latest.successRateBefore)}% · Usage: {latest.usageCountBefore ?? 0}
        </div>
      )}

      {latest?.failureReason && (
        <div style={{ fontSize: 11, color: 'var(--color-err, #f0616d)', fontStyle: 'italic' }}>
          {latest.failureReason}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 2 }}>
        <button
          className="btn-ghost-sf"
          disabled={disabled}
          onClick={() => evolveMutation.mutate()}
          style={{ fontSize: 11, padding: '3px 10px' }}
          title="Generate an improved SKILL.md via LLM and trigger an A/B test"
        >
          {evolveMutation.isPending ? 'Starting…' : inProgress ? 'Running…' : 'Evolve Skill'}
        </button>
        {flash && <span style={{ fontSize: 11, color: 'var(--accent, #6366f1)' }}>{flash}</span>}
      </div>
    </div>
  );
};
