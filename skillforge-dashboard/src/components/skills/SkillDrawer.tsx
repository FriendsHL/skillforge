import React, { useEffect, useState } from 'react';
import { Tag, Tooltip, Button, Switch, Empty, Divider, Modal, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getSkillDetail, getSkillVersionTree } from '../../api';
import type { SkillRow, SkillDetailData } from './types';
import { CLOSE_ICON } from './icons';
import { SkillAbPanel } from './SkillAbPanel';
import { SkillEvolutionPanel } from './SkillEvolutionPanel';
import { EvalHistoryPanel } from './EvalHistoryPanel';
import { VersionTreeView } from './VersionTreeView';
import { SkillMdDiff } from './SkillMdDiff';
import { SkillFileBrowser } from './SkillFileBrowser';

// Helper to ensure version strings always look like "vX.Y.Z"
const formatVer = (ver: string | number | undefined | null): string => {
  if (ver == null) return 'v0.0.0';
  const str = String(ver);
  return str.startsWith('v') ? str : `v${str}`;
};

interface SkillDrawerProps {
  skill: SkillRow;
  tab: string;
  setTab: (t: string) => void;
  onClose: () => void;
  onToggle: (id: number | string, enabled: boolean) => void;
  onDelete: (id: number | string) => void;
  currentUserId?: number;
  sourceAgentId: number | null;
  onOpenSkill?: (skillId: number) => void;
  /**
   * V2.5 — sibling versions (same owner + name) including orphans. When provided,
   * the drawer renders a flat list (replaces the chain-only Version Tree). When
   * absent, falls back to the legacy VersionTreeView so the drawer still works
   * if a caller hasn't migrated yet.
   */
  siblingVersions?: SkillRow[];
}

export const SkillDrawer: React.FC<SkillDrawerProps> = ({
  skill, tab, setTab, onClose, onToggle, onDelete, currentUserId, sourceAgentId, onOpenSkill,
  siblingVersions,
}) => {
  const queryClient = useQueryClient();

  // State for version viewing and comparison
  const [viewingSkillId, setViewingSkillId] = useState<number>(skill.id as number);
  const [compareMode, setCompareMode] = useState(false);

  const { data: detail } = useQuery<SkillDetailData>({
    queryKey: ['skill-detail', viewingSkillId],
    queryFn: () => getSkillDetail(viewingSkillId).then(r => r.data),
    enabled: viewingSkillId != null,
  });

  // V2.5 — removed the auto-fallback-to-Live effect.
  //   Old behavior: if the viewed candidate had no SKILL.md, force-switched to
  //     Live and toasted "Switched to Live version".
  //   New UX: the flat version list lets the operator click any row (including
  //     broken / archived rows where artifacts are missing on disk), and
  //     SkillFileBrowser shows its own empty state ("No files found"). The toast
  //     was racing with the skill.id-change reset effect and surfacing on
  //     unrelated transitions (e.g. opening tool-call-retry whose only version
  //     has a missing path was wrongly toasted as a "fallback").

  // Fetch base version for comparison (default to the previous version in tree or the live one)
  const baseSkillId = compareMode && viewingSkillId !== skill.id ? skill.id : null;
  const { data: baseDetail } = useQuery<SkillDetailData>({
    queryKey: ['skill-detail', baseSkillId],
    queryFn: () => getSkillDetail(baseSkillId!).then(r => r.data),
    enabled: !!baseSkillId,
  });

  useEffect(() => {
    setViewingSkillId(skill.id as number);
    setCompareMode(false);
  }, [skill.id]);

  const showAbPanel = !skill.system && typeof skill.id === 'number';

  // Tabs definition
  const tabs = [
    { id: 'readme', label: 'SKILL.md' },
    ...(showAbPanel ? [{ id: 'ab-test', label: 'A/B Test' }] : []),
    { id: 'eval-history', label: 'Eval History' },
  ];

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer sf-drawer--wide" role="dialog" style={{ display: 'flex', flexDirection: 'column' }}>
        
        {/* Top Header */}
        <div className="sf-drawer-head" style={{ flexShrink: 0 }}>
          <div className="sf-drawer-head-row">
            <div>
              <h2 className="sf-drawer-title">{skill.name}</h2>
              {skill.description && <p className="sf-drawer-subtitle">{skill.description}</p>}
            </div>
            <button className="sf-icon-btn" onClick={onClose}>{CLOSE_ICON}</button>
          </div>
          
          <nav className="sf-drawer-tabs" style={{ marginTop: 12 }}>
            {tabs.map(t => (
              <button key={t.id} className={`sf-drawer-tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>
                {t.label}
              </button>
            ))}
          </nav>
        </div>

        {/* Main Layout: Left Sidebar + Right Content */}
        <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
          
          {/* Left Sidebar: Version Navigation */}
          <div style={{ 
            width: 240, 
            borderRight: '1px solid var(--border-subtle)', 
            background: 'var(--bg-hover)', 
            overflowY: 'auto', 
            padding: '16px 0' 
          }}>
            <div style={{ padding: '0 16px', marginBottom: 12 }}>
              <h4 style={{ margin: 0, fontSize: 11, color: 'var(--fg-3)', letterSpacing: 1 }}>VERSIONS</h4>
            </div>
            {/* V2.5 — Flat version list (replaces the chain-only tree).
                Per user 2026-05-08: 不要 tree UX, 还是要罗列各个版本.
                When siblingVersions is supplied (parent already aggregated by name),
                render that — includes orphans (parent_skill_id missing) which the
                tree endpoint silently omitted. When absent, fall back to legacy
                VersionTreeView so older callers keep working. */}
            {skill.system ? (
              <div style={{ padding: '0 16px', fontSize: 11, color: 'var(--fg-4)', lineHeight: 1.6 }}>
                {'System skills are managed via the marketplace and have a single live version — no fork/promote workflow.'}
              </div>
            ) : siblingVersions && siblingVersions.length > 0 ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '0 8px' }}>
                {[...siblingVersions]
                  .sort((a, b) => {
                    // enabled first; then by created_at desc.
                    if (!!a.enabled !== !!b.enabled) return a.enabled ? -1 : 1;
                    const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
                    const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
                    return tb - ta;
                  })
                  .map((v) => {
                    const vId = typeof v.id === 'number' ? v.id : null;
                    if (vId == null) return null;
                    const isViewing = viewingSkillId === vId;
                    return (
                      <div
                        key={vId}
                        onClick={() => setViewingSkillId(vId)}
                        style={{
                          padding: '6px 10px',
                          borderRadius: 4,
                          cursor: 'pointer',
                          background: isViewing ? 'var(--accent-soft, rgba(99,102,241,0.18))' : 'transparent',
                          color: isViewing ? 'var(--accent-primary, #8b8df5)' : 'var(--fg-2, #c0c0c8)',
                          fontFamily: 'var(--font-mono, monospace)',
                          fontSize: 12,
                          display: 'flex',
                          alignItems: 'baseline',
                          justifyContent: 'space-between',
                          gap: 6,
                        }}
                      >
                        <span style={{ fontWeight: isViewing ? 700 : 500 }}>
                          {formatVer(v.semver)}
                        </span>
                        <span style={{ fontSize: 10, color: 'var(--fg-4, #8a8a93)' }}>
                          {v.enabled ? 'live' : v.parentSkillId != null ? 'candidate' : 'archived'}
                        </span>
                      </div>
                    );
                  })}
              </div>
            ) : typeof skill.id === 'number' ? (
              <VersionTreeView
                skillId={skill.id as number}
                userId={currentUserId || 0}
                currentLiveId={skill.id as number}
                onView={(id) => setViewingSkillId(id)}
                onOpenSkill={onOpenSkill}
              />
            ) : (
              <div style={{ padding: '0 16px', fontSize: 11, color: 'var(--fg-4)', lineHeight: 1.6 }}>
                {skill.system
                  ? 'System skills are managed via the marketplace and have a single live version — no fork/promote workflow.'
                  : 'No version data available.'}
              </div>
            )}

            {/* Note: To show ALL versions including orphans, we would need to pass the full grouped list from the parent. 
                For now, the Tree shows the main evolution path. Orphaned versions can be managed from the main Skills List. */}
            <div style={{ margin: '16px 16px 0', borderTop: '1px solid var(--border-subtle)', paddingTop: 12 }}>
              <p style={{ fontSize: 10, color: 'var(--fg-4)', lineHeight: 1.5, margin: 0 }}>
                💡 <strong>Tip:</strong> Only linked evolutions are shown here. To manage parallel/orphaned versions with the same name, please use the main Skills List.
              </p>
            </div>
          </div>

          {/* Right Content Area */}
          <div style={{ flex: 1, overflowY: 'auto', padding: 24, background: 'var(--bg-surface)' }}>
            
            {tab === 'readme' && (
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                  <h3 style={{ margin: 0, fontSize: 16 }}>SKILL.md Content</h3>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <span style={{ fontSize: 12, color: 'var(--fg-3)' }}>Compare Versions</span>
                    <Switch checked={compareMode} onChange={setCompareMode} size="small" />
                    <Tag color={viewingSkillId === skill.id ? 'green' : 'default'}>
                      {viewingSkillId === skill.id ? 'Live Version' : `Viewing ${detail?.semver?.startsWith('v') ? detail.semver : `v${detail?.semver}`}`}
                    </Tag>
                    
                    {/* Delete Button for Candidate Versions */}
                    {viewingSkillId !== skill.id && (
                      <Button 
                        danger 
                        type="text"
                        size="small" 
                        onClick={() => {
                          const verName = detail?.semver?.startsWith('v') ? detail.semver : `v${detail?.semver}`;
                          Modal.confirm({
                            title: 'Delete this version?',
                            content: (
                              <div>
                                <p>Are you sure you want to delete <strong>{verName}</strong>?</p>
                                <p style={{ fontSize: 12, color: 'var(--fg-3)', marginTop: 8 }}>
                                  ⚠️ Note: This will only remove this specific version. The Live version ({formatVer(skill.semver)}) will remain unaffected.
                                </p>
                              </div>
                            ),
                            okText: 'Delete Version',
                            okButtonProps: { danger: true },
                            onOk: async () => {
                              try {
                                await onDelete(viewingSkillId);
                                // V2.5 — parent (SkillList) deleteMutation onSuccess already fires
                                // a `message.success('Skill deleted')`. Don't add a second one
                                // here or the operator gets two toasts for one action.

                                // Invalidate version tree cache to remove the deleted node from sidebar
                                queryClient.invalidateQueries({ queryKey: ['skill-version-tree', skill.id] });

                                // Reset viewing to the live version after deletion
                                setViewingSkillId(skill.id as number);
                                setCompareMode(false);
                              } catch (error) {
                                message.error('Failed to delete version. Please try again.');
                              }
                            }
                          });
                        }}
                      >
                        Delete Version
                      </Button>
                    )}
                  </div>
                </div>

                {compareMode && baseDetail ? (
                  <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 8, overflow: 'hidden' }}>
                    <SkillMdDiff
                      parent={baseDetail.skillMd || ''}
                      candidate={detail?.skillMd || ''}
                    />
                  </div>
                ) : (
                  /* V2.5 — replace single-pre SKILL.md rendering with full file browser
                     (skill packages have references/, scripts/, hooks/, assets/ — only
                     SKILL.md was visible before).
                     Resolves into: numeric viewing id for user skills,
                     "system-<name>" for system skills (id passed as the system row's
                     prefixed string from listSkills). */
                  <div style={{ height: 'calc(100vh - 220px)' }}>
                    <SkillFileBrowser
                      skillId={
                        skill.system && typeof skill.id === 'string'
                          ? skill.id
                          : viewingSkillId
                      }
                      userId={currentUserId || 0}
                    />
                  </div>
                )}
              </div>
            )}

            {tab === 'ab-test' && showAbPanel && (
              <div>
                <SkillAbPanel skillId={skill.id as number} agentId={sourceAgentId} skill={skill} />
                {sourceAgentId && <SkillEvolutionPanel skillId={skill.id as number} agentId={sourceAgentId} currentUserId={currentUserId} />}
              </div>
            )}

            {tab === 'eval-history' && typeof skill.id === 'number' && (
              <EvalHistoryPanel skillId={skill.id} currentUserId={currentUserId} agentId={sourceAgentId} />
            )}
          </div>
        </div>
      </aside>
    </>
  );
};
