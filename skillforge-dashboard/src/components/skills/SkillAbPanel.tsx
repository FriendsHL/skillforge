import React, { useEffect, useMemo, useRef, useState } from 'react';
import { message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  forkSkill, startSkillAbTest, getSkillAbTests,
  type SkillAbRun, type SkillVersionEntry,
} from '../../api';
import { useAuth } from '../../contexts/AuthContext';

interface SkillAbPanelProps {
  skillId: number;
}

function formatPct(v: number | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—';
  return `${Math.round(v)}%`;
}

function formatDeltaPp(v: number | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—';
  const sign = v > 0 ? '+' : '';
  return `${sign}${v.toFixed(1)} pp`;
}

interface AbStatusBadgeStyle {
  background: string;
  color: string;
  label: string;
  dotColor: string;
}

function abBadgeStyle(run: SkillAbRun): AbStatusBadgeStyle {
  if (run.status === 'RUNNING') {
    return { background: 'rgba(99,102,241,0.12)', color: '#8b8df5', dotColor: '#6366f1', label: 'Running' };
  }
  if (run.status === 'PENDING') {
    return { background: 'var(--bg-hover, #1d1d22)', color: 'var(--fg-4, #8a8a93)', dotColor: '#8a8a93', label: 'Pending' };
  }
  if (run.status === 'FAILED') {
    return { background: 'rgba(240,97,109,0.12)', color: '#f0616d', dotColor: '#f0616d', label: 'Failed' };
  }
  // COMPLETED
  if (run.promoted) {
    return { background: 'rgba(54,179,126,0.14)', color: '#36b37e', dotColor: '#36b37e', label: 'Promoted' };
  }
  return { background: 'rgba(255,159,67,0.14)', color: '#ff9f43', dotColor: '#ff9f43', label: 'Not promoted' };
}

export const SkillAbPanel: React.FC<SkillAbPanelProps> = ({ skillId }) => {
  const queryClient = useQueryClient();
  const { userId: currentUserId } = useAuth();
  const [flash, setFlash] = useState<string | null>(null);
  const flashTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (flashTimerRef.current !== null) {
        clearTimeout(flashTimerRef.current);
      }
    };
  }, []);

  const { data: runs, isLoading, isError } = useQuery<SkillAbRun[]>({
    queryKey: ['skill-ab-tests', skillId],
    queryFn: () => getSkillAbTests(skillId).then(r => r.data),
    enabled: typeof skillId === 'number',
  });

  const latest = useMemo(() => {
    if (!runs || runs.length === 0) return undefined;
    const sorted = [...runs].sort((a, b) => {
      const ta = a.startedAt ? new Date(a.startedAt).getTime() : 0;
      const tb = b.startedAt ? new Date(b.startedAt).getTime() : 0;
      return tb - ta;
    });
    return sorted[0];
  }, [runs]);

  const abMutation = useMutation({
    mutationFn: async () => {
      const forkRes = await forkSkill(skillId, currentUserId);
      const forked: SkillVersionEntry = forkRes.data;
      const startRes = await startSkillAbTest(skillId, {
        candidateSkillId: forked.id,
        triggeredByUserId: currentUserId,
      });
      return startRes.data;
    },
    onSuccess: () => {
      setFlash('A/B test started');
      queryClient.invalidateQueries({ queryKey: ['skill-ab-tests', skillId] });
      queryClient.invalidateQueries({ queryKey: ['skills'] });
      if (flashTimerRef.current !== null) clearTimeout(flashTimerRef.current);
      flashTimerRef.current = setTimeout(() => { setFlash(null); flashTimerRef.current = null; }, 4000);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      const msg = e.response?.data?.error || e.message || 'Failed to start A/B test';
      message.error(msg);
    },
  });

  const busy = abMutation.isPending;

  return (
    <div
      style={{
        marginTop: 10,
        paddingTop: 10,
        borderTop: '1px solid var(--border-subtle, #2a2a31)',
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
      }}
    >
      <div
        style={{
          display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
          fontSize: 11, color: 'var(--fg-4, #8a8a93)',
        }}
      >
        <span style={{ fontWeight: 600, color: 'var(--fg-3, #a8a8b1)' }}>A/B test</span>
        {isLoading && <span>Loading…</span>}
        {isError && <span style={{ color: 'var(--color-err, #f0616d)' }}>Failed to load runs</span>}
        {!isLoading && !isError && !latest && <span>No runs yet</span>}
        {latest && (() => {
          const s = abBadgeStyle(latest);
          return (
            <span
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 5,
                fontSize: 10, padding: '1px 7px', borderRadius: 10,
                background: s.background, color: s.color,
                fontFamily: 'var(--font-mono, monospace)',
              }}
            >
              <span
                style={{
                  width: 6, height: 6, borderRadius: '50%',
                  background: s.dotColor, display: 'inline-block',
                }}
              />
              {s.label}
            </span>
          );
        })()}
      </div>

      {latest && (latest.baselinePassRate != null || latest.candidatePassRate != null) && (
        <div
          style={{
            fontSize: 11, color: 'var(--fg-4, #8a8a93)',
            fontFamily: 'var(--font-mono, monospace)',
          }}
        >
          Baseline: {formatPct(latest.baselinePassRate)} · Candidate: {formatPct(latest.candidatePassRate)} · Δ {formatDeltaPp(latest.deltaPassRate)}
        </div>
      )}

      {latest?.skipReason && (
        <div style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)', fontStyle: 'italic' }}>
          {latest.skipReason}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 2 }}>
        <button
          className="btn-ghost-sf"
          disabled={busy}
          onClick={() => abMutation.mutate()}
          style={{ fontSize: 11, padding: '3px 10px' }}
          title="Fork this skill and launch an A/B test against the parent"
        >
          {busy ? 'Starting…' : 'Fork & A/B Test'}
        </button>
        {flash && (
          <span style={{ fontSize: 11, color: 'var(--accent, #6366f1)' }}>
            {flash}
          </span>
        )}
      </div>
    </div>
  );
};
