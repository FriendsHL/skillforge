import React, { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import {
  listPatternMembers,
  type PatternListItem,
  type PatternMemberItem,
} from '../../api/insights';
import './insights.css';

dayjs.extend(relativeTime);

export interface PatternDetailDrawerProps {
  pattern: PatternListItem | null;
  open: boolean;
  onClose: () => void;
}

const OUTCOME_CLS: Record<string, string> = {
  success: 'ins-tag-green',
  partial_success: 'ins-tag-blue',
  failure: 'ins-tag-red',
  cancelled: 'ins-tag-default',
};

const SURFACE_CLS: Record<string, string> = {
  skill: 'ins-tag-geekblue',
  prompt: 'ins-tag-purple',
  behavior_rule: 'ins-tag-magenta',
  other: 'ins-tag-orange',
  unclear: 'ins-tag-default',
};

const DEFAULT_LIMIT = 100;
const EXPANDED_LIMIT = 500;

function fmtAbsolute(iso: string | null | undefined): string {
  if (!iso) return '';
  return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
}

function fmtRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  return dayjs(iso).fromNow();
}

const PatternDetailDrawer: React.FC<PatternDetailDrawerProps> = ({
  pattern,
  open,
  onClose,
}) => {
  const [limit, setLimit] = useState<number>(DEFAULT_LIMIT);

  const { data: members = [], isLoading } = useQuery({
    queryKey: ['pattern-members', pattern?.id, limit],
    queryFn: () =>
      pattern
        ? listPatternMembers(pattern.id, limit).then((r) => r.data ?? [])
        : Promise.resolve<PatternMemberItem[]>([]),
    enabled: open && pattern !== null,
  });

  useEffect(() => {
    setLimit(DEFAULT_LIMIT);
  }, [pattern?.id]);

  const showLoadMore =
    pattern !== null &&
    pattern.memberCount > limit &&
    limit < EXPANDED_LIMIT &&
    members.length === limit;

  const handleCopy = () => {
    if (pattern?.signature) {
      navigator.clipboard.writeText(pattern.signature);
    }
  };

  if (!open || !pattern) return null;

  return (
    <>
      {/* Overlay */}
      <div className="ins-drawer-overlay" onClick={onClose} />

      {/* Drawer panel */}
      <div className="ins-drawer">
        {/* Header */}
        <div className="ins-drawer-header">
          <span className="ins-drawer-title">
            Pattern #{pattern.id}
          </span>
          <span className={`ins-tag ${OUTCOME_CLS[pattern.outcome] ?? 'ins-tag-default'}`}>
            {pattern.outcome}
          </span>
          <span className={`ins-tag ${SURFACE_CLS[pattern.suspectSurface] ?? 'ins-tag-default'}`}>
            {pattern.suspectSurface}
          </span>
          <button className="ins-drawer-close" onClick={onClose}>×</button>
        </div>

        {/* Body */}
        <div className="ins-drawer-body">
          {/* Signature block */}
          <div className="ins-sig-block">
            {pattern.signature}
            <button className="ins-sig-copy" onClick={handleCopy} title="Copy signature">
              ⧉
            </button>
          </div>

          {/* Descriptions grid */}
          <div className="ins-desc-grid">
            <div className="ins-desc-item">
              <span className="ins-desc-label">Top failing tool</span>
              <span className="ins-desc-value">{pattern.topFailingTool ?? '—'}</span>
            </div>
            <div className="ins-desc-item">
              <span className="ins-desc-label">Agent id</span>
              <span className="ins-desc-value ins-mono">
                {pattern.agentId !== null && pattern.agentId !== undefined
                  ? `#${pattern.agentId}`
                  : '—'}
              </span>
            </div>
            <div className="ins-desc-item">
              <span className="ins-desc-label">Member count</span>
              <span className="ins-desc-value" style={{ fontWeight: 600 }}>
                {pattern.memberCount}
              </span>
            </div>
            <div className="ins-desc-item">
              <span className="ins-desc-label">Suggested surface</span>
              <span className="ins-desc-value">
                {pattern.suggestedSurface ?? <span className="ins-dim">(V2 only)</span>}
              </span>
            </div>
            <div className="ins-desc-item">
              <span className="ins-desc-label">First seen</span>
              <span className="ins-desc-value" title={fmtAbsolute(pattern.firstSeenAt)}>
                {fmtRelative(pattern.firstSeenAt)}
              </span>
            </div>
            <div className="ins-desc-item">
              <span className="ins-desc-label">Last seen</span>
              <span className="ins-desc-value" title={fmtAbsolute(pattern.lastSeenAt)}>
                {fmtRelative(pattern.lastSeenAt)}
              </span>
            </div>
          </div>

          {/* Member sessions */}
          <div className="ins-section-header">
            <span className="ins-section-title">
              Member sessions{' '}
              <span className="ins-section-meta">
                ({members.length} of {pattern.memberCount})
              </span>
            </span>
            {showLoadMore && (
              <button
                className="ins-btn-link"
                onClick={() => setLimit(EXPANDED_LIMIT)}
                disabled={isLoading}
              >
                {isLoading ? 'Loading…' : `Load up to ${EXPANDED_LIMIT}`}
              </button>
            )}
          </div>

          {/* Members table */}
          {isLoading ? (
            <div className="ins-empty">
              <p className="ins-empty-title">Loading…</p>
            </div>
          ) : members.length === 0 ? (
            <div className="ins-empty">
              <p className="ins-empty-title">No member sessions</p>
            </div>
          ) : (
            <div className="ins-table-wrap">
              <table className="ins-table">
                <thead>
                  <tr>
                    <th className="col-session">Session</th>
                    <th className="col-agent-name">Agent</th>
                    <th className="col-completed">Completed</th>
                    <th className="col-drawer-outcome">Outcome</th>
                    <th>Reasoning</th>
                  </tr>
                </thead>
                <tbody>
                  {members.map((m) => (
                    <tr key={m.sessionId}>
                      <td className="col-mono">
                        <Link
                          to={`/traces?q=${encodeURIComponent(m.sessionId)}`}
                          style={{ color: 'var(--accent-primary)', textDecoration: 'none' }}
                        >
                          {m.sessionId.slice(0, 16)}…
                        </Link>
                      </td>
                      <td className="ins-dim">{m.agentName ?? '—'}</td>
                      <td className="col-dim" title={fmtAbsolute(m.completedAt)}>
                        {fmtRelative(m.completedAt)}
                      </td>
                      <td>{m.outcome ?? '—'}</td>
                      <td title={m.outcomeReasoning ?? undefined}>
                        {m.outcomeReasoning ? (
                          <span style={{ fontSize: 12, display: 'block', maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {m.outcomeReasoning}
                          </span>
                        ) : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </>
  );
};

export default PatternDetailDrawer;
