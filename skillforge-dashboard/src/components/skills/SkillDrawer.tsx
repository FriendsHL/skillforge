import React, { useEffect, useState } from 'react';
import { Tag, Tooltip, Button, Select, Empty, Divider } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { getSkillDetail, getSkillVersionTree } from '../../api';
import type { SkillRow, SkillDetailData } from './types';
import { CLOSE_ICON } from './icons';
import { SkillAbPanel } from './SkillAbPanel';
import { SkillEvolutionPanel } from './SkillEvolutionPanel';
import { EvalHistoryPanel } from './EvalHistoryPanel';
import { VersionTreeView } from './VersionTreeView';

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
}

export const SkillDrawer: React.FC<SkillDrawerProps> = ({
  skill, tab, setTab, onClose, onToggle, onDelete, currentUserId, sourceAgentId, onOpenSkill,
}) => {
  // State to track which version is currently being viewed in the drawer
  const [viewingSkillId, setViewingSkillId] = useState<number>(skill.id as number);

  const { data: detail } = useQuery<SkillDetailData>({
    queryKey: ['skill-detail', viewingSkillId],
    queryFn: () => getSkillDetail(viewingSkillId).then(r => r.data),
    enabled: viewingSkillId != null,
  });

  // Reset viewing ID when the main skill prop changes (e.g., opening a new card)
  useEffect(() => {
    setViewingSkillId(skill.id as number);
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
            <VersionTreeView 
              skillId={skill.id as number} 
              userId={currentUserId || 0} 
              currentLiveId={skill.id as number}
              onView={(id) => setViewingSkillId(id)}
              onOpenSkill={onOpenSkill}
            />
          </div>

          {/* Right Content Area */}
          <div style={{ flex: 1, overflowY: 'auto', padding: 24, background: 'var(--bg-surface)' }}>
            
            {tab === 'readme' && (
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
                  <h3 style={{ margin: 0, fontSize: 16 }}>SKILL.md Content</h3>
                  <Tag color={viewingSkillId === skill.id ? 'green' : 'default'}>
                    {viewingSkillId === skill.id ? 'Live Version' : `Viewing v${detail?.semver}`}
                  </Tag>
                </div>
                <pre className="sf-code-block" style={{ whiteSpace: 'pre-wrap', background: 'var(--bg-base)', padding: 20, borderRadius: 8 }}>
                  {detail?.skillMd || detail?.promptContent || '// No content available for this version.'}
                </pre>
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
