import React, { useEffect } from 'react';
import { Card, Col, Row, Tooltip } from 'antd';
import {
  BulbOutlined,
  WarningOutlined,
  ExperimentOutlined,
  DatabaseOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getDashboardSkillSummary, type SkillDashboardSummary } from '../../api';

interface SkillSummaryCardProps {
  userId: number;
}

interface SummaryCellProps {
  label: string;
  value: number | null | undefined;
  sub?: string;
  icon: React.ReactNode;
  /**
   * Optional click target. Passing `null` (instead of a function) marks the
   * cell as "informational" — the cursor stays default and the card has no
   * active state. We deliberately distinguish "no link" from "link disabled
   * because data missing" so the affordance reads correctly.
   */
  onClick?: (() => void) | null;
  tooltip?: string;
}

const formatNumber = (n: number | null | undefined): string =>
  n == null ? '—' : n.toLocaleString();

const SummaryCell: React.FC<SummaryCellProps> = React.memo(
  ({ label, value, sub, icon, onClick, tooltip }) => {
    const clickable = typeof onClick === 'function';
    const cell = (
      <Card
        className="sf-stat-card"
        style={{
          borderRadius: 'var(--radius-md)',
          border: '1px solid var(--border-subtle)',
          cursor: clickable ? 'pointer' : 'default',
          height: '100%',
        }}
        onClick={clickable ? onClick : undefined}
        role={clickable ? 'button' : undefined}
        tabIndex={clickable ? 0 : undefined}
        onKeyDown={
          clickable
            ? (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onClick?.();
                }
              }
            : undefined
        }
      >
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'flex-start',
            gap: 12,
          }}
        >
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="sf-stat-label">{label}</div>
            <div className="sf-stat-metric">{formatNumber(value)}</div>
            {sub && <div className="sf-stat-sub">{sub}</div>}
          </div>
          <div style={{ flexShrink: 0, opacity: 0.85, fontSize: 22, color: 'var(--accent-primary)' }}>
            {icon}
          </div>
        </div>
      </Card>
    );
    if (tooltip) {
      return <Tooltip title={tooltip}>{cell}</Tooltip>;
    }
    return cell;
  },
);
SummaryCell.displayName = 'SkillSummaryCell';

/**
 * SKILL-DASHBOARD-POLISH-V2 §G — top-of-Dashboard skill stats: 5-cell grid
 * of "auto-upgraded this week / pending drafts / failed evolve / total
 * enabled / low-score". Click-through routes:
 *   - autoUpgradedThisWeek  → V3 deep-link (no nav yet, render as info)
 *   - pendingDraftsCount    → /skill-drafts
 *   - failedEvolveThisWeek  → V3 deep-link (no nav yet, render as info)
 *   - totalEnabledSkills    → no click (informational)
 *   - lowScoreSkillsCount   → V3 deep-link (no nav yet, render as info)
 *
 * Self-check #1: empty-state ("—") is reserved for the load-failure path
 * — successful fetch always returns 5 ints (BE coerces nulls to 0).
 */
export const SkillSummaryCard: React.FC<SkillSummaryCardProps> = React.memo(({ userId }) => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const { data, isError } = useQuery<SkillDashboardSummary>({
    queryKey: ['dashboard-skill-summary', userId],
    queryFn: () => getDashboardSkillSummary(userId).then((r) => r.data),
    enabled: !!userId,
    staleTime: 60_000,
    retry: 1,
  });

  /**
   * WS subscription: invalidate on `skill_auto_upgraded` (Phase 5 self-improve
   * cron auto-promotes a candidate) and `skill_draft_extracted` (Tuesday cron
   * lands new drafts). Mirrors the patterns in SkillList.tsx — same socket,
   * same discriminator. Cleanup must close the socket (frontend.md footgun #2).
   */
  useEffect(() => {
    if (!userId) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`,
    );
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as { type?: string };
        if (
          msg.type === 'skill_auto_upgraded' ||
          msg.type === 'skill_draft_extracted'
        ) {
          queryClient.invalidateQueries({ queryKey: ['dashboard-skill-summary'] });
        }
      } catch {
        /* ignore non-JSON */
      }
    };
    return () => {
      try { ws.close(); } catch { /* ignore */ }
    };
  }, [userId, queryClient]);

  // self-check #1 — load failure renders 5 "—" cells, not a banner-level error.
  // The Dashboard already shows a global message.error from the parent on
  // overall fetch failure; we keep the card structure stable so the layout
  // doesn't jump.
  const summary: SkillDashboardSummary | null = isError ? null : data ?? null;

  const cells: SummaryCellProps[] = [
    {
      label: 'Auto-upgraded · 7d',
      value: summary?.autoUpgradedThisWeek,
      sub: 'Promoted by self-improve cron',
      icon: <ThunderboltOutlined />,
      // V3: deep-link to /skills?filter=auto-upgraded — no nav target yet.
      onClick: null,
      tooltip: 'Skills auto-promoted via A/B in the last 7 days. Deep-link filter coming in V3.',
    },
    {
      label: 'Pending drafts',
      value: summary?.pendingDraftsCount,
      sub: 'Awaiting review',
      icon: <BulbOutlined />,
      onClick: () => navigate('/skills'),
      tooltip: 'Click to review extracted skill drafts',
    },
    {
      label: 'Failed evolve · 7d',
      value: summary?.failedEvolveThisWeek,
      sub: 'Skill evolution runs',
      icon: <WarningOutlined />,
      onClick: null,
      tooltip: 'Skill evolution runs that failed in the last 7 days. Deep-link filter coming in V3.',
    },
    {
      label: 'Enabled skills',
      value: summary?.totalEnabledSkills,
      sub: 'System + custom',
      icon: <DatabaseOutlined />,
      // Informational — no click target by design.
      onClick: null,
    },
    {
      label: 'Low-score skills',
      value: summary?.lowScoreSkillsCount,
      sub: 'Composite < 60',
      icon: <ExperimentOutlined />,
      onClick: null,
      tooltip: 'Skills whose latest eval composite is below 60. Deep-link filter coming in V3.',
    },
  ];

  return (
    <Row gutter={[16, 16]} data-testid="skill-summary-card">
      {cells.map((cell) => (
        <Col key={cell.label} xs={24} sm={12} md={12} lg={8} xl={cells.length === 5 ? undefined : 6}
          style={cells.length === 5 ? { flex: '1 1 19%', minWidth: 200 } : undefined}>
          <SummaryCell {...cell} />
        </Col>
      ))}
    </Row>
  );
});
SkillSummaryCard.displayName = 'SkillSummaryCard';
