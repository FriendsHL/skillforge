import type { SkillRow } from './types';
import { timeAgo } from './utils';

interface SkillTableProps {
  rows: SkillRow[];
  onOpenDetail: (s: SkillRow) => void;
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
        </tr>
      </thead>
      <tbody>
        {rows.map(s => (
          <tr key={s.id} onClick={() => onOpenDetail(s)}>
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
              </div>
            </td>
            <td>{s.description || '—'}</td>
            <td><span className={`skill-source-sf src-${s.source}`}>{s.source}</span></td>
            <td>
              <span className={`status-pill-sf ${s.enabled ? 's-ok' : 's-draft'}`}>
                <span className="status-dot-sf" /> {s.enabled ? 'active' : 'disabled'}
              </span>
            </td>
            <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>{timeAgo(s.createdAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
