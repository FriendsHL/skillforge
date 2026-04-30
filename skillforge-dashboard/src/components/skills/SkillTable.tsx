import { Tag, Tooltip } from 'antd';
import type { SkillRow, SkillArtifactStatus } from './types';
import { timeAgo } from './utils';

interface SkillTableProps {
  rows: SkillRow[];
  onOpenDetail: (s: SkillRow) => void;
}

/**
 * Color-code artifact lifecycle status (P1-D governance):
 * - active   → green (matches existing s-ok pill)
 * - missing  → orange (artifact dir gone)
 * - invalid  → red (SKILL.md / bundle corrupt)
 * - shadowed → gold (same-name conflict, deferred to a peer)
 *
 * Returns Ant Design Tag color tokens.
 */
function artifactStatusColor(status: SkillArtifactStatus): string {
  switch (status) {
    case 'active': return 'success';
    case 'missing': return 'orange';
    case 'invalid': return 'error';
    case 'shadowed': return 'gold';
  }
}

interface ArtifactStatusBadgeProps {
  status: SkillArtifactStatus;
  shadowedBy?: string;
}

function ArtifactStatusBadge({ status, shadowedBy }: ArtifactStatusBadgeProps) {
  const tag = (
    <Tag color={artifactStatusColor(status)} style={{ marginInlineEnd: 0, textTransform: 'lowercase' }}>
      {status}
    </Tag>
  );
  if (status === 'shadowed' && shadowedBy) {
    return <Tooltip title={`Shadowed by ${shadowedBy}`}>{tag}</Tooltip>;
  }
  return tag;
}

export function SkillTable({ rows, onOpenDetail }: SkillTableProps) {
  return (
    <table className="skills-table-sf">
      <thead>
        <tr>
          <th>Name</th>
          <th>Description</th>
          <th>Source</th>
          <th>Status</th>
          <th>Updated</th>
          <th>Last scanned</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(s => {
          const artifactStatus = s.artifactStatus ?? 'active';
          const isMissing = artifactStatus === 'missing';
          // Missing artifacts get a desaturated row to telegraph the row is
          // half-broken without removing it from the table (operator still
          // needs to see it to take action).
          const rowStyle: React.CSSProperties | undefined = isMissing
            ? { opacity: 0.55 }
            : undefined;
          // Display source: prefer the backend provenance string when present
          // (upload / skill-creator / skillhub / …); fall back to the legacy
          // system/custom UI category for older rows.
          const sourceLabel = s.originSource ?? s.source;
          return (
            <tr key={s.id} onClick={() => onOpenDetail(s)} style={rowStyle}>
              <td>
                <div className="t-name-sf">
                  <span className={`skill-lang-sf lang-${s.lang}`}>{s.lang}</span>
                  <b>{s.name}</b>
                  {s.semver && (
                    <span
                      style={{
                        fontSize: 10,
                        padding: '1px 5px',
                        borderRadius: 3,
                        background: 'var(--bg-hover, #1d1d22)',
                        color: 'var(--fg-4, #8a8a93)',
                        fontFamily: 'var(--font-mono, monospace)',
                        marginLeft: 6,
                        verticalAlign: 'middle',
                      }}
                    >
                      {s.semver}
                    </span>
                  )}
                  {s.isSystem && (
                    <Tag
                      color="blue"
                      style={{ marginInlineStart: 6, textTransform: 'lowercase' }}
                      data-testid="system-tag"
                    >
                      system
                    </Tag>
                  )}
                </div>
              </td>
              <td>{s.description || '—'}</td>
              <td><span className={`skill-source-sf src-${s.source}`}>{sourceLabel}</span></td>
              <td>
                <div style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                  <ArtifactStatusBadge
                    status={artifactStatus}
                    shadowedBy={s.shadowedBy}
                  />
                  {!s.enabled && (
                    <span className="status-pill-sf s-draft">
                      <span className="status-dot-sf" /> disabled
                    </span>
                  )}
                </div>
              </td>
              <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>{timeAgo(s.createdAt)}</td>
              <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>{timeAgo(s.lastScannedAt)}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
