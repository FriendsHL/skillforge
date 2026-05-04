import React, { useEffect } from 'react';
import { Tag, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { getSkillDetail } from '../../api';
import type { SkillRow, SkillDetailData } from './types';
import { CLOSE_ICON, COPY_ICON } from './icons';
import { SkillAbPanel } from './SkillAbPanel';
import { SkillEvolutionPanel } from './SkillEvolutionPanel';
import { timeAgo } from './utils';

interface SkillDrawerProps {
  skill: SkillRow;
  tab: string;
  setTab: (t: string) => void;
  onClose: () => void;
  onToggle: (id: number | string, enabled: boolean) => void;
  onDelete: (id: number | string) => void;
  currentUserId?: number;
  /**
   * P1-C-7: source agent chosen on the SkillList page header. When null the
   * Evolution panel renders a hint instead of starting a run.
   */
  sourceAgentId: number | null;
}

export const SkillDrawer: React.FC<SkillDrawerProps> = ({
  skill, tab, setTab, onClose, onToggle, onDelete, currentUserId, sourceAgentId,
}) => {
  const { data: detail, isLoading } = useQuery<SkillDetailData>({
    queryKey: ['skill-detail', skill.id],
    queryFn: () => getSkillDetail(skill.id).then(r => r.data),
    enabled: typeof skill.id === 'number',
  });

  const showAbPanel = !skill.system && typeof skill.id === 'number';

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const tabs = [
    { id: 'readme', label: 'SKILL.md' },
    { id: 'files', label: 'Files', count: detail?.references ? Object.keys(detail.references).length : 0 },
    { id: 'source', label: 'Source' },
    ...(skill.toolSchema ? [{ id: 'schema', label: 'Schema' }] : []),
  ];

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div>
              <h2 className="sf-drawer-title">
                {skill.name}
                {skill.semver && (
                  <span
                    style={{
                      fontSize: 11,
                      padding: '1px 6px',
                      borderRadius: 3,
                      background: 'var(--bg-hover, #1d1d22)',
                      color: 'var(--fg-4, #8a8a93)',
                      fontFamily: 'var(--font-mono, monospace)',
                      marginLeft: 8,
                      verticalAlign: 'middle',
                      fontWeight: 400,
                    }}
                  >
                    {skill.semver}
                  </span>
                )}
              </h2>
              {skill.description && <p className="sf-drawer-subtitle">{skill.description}</p>}
              {skill.parentSkillId != null && (
                <div style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)', marginTop: 2 }}>
                  Forked from skill #{skill.parentSkillId}
                </div>
              )}
              {skill.usageCount != null && skill.usageCount > 0 && (
                <div style={{ fontSize: 11, color: 'var(--fg-4, #8a8a93)', marginTop: 4 }}>
                  {skill.usageCount} uses · {skill.successCount ?? 0} succeeded
                  {(skill.failureCount ?? 0) > 0 && (
                    <> · {skill.failureCount} failed</>
                  )}
                  <span style={{ marginLeft: 6 }}>
                    ({Math.round(((skill.successCount ?? 0) / skill.usageCount) * 100)}% success)
                  </span>
                </div>
              )}
              {showAbPanel && typeof skill.id === 'number' && (
                <>
                  <SkillAbPanel skillId={skill.id} />
                  {sourceAgentId != null ? (
                    <SkillEvolutionPanel
                      skillId={skill.id}
                      agentId={sourceAgentId}
                      currentUserId={currentUserId}
                    />
                  ) : (
                    <div
                      style={{
                        marginTop: 10,
                        paddingTop: 10,
                        borderTop: '1px solid var(--border-subtle, #2a2a31)',
                        fontSize: 11,
                        color: 'var(--fg-4, #8a8a93)',
                      }}
                    >
                      <span style={{ fontWeight: 600, color: 'var(--fg-3, #a8a8b1)' }}>Evolution</span>{' '}
                      — pick a source agent on the Skills header to enable.
                    </div>
                  )}
                </>
              )}
            </div>
            <div className="sf-drawer-actions">
              {/*
                P1-D system-skill governance: system skills must remain visible
                in the drawer (operators still need to inspect them) but the
                Disable / Delete actions are removed; the backend rejects them
                anyway (fail-secure 403 from T7).
              */}
              {!skill.system ? (
                <>
                  <button className="btn-ghost-sf" onClick={() => onToggle(skill.id, !skill.enabled)}>
                    {skill.enabled ? 'Disable' : 'Enable'}
                  </button>
                  <button className="btn-ghost-sf" style={{ color: 'var(--color-err)' }} onClick={() => onDelete(skill.id)}>
                    Delete
                  </button>
                </>
              ) : (
                // Ant Design Tooltip footgun: a disabled native <button> swallows
                // pointer events so the tooltip never fires. Wrap in a span so
                // the hover lands on the wrapper instead.
                <Tooltip title="System skill 不可删除">
                  <span style={{ display: 'inline-block', cursor: 'not-allowed' }}>
                    <button
                      className="btn-ghost-sf"
                      style={{ color: 'var(--color-err)', opacity: 0.45, pointerEvents: 'none' }}
                      disabled
                      aria-disabled="true"
                      data-testid="system-delete-disabled"
                    >
                      Delete
                    </button>
                  </span>
                </Tooltip>
              )}
            </div>
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`skill-lang-sf lang-${skill.lang}`}>{skill.lang}</span>
            <span className={`skill-source-sf src-${skill.source}`}>{skill.originSource ?? skill.source}</span>
            <span className={`status-pill-sf ${skill.enabled ? 's-ok' : 's-draft'}`}>
              <span className="status-dot-sf" /> {skill.enabled ? 'active' : 'disabled'}
            </span>
            {skill.isSystem && (
              <Tag color="blue" style={{ marginInlineEnd: 0, textTransform: 'lowercase' }}>system</Tag>
            )}
            {skill.artifactStatus && skill.artifactStatus !== 'active' && (
              <Tooltip title={skill.shadowedBy ? `Shadowed by ${skill.shadowedBy}` : undefined}>
                <Tag
                  color={
                    skill.artifactStatus === 'missing' ? 'orange'
                    : skill.artifactStatus === 'invalid' ? 'error'
                    : 'gold'
                  }
                  style={{ marginInlineEnd: 0, textTransform: 'lowercase' }}
                >
                  {skill.artifactStatus}
                </Tag>
              </Tooltip>
            )}
          </div>
          {(skill.skillPath || skill.shadowedBy || skill.lastScannedAt) && (
            <dl
              data-testid="governance-meta"
              style={{
                margin: '10px 0 0',
                padding: '10px 0 0',
                borderTop: '1px solid var(--border-subtle, #2a2a31)',
                display: 'grid',
                gridTemplateColumns: 'max-content 1fr',
                rowGap: 4,
                columnGap: 12,
                fontSize: 11,
                color: 'var(--fg-4, #8a8a93)',
              }}
            >
              {skill.skillPath && (
                <>
                  <dt style={{ fontWeight: 600, color: 'var(--fg-3, #a8a8b1)' }}>Path</dt>
                  <dd
                    style={{
                      margin: 0,
                      fontFamily: 'var(--font-mono, monospace)',
                      wordBreak: 'break-all',
                    }}
                  >
                    {skill.skillPath}
                  </dd>
                </>
              )}
              {skill.shadowedBy && (
                <>
                  <dt style={{ fontWeight: 600, color: 'var(--fg-3, #a8a8b1)' }}>Shadowed by</dt>
                  <dd style={{ margin: 0, fontFamily: 'var(--font-mono, monospace)' }}>{skill.shadowedBy}</dd>
                </>
              )}
              {skill.lastScannedAt && (
                <>
                  <dt style={{ fontWeight: 600, color: 'var(--fg-3, #a8a8b1)' }}>Last scanned</dt>
                  <dd style={{ margin: 0 }}>
                    {timeAgo(skill.lastScannedAt)}
                    <span style={{ marginLeft: 6, opacity: 0.6 }}>({skill.lastScannedAt})</span>
                  </dd>
                </>
              )}
            </dl>
          )}
        </div>

        <nav className="sf-drawer-tabs">
          {tabs.map(t => (
            <button key={t.id} className={`sf-drawer-tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>
              {t.label}
              {'count' in t && t.count != null && <span className="badge">{t.count}</span>}
            </button>
          ))}
        </nav>

        <div className="sf-drawer-body">
          {isLoading && typeof skill.id === 'number' && (
            <div className="sf-empty-state">Loading…</div>
          )}

          {tab === 'readme' && (
            <div className="sf-code-block" style={{ whiteSpace: 'pre-wrap' }}>
              {detail?.skillMd || detail?.promptContent || skill.description || 'No SKILL.md content.'}
            </div>
          )}

          {tab === 'files' && (
            detail?.references && Object.keys(detail.references).length > 0 ? (
              <div className="sf-bundle-tree">
                <div className="sf-bundle-h">
                  <span>Bundle contents</span>
                  <span className="sf-mono-sm">{Object.keys(detail.references).length} files</span>
                </div>
                {Object.entries(detail.references).map(([name, content]) => (
                  <div key={name} className="sf-bundle-row">
                    <span className={`skill-lang-sf lang-${name.split('.').pop() || 'md'}`}>{name.split('.').pop()}</span>
                    <span className="sf-mono-sm">{name}</span>
                    <span className="sf-mono-sm" style={{ marginLeft: 'auto' }}>
                      {typeof content === 'string' ? `${(content.length / 1024).toFixed(1)} KB` : '—'}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <div className="sf-empty-state">No reference files.</div>
            )
          )}

          {tab === 'source' && (
            detail?.scripts && detail.scripts.length > 0 ? (
              detail.scripts.map(s => (
                <div key={s.name} style={{ marginBottom: 14 }}>
                  <div className="sf-code-bar">
                    <span>{s.name}</span>
                    <button className="sf-mini-btn" style={{ marginLeft: 'auto' }}>{COPY_ICON} copy</button>
                  </div>
                  <pre className="sf-code-block">{s.content}</pre>
                </div>
              ))
            ) : (
              <div className="sf-empty-state">No source scripts.</div>
            )
          )}

          {tab === 'schema' && skill.toolSchema != null && (
            <div>
              <div className="sf-section-h">Input schema</div>
              <pre className="sf-code-block">{JSON.stringify(skill.toolSchema, null, 2)}</pre>
            </div>
          )}
        </div>
      </aside>
    </>
  );
};
