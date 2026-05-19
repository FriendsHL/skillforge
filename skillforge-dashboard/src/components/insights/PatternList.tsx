import React from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import type { PatternListItem } from '../../api/insights';
import './insights.css';

dayjs.extend(relativeTime);

export interface PatternListProps {
  patterns: PatternListItem[];
  loading: boolean;
  onRowClick: (pattern: PatternListItem) => void;
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

function memberTagClass(count: number): string {
  if (count >= 10) return 'ins-tag-red';
  if (count >= 5) return 'ins-tag-orange';
  return 'ins-tag-blue';
}

function fmtRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  return dayjs(iso).fromNow();
}

function fmtAbsolute(iso: string | null | undefined): string {
  if (!iso) return '';
  return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
}

const PatternList: React.FC<PatternListProps> = ({ patterns, loading, onRowClick }) => {
  if (loading) {
    return (
      <div className="ins-empty">
        <p className="ins-empty-title">Loading…</p>
      </div>
    );
  }

  if (patterns.length === 0) {
    return (
      <div className="ins-empty">
        <p className="ins-empty-title">No patterns yet</p>
        <p className="ins-empty-desc">
          Wait for the hourly session-annotator cron to run and cluster ≥ 3 members.
        </p>
      </div>
    );
  }

  return (
    <div className="ins-table-wrap">
      <table className="ins-table">
        <thead>
          <tr>
            <th className="col-sig">Signature</th>
            <th className="col-outcome">Outcome</th>
            <th className="col-surface">Surface</th>
            <th className="col-tool">Top failing tool</th>
            <th className="col-agent">Agent</th>
            <th className="col-members">Members</th>
            <th className="col-first">First seen</th>
            <th className="col-last">Last seen</th>
          </tr>
        </thead>
        <tbody>
          {patterns.map((p) => (
            <tr key={p.id} onClick={() => onRowClick(p)}>
              <td className="col-mono" title={p.signature}>
                {p.signature}
              </td>
              <td>
                <span className={`ins-tag ${OUTCOME_CLS[p.outcome] ?? 'ins-tag-default'}`}>
                  {p.outcome}
                </span>
              </td>
              <td>
                <span className={`ins-tag ${SURFACE_CLS[p.suspectSurface] ?? 'ins-tag-default'}`}>
                  {p.suspectSurface}
                </span>
              </td>
              <td className="ins-mono">{p.topFailingTool ?? '—'}</td>
              <td className="ins-mono">
                {p.agentId !== null && p.agentId !== undefined ? `#${p.agentId}` : '—'}
              </td>
              <td className="col-right">
                <span className={`ins-tag ${memberTagClass(p.memberCount)}`}>
                  {p.memberCount}
                </span>
              </td>
              <td className="col-dim" title={fmtAbsolute(p.firstSeenAt)}>
                {fmtRelative(p.firstSeenAt)}
              </td>
              <td title={fmtAbsolute(p.lastSeenAt)}>
                {fmtRelative(p.lastSeenAt)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default PatternList;
