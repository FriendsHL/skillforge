import React, { useState } from 'react';
import { Button, Tag, Divider, Tree, Card, Empty } from 'antd';
import { 
  ArrowLeftOutlined, 
  ExperimentOutlined, 
  CheckCircleOutlined, 
  ThunderboltOutlined,
  FileTextOutlined
} from '@ant-design/icons';
import type { SkillRow } from '../../api';
import { useAuth } from '../../contexts/AuthContext';
import { SkillMdDiff } from './SkillMdDiff';

interface SkillVersionCompareProps {
  skillName: string;
  versions: SkillRow[];
  primary: SkillRow;
  onBack: () => void;
}

/**
 * SKILL-DASHBOARD-POLISH V2.0 — Version Management Center.
 * 
 * Displays all versions of a skill in a tree structure and allows
 * comparison, evaluation, and promotion.
 */
export const SkillVersionCompare: React.FC<SkillVersionCompareProps> = ({ 
  skillName, 
  versions, 
  primary, 
  onBack 
}) => {
  const { userId } = useAuth();
  const [selectedVersionId, setSelectedVersionId] = useState<string | number>(primary.id);
  const [viewMode, setViewMode] = useState<'diff' | 'content'>('diff');

  // 构造 Tree 数据源
  const treeData = versions.map(v => ({
    title: (
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>v{v.semver} {v.enabled && <Tag color="green" style={{ marginLeft: 8, fontSize: 10 }}>Live</Tag>}</span>
        <span style={{ fontSize: 10, color: '#8a8a93' }}>Score: {v.latestEvalScore || 'N/A'}</span>
      </div>
    ),
    key: v.id,
    icon: v.enabled ? <CheckCircleOutlined style={{ color: '#52c41a' }} /> : <FileTextOutlined />,
  }));

  const selectedVersion = versions.find(v => v.id === selectedVersionId) || primary;

  return (
    <div style={{ padding: 24, maxWidth: 1400, margin: '0 auto', height: 'calc(100vh - 60px)', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 24, flexShrink: 0 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={onBack} style={{ marginRight: 16 }}>
          Back to Library
        </Button>
        <h2 style={{ margin: 0, fontSize: 20, color: 'var(--fg-1, #fff)' }}>
          Version Manager: <span style={{ color: 'var(--accent, #6366f1)' }}>{skillName}</span>
        </h2>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '300px 1fr', gap: 24, flex: 1, overflow: 'hidden' }}>
        
        {/* Left: Version Tree */}
        <Card 
          title="Version History" 
          bordered={false}
          style={{ background: 'var(--bg-secondary, #15151a)', borderColor: 'var(--border-subtle, #2a2a31)', height: '100%', overflowY: 'auto' }}
          bodyStyle={{ padding: 12 }}
        >
          <Tree
            treeData={treeData}
            defaultExpandAll
            onSelect={(keys) => keys.length > 0 && setSelectedVersionId(keys[0])}
            selectedKeys={[selectedVersionId]}
            style={{ background: 'transparent', color: 'var(--fg-2, #c0c0c5)' }}
          />
          
          <Divider style={{ borderColor: 'var(--border-subtle, #2a2a31)', margin: '16px 0' }} />
          
          <div style={{ padding: '0 8px' }}>
            <Button 
              type="primary" 
              block 
              icon={<ThunderboltOutlined />}
              disabled={selectedVersion.id === primary.id}
              style={{ marginBottom: 8 }}
            >
              Promote this Version
            </Button>
            <Button block icon={<ExperimentOutlined />}>
              Run Evaluation
            </Button>
          </div>
        </Card>

        {/* Right: Content & Diff */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16, overflow: 'hidden' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0 }}>
            <Tag color="blue">Viewing: v{selectedVersion.semver}</Tag>
            <Button.Group>
              <Button 
                type={viewMode === 'diff' ? 'primary' : 'default'} 
                onClick={() => setViewMode('diff')}
                disabled={!selectedVersion.parentSkillId}
              >
                Compare with Parent
              </Button>
              <Button 
                type={viewMode === 'content' ? 'primary' : 'default'} 
                onClick={() => setViewMode('content')}
              >
                View Content
              </Button>
            </Button.Group>
          </div>

          <div style={{ flex: 1, border: '1px solid var(--border-subtle, #2a2a31)', borderRadius: 8, overflow: 'auto', background: 'var(--bg-secondary, #15151a)' }}>
            {viewMode === 'content' ? (
              <pre style={{ padding: 16, margin: 0, whiteSpace: 'pre-wrap', color: 'var(--fg-2, #c0c0c5)' }}>
                {selectedVersion.skillMd || '// No content available for this version.'}
              </pre>
            ) : (
              selectedVersion.parentSkillId ? (
                <SkillMdDiff 
                  parent="// Parent content loading..." 
                  candidate={selectedVersion.skillMd || '// Candidate content'} 
                />
              ) : (
                <Empty description="This is the root version. No parent to compare against." style={{ marginTop: 48 }} />
              )
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
