import React, { useEffect, useMemo, useRef, useState } from 'react';
import { message, Modal, Tooltip, Tag } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  forkSkill, startSkillAbTest, getSkillAbTests,
  manualPromoteAbRun, rollbackSkill,
  type SkillAbRun, type SkillVersionEntry,
} from '../../api';
import { useAuth } from '../../contexts/AuthContext';
import type { SkillRow } from './types';

interface SkillAbPanelProps {
  skillId: number;
  /** Source agent id selected in the SkillList header. Required for /abtest BE call. */
  agentId: number | null;
  /**
   * SKILL-DASHBOARD-POLISH §D — full SkillRow for the **currently open** skill.
   * Needed so the panel can decide whether to surface the Rollback button
   * (only visible when this skill is itself a candidate, i.e. parentSkillId
   *  != null && enabled). Optional for backwards compat with legacy callers.
   */
  skill?: SkillRow;
}

/**
 * SKILL-DASHBOARD-POLISH §C/§D — auto-promote thresholds. Mirrors the BE
 * constants in `SkillAbTestService` so the FE can render the threshold
 * tooltip and confirm-modal copy without an extra round-trip.
 */
const PROMOTE_DELTA_THRESHOLD = 15; // pp
const PROMOTE_CANDIDATE_FLOOR = 40; // %

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
    return {
      background: 'rgba(54,179,126,0.14)',
      color: '#36b37e',
      dotColor: '#36b37e',
      label: run.manuallyPromoted ? 'Promoted (manual)' : 'Promoted',
    };
  }
  return { background: 'rgba(255,159,67,0.14)', color: '#ff9f43', dotColor: '#ff9f43', label: 'Not promoted' };
}

/**
 * Render the threshold-reasoning tooltip body for the badge. Uses ✓/✗
 * glyphs against each axis so the operator can see at a glance which
 * threshold drove the decision.
 */
function badgeTooltipBody(run: SkillAbRun | undefined): React.ReactNode {
  if (!run) return 'No A/B run data available.';
  
  if (run.status === 'FAILED') {
    return run.failureReason || 'A/B run failed without a recorded reason.';
  }
  if (run.status === 'PENDING' || run.status === 'RUNNING') {
    return 'A/B run is still in progress.';
  }
  if (run.skipReason) {
    return `Skipped: ${run.skipReason}`;
  }
  // COMPLETED branch — show threshold pass / fail breakdown.
  const delta = run.deltaPassRate ?? 0;
  const cand = run.candidatePassRate ?? 0;
  const deltaPass = delta >= PROMOTE_DELTA_THRESHOLD;
  const candPass = cand >= PROMOTE_CANDIDATE_FLOOR;
  
  return (
    <div style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 11.5, lineHeight: 1.55 }}>
      <div>
        delta = {formatDeltaPp(run.deltaPassRate)}{' '}
        {deltaPass ? '≥ 15 ✓' : `< ${PROMOTE_DELTA_THRESHOLD} ✗`}
      </div>
      <div>
        candidate = {formatPct(run.candidatePassRate)}{' '}
        {candPass ? `≥ ${PROMOTE_CANDIDATE_FLOOR}% ✓` : `< ${PROMOTE_CANDIDATE_FLOOR}% ✗`}
      </div>
      {run.manuallyPromoted && (
        <div style={{ marginTop: 4, color: '#ff9f43' }}>Manually promoted (override).</div>
      )}
    </div>
  );
}

export const SkillAbPanel: React.FC<SkillAbPanelProps> = ({ skillId, agentId, skill }) => {
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
      if (agentId == null) {
        // Defensive: button is disabled when agentId is null, but guard anyway
        // so the BE 400 ('agentId is required') doesn't surface as an opaque error.
        return Promise.reject(new Error('Pick a source agent in the Skills header first'));
      }
      const forkRes = await forkSkill(skillId, currentUserId);
      const forked: SkillVersionEntry = forkRes.data;
      const startRes = await startSkillAbTest(skillId, {
        candidateSkillId: forked.id,
        agentId: String(agentId),
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

  /**
   * SKILL-DASHBOARD-POLISH §D — manual promote override. Confirmable so the
   * operator acknowledges the candidate did NOT pass auto-thresholds.
   */
  const promoteMutation = useMutation({
    mutationFn: (abRunId: string) => manualPromoteAbRun(abRunId, currentUserId),
    onSuccess: () => {
      message.success('Candidate promoted manually');
      queryClient.invalidateQueries({ queryKey: ['skill-ab-tests', skillId] });
      queryClient.invalidateQueries({ queryKey: ['skills'] });
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Manual promote failed');
    },
  });

  /**
   * SKILL-DASHBOARD-POLISH §D — rollback. Disables the candidate and
   * re-enables the parent. Only surfaced when the open skill is itself
   * a candidate (parentSkillId != null && enabled).
   */
  const rollbackMutation = useMutation({
    mutationFn: (candidateSkillId: number) => rollbackSkill(candidateSkillId, currentUserId),
    onSuccess: () => {
      message.success('Rolled back to parent version');
      queryClient.invalidateQueries({ queryKey: ['skills'] });
      queryClient.invalidateQueries({ queryKey: ['skill-ab-tests', skillId] });
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Rollback failed');
    },
  });

  const handleManualPromote = () => {
    if (!latest) return;
    const delta = formatDeltaPp(latest.deltaPassRate);
    const candRate = formatPct(latest.candidatePassRate);
    Modal.confirm({
      title: 'Promote anyway?',
      content: (
        <div style={{ fontSize: 12.5, lineHeight: 1.55 }}>
          <p>
            Candidate did not pass auto-promote thresholds (delta = {delta},{' '}
            candidate = {candRate}).
          </p>
          <p>This will mark the candidate as the active version, disabling the parent.</p>
        </div>
      ),
      okText: 'Promote anyway',
      cancelText: 'Cancel',
      okButtonProps: { danger: true },
      onOk: () => promoteMutation.mutate(latest.id),
    });
  };

  const handleRollback = () => {
    if (!skill || typeof skill.id !== 'number') return;
    Modal.confirm({
      title: 'Roll back to parent?',
      content: (
        <div style={{ fontSize: 12.5, lineHeight: 1.55 }}>
          <p>
            Current candidate version{skill.semver ? ` (${skill.semver})` : ''} will be
            disabled and the parent skill re-enabled.
          </p>
        </div>
      ),
      okText: 'Roll back',
      cancelText: 'Cancel',
      okButtonProps: { danger: true },
      onOk: () => rollbackMutation.mutate(skill.id as number),
    });
  };

  const busy = abMutation.isPending;

  const showManualPromote =
    !!latest &&
    latest.status === 'COMPLETED' &&
    !latest.promoted &&
    !latest.skipReason;
  const showRollback =
    !!skill && skill.parentSkillId != null && skill.enabled === true;

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
      {/* A/B Test Status & Fork Info */}
      <div style={{ marginBottom: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
          <span style={{ fontWeight: 600, fontSize: 12, color: 'var(--fg-1)' }}>A/B Testing</span>
          {latest && (
            <Tooltip title={badgeTooltipBody(latest)}>
              <Tag color={abBadgeStyle(latest).color}>{latest.status}</Tag>
            </Tooltip>
          )}
        </div>
        
        {latest && latest.candidateSkillId && (
          <div style={{ fontSize: 11, color: 'var(--fg-3)', marginBottom: 8 }}>
            Comparing: <strong>v{skill.semver}</strong> vs <strong>Candidate #{latest.candidateSkillId}</strong>
          </div>
        )}

        {latest?.status === 'RUNNING' && (
          <div style={{ height: 4, background: 'var(--bg-hover)', borderRadius: 2, overflow: 'hidden', marginBottom: 8 }}>
            <div style={{ width: '100%', height: '100%', background: 'var(--accent-primary, #6366f1)', animation: 'pulse 1.5s infinite' }}></div>
          </div>
        )}
      </div>

      <div
        style={{
          display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
          fontSize: 11, color: 'var(--fg-4, #8a8a93)',
        }}
      >
        {isLoading && <span>Loading…</span>}
        {isError && <span style={{ color: 'var(--color-err, #f0616d)' }}>Failed to load runs</span>}
        {!isLoading && !isError && !latest && <span>No runs yet. Start one below.</span>}
        {latest && (() => {
          const s = abBadgeStyle(latest);
          const badgeEl = (
            <span
              data-testid="ab-status-badge"
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 5,
                fontSize: 10, padding: '1px 7px', borderRadius: 10,
                background: s.background, color: s.color,
                fontFamily: 'var(--font-mono, monospace)',
                cursor: 'help',
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
          return (
            <Tooltip title={badgeTooltipBody(latest)} mouseEnterDelay={0.1}>
              {badgeEl}
            </Tooltip>
          );
        })()}
      </div>

      {/* Enhanced Comparison Dashboard */}
      {latest && (latest.baselinePassRate != null || latest.candidatePassRate != null) && (
        <div style={{ 
          background: 'var(--bg-hover)', 
          borderRadius: 8, 
          padding: 16, 
          marginTop: 8,
          border: '1px solid var(--border-subtle)'
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12, fontSize: 12, fontWeight: 600 }}>
            <span>Performance Comparison</span>
            <span style={{ color: latest.deltaPassRate > 0 ? '#52c41a' : '#ff4d4f' }}>
              Delta: {formatDeltaPp(latest.deltaPassRate)}
            </span>
          </div>
          
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <div>
              <div style={{ fontSize: 10, color: 'var(--fg-3)', marginBottom: 4 }}>BASELINE (v{skill.semver})</div>
              <div style={{ fontSize: 20, fontWeight: 700 }}>{formatPct(latest.baselinePassRate)}</div>
              <div style={{ fontSize: 10, color: 'var(--fg-4)' }}>Pass Rate</div>
            </div>
            <div>
              <div style={{ fontSize: 10, color: 'var(--fg-3)', marginBottom: 4 }}>CANDIDATE (Fork)</div>
              <div style={{ fontSize: 20, fontWeight: 700, color: '#6366f1' }}>{formatPct(latest.candidatePassRate)}</div>
              <div style={{ fontSize: 10, color: 'var(--fg-4)' }}>Pass Rate</div>
            </div>
          </div>

          {/* Progress Bar if Running */}
          {latest.status === 'RUNNING' && (
            <div style={{ marginTop: 12 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10, marginBottom: 4 }}>
                <span>Evaluation Progress</span>
                <span>Processing...</span>
              </div>
              <div style={{ height: 4, background: 'var(--bg-base)', borderRadius: 2, overflow: 'hidden' }}>
                <div style={{ width: '60%', height: '100%', background: '#6366f1', transition: 'width 0.5s' }}></div>
              </div>
            </div>
          )}
        </div>
      )}

      {latest?.skipReason && (
        <div style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)', fontStyle: 'italic' }}>
          {latest.skipReason}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 2, flexWrap: 'wrap' }}>
        <button
          className="btn-ghost-sf"
          disabled={busy || agentId == null}
          onClick={() => abMutation.mutate()}
          style={{ fontSize: 11, padding: '3px 10px' }}
          title={
            agentId == null
              ? 'Pick a source agent in the Skills header first'
              : 'Fork this skill and launch an A/B test against the parent'
          }
        >
          {busy ? 'Starting…' : 'Fork & A/B Test'}
        </button>

        {showManualPromote && (
          <button
            className="btn-ghost-sf"
            disabled={promoteMutation.isPending}
            onClick={handleManualPromote}
            style={{ fontSize: 11, padding: '3px 10px', color: 'var(--accent, #6366f1)' }}
            data-testid="manual-promote-btn"
            title="Promote the candidate anyway, ignoring auto thresholds"
          >
            {promoteMutation.isPending ? 'Promoting…' : 'Promote anyway'}
          </button>
        )}

        {showRollback && (
          <button
            className="btn-ghost-sf"
            disabled={rollbackMutation.isPending}
            onClick={handleRollback}
            style={{ fontSize: 11, padding: '3px 10px', color: 'var(--color-err, #f0616d)' }}
            data-testid="rollback-btn"
            title="Disable this candidate and re-enable the parent skill"
          >
            {rollbackMutation.isPending ? 'Rolling back…' : 'Rollback to parent'}
          </button>
        )}

        {flash && (
          <span style={{ fontSize: 11, color: 'var(--accent, #6366f1)' }}>
            {flash}
          </span>
        )}
      </div>
    </div>
  );
};
