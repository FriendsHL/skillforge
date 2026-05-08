import React, { useEffect, useMemo, useState } from 'react';
import { Tag, Tooltip, message, Drawer, Table, Button, Modal } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  evaluateSkill,
  getSkillEvalHistory,
  type EvalHistoryEntry,
} from '../../api';
import { EvalHistoryChart } from './EvalHistoryChart';
import { formatScore, visualForScore } from './evalScore';
import { EyeOutlined } from '@ant-design/icons';

interface EvalHistoryPanelProps {
  skillId: number;
  currentUserId?: number;
  /**
   * Source agent for the manual evaluation. Null when the operator hasn't
   * picked one in the SkillList header — the BE rejects the request with
   * 400 if `agentId` is blank, so we disable the button + render a tooltip
   * hint instead of letting the request fly.
   */
  agentId: number | null;
}

/**
 * SKILL-EVOLVE-LOOP Phase 6 — Evaluation History tab content. Composes
 * the multi-line chart with a "Evaluate Now" button that POSTs to
 * `/api/skills/{id}/evaluate` and refreshes the history once the BE
 * persists the new row (triggered_by='manual').
 *
 * Latest-score chip surfaces above the chart so the operator gets the
 * one-glance health read without parsing the curve.
 */
export const EvalHistoryPanel: React.FC<EvalHistoryPanelProps> = ({
  skillId,
  currentUserId,
  agentId,
}) => {
  const queryClient = useQueryClient();

  const { data: history, isLoading } = useQuery<EvalHistoryEntry[]>({
    queryKey: ['skill-eval-history', skillId, 20],
    queryFn: () =>
      getSkillEvalHistory(skillId, currentUserId ?? 0, 20).then((r) => r.data),
    enabled: !!currentUserId,
    // Mirror SkillList batch query (staleTime 60s) so re-opening the drawer
    // within the same minute doesn't re-fire the GET. Manual evaluate +
    // WS skill_auto_upgraded explicitly invalidate this key, so the staleness
    // window only matters for plain tab/drawer churn.
    staleTime: 60_000,
  });

  const latest = useMemo(() => {
    if (!history || history.length === 0) return undefined;
    return history[0];
  }, [history]);

  // Check if an evaluation was triggered very recently (within last 30s)
  const [recentlyTriggered, setRecentlyTriggered] = useState(false);
  // V2.5 — Eval Detail modal state (replaces "Detail view coming soon" placeholder).
  const [detailRecord, setDetailRecord] = useState<EvalHistoryEntry | null>(null);
  const latestVisual = visualForScore(latest?.compositeScore);

  const evaluateMutation = useMutation({
    mutationFn: () => {
      if (!currentUserId) {
        return Promise.reject(new Error('Sign in required'));
      }
      if (agentId == null) {
        // BE-1 returns 400 when agentId is blank; gate client-side so the
        // operator gets a clearer error than a generic "400 Bad Request".
        return Promise.reject(new Error('Pick a source agent in the Skills header first'));
      }
      return evaluateSkill(skillId, currentUserId, agentId).then((r) => r.data);
    },
    onSuccess: (data) => {
      const score = formatScore(data?.compositeScore);
      message.success(`Evaluation complete · score ${score}`);
      // Refresh both detail and the parent list (for the Trend / Latest cells).
      queryClient.invalidateQueries({ queryKey: ['skill-eval-history', skillId] });
      queryClient.invalidateQueries({ queryKey: ['skill-eval-history-list'] });
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } }; message?: string };
      message.error(e.response?.data?.error || e.message || 'Failed to evaluate skill');
    },
  });

  // After evaluateMutation is initialized, register the success-side-effect.
  useEffect(() => {
    if (evaluateMutation.isSuccess) {
      setRecentlyTriggered(true);
      const timer = setTimeout(() => setRecentlyTriggered(false), 5000);
      return () => clearTimeout(timer);
    }
  }, [evaluateMutation.isSuccess]);

  const disabled = evaluateMutation.isPending || !currentUserId || agentId == null;
  const disabledReason =
    !currentUserId
      ? 'Sign in required'
      : agentId == null
        ? 'Pick a source agent in the Skills header first'
        : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span
            style={{
              fontSize: 11,
              color: 'var(--fg-4, #8a8a93)',
              textTransform: 'uppercase',
              letterSpacing: 0.4,
            }}
          >
            Latest score
          </span>
          <Tag
            color={latestVisual.tagColor}
            style={{
              fontFamily: 'var(--font-mono, monospace)',
              fontSize: 12,
              marginInlineEnd: 0,
            }}
          >
            {formatScore(latest?.compositeScore)}
          </Tag>
          {latest && (
            <span
              style={{
                fontSize: 11,
                color: 'var(--fg-4, #8a8a93)',
                fontFamily: 'var(--font-mono, monospace)',
              }}
            >
              {latest.triggeredBy} · {new Date(latest.createdAt).toLocaleString()}
            </span>
          )}
        </div>
        <Tooltip
          title={
            disabledReason ??
            'Run a single-skill direct evaluation now (writes to t_skill_eval_history)'
          }
        >
          {/* span wrapper so disabled native <button> still hovers — same
              footgun pattern used in SkillDrawer for system-skill delete. */}
          <span style={{ marginLeft: 'auto' }}>
            <button
              className="btn-ghost-sf"
              disabled={disabled}
              onClick={() => evaluateMutation.mutate()}
              style={{ fontSize: 11, padding: '4px 12px' }}
              data-testid="evaluate-now-btn"
            >
              {evaluateMutation.isPending ? 'Evaluating…' : recentlyTriggered ? 'Processing...' : 'Evaluate Now'}
            </button>
          </span>
        </Tooltip>
      </div>

      {/* Running Status Indicator */}
      {(evaluateMutation.isPending || recentlyTriggered) && (
        <div style={{ 
          background: 'rgba(99, 102, 241, 0.1)', 
          border: '1px solid rgba(99, 102, 241, 0.3)', 
          borderRadius: 8, 
          padding: 12, 
          marginBottom: 16,
          display: 'flex',
          alignItems: 'center',
          gap: 12
        }}>
          <div style={{ width: 16, height: 16, border: '2px solid #6366f1', borderTopColor: 'transparent', borderRadius: '50%', animation: 'spin 1s linear infinite' }}></div>
          <div>
            <div style={{ fontSize: 12, fontWeight: 600, color: '#6366f1' }}>Evaluation in Progress</div>
            <div style={{ fontSize: 11, color: 'var(--fg-3)' }}>Running scenarios against the skill. This may take a moment...</div>
          </div>
        </div>
      )}

      <EvalHistoryChart history={history} loading={isLoading} />
      
      {/* Eval History List with Details */}
      <div style={{ marginTop: 16 }}>
        <h4 style={{ fontSize: 12, color: 'var(--fg-3)', marginBottom: 8 }}>Evaluation History</h4>
        {isLoading ? (
          <div style={{ padding: 16, textAlign: 'center', color: 'var(--fg-3)' }}>Loading...</div>
        ) : (
          <Table 
            dataSource={history} 
            rowKey="id" 
            pagination={false} 
            size="small"
            columns={[
              { title: 'Date', dataIndex: 'createdAt', key: 'date', render: (d) => new Date(d).toLocaleString(), width: 160 },
              { title: 'Trigger', dataIndex: 'triggeredBy', key: 'trigger', width: 80, render: (t) => <Tag color={t === 'manual' ? 'blue' : 'default'}>{t}</Tag> },
              { 
                title: 'Score', 
                dataIndex: 'compositeScore', 
                key: 'score', 
                render: (s) => <span style={{ fontWeight: 700, color: visualForScore(s).color }}>{formatScore(s)}</span> 
              },
              {
                title: 'Action',
                key: 'action',
                render: (_, record) => (
                  <Button
                    type="link"
                    size="small"
                    icon={<EyeOutlined />}
                    onClick={() => setDetailRecord(record)}
                  >
                    Details
                  </Button>
                )
              }
            ]}
          />
        )}
      </div>

      <Modal
        title="Evaluation Detail"
        open={detailRecord !== null}
        onCancel={() => setDetailRecord(null)}
        footer={[
          <Button key="close" onClick={() => setDetailRecord(null)}>Close</Button>,
        ]}
        width={520}
      >
        {detailRecord && (() => {
          const dims: Array<[string, number | null | undefined]> = [
            ['Composite', detailRecord.compositeScore],
            ['Quality', detailRecord.qualityScore],
            ['Efficiency', detailRecord.efficiencyScore],
            ['Latency', detailRecord.latencyScore],
            ['Cost', detailRecord.costScore],
          ];
          return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
                <div style={{ fontSize: 12, color: 'var(--fg-4, #8a8a93)' }}>
                  {new Date(detailRecord.createdAt).toLocaleString()}
                </div>
                <Tag color={detailRecord.triggeredBy === 'manual' ? 'blue' : 'default'}>
                  {detailRecord.triggeredBy}
                </Tag>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                {dims.map(([label, val]) => {
                  const visual = visualForScore(val ?? undefined);
                  return (
                    <div
                      key={label}
                      style={{
                        padding: '10px 12px',
                        background: 'var(--bg-hover, #1d1d22)',
                        borderRadius: 6,
                        borderLeft: `3px solid ${visual.color}`,
                      }}
                    >
                      <div style={{ fontSize: 10, color: 'var(--fg-4, #8a8a93)', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                        {label}
                      </div>
                      <div style={{ fontSize: 22, fontWeight: 700, color: visual.color }}>
                        {formatScore(val ?? undefined)}
                      </div>
                    </div>
                  );
                })}
              </div>

              {detailRecord.evalRunId ? (
                <div style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)', fontFamily: 'var(--font-mono, monospace)' }}>
                  evalRunId: {detailRecord.evalRunId}
                </div>
              ) : (
                <div style={{ fontSize: 11, fontStyle: 'italic', color: 'var(--fg-4, #8a8a93)' }}>
                  No eval run id (synthetic / manual baseline-only entry).
                </div>
              )}

              <div style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)', lineHeight: 1.5 }}>
                Per-scenario details (judge rationale / agent output) are available
                via the EVAL framework once an evalRunId-linked detail endpoint is
                wired (V3 follow-up).
              </div>
            </div>
          );
        })()}
      </Modal>
    </div>
  );
};
