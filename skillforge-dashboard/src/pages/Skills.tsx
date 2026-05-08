import React, { useState, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getSkills, type SkillRow } from '../api';
import { useAuth } from '../contexts/AuthContext';
import { Badge, Empty, Spin, Tag, Button } from 'antd';
import { ExperimentOutlined, CheckCircleOutlined, HistoryOutlined, ArrowRightOutlined } from '@ant-design/icons';

// --- 内联聚合卡片组件 (确保不依赖外部文件) ---
const SkillCard = ({ name, primary, versions, onClick }: { name: string; primary: SkillRow; versions: SkillRow[]; onClick: () => void }) => {
  const hasCandidate = versions.some(v => !v.enabled && v.parentSkillId);
  return (
    <div 
      onClick={onClick}
      style={{
        border: '1px solid #2a2a31', borderRadius: 12, padding: 20, background: '#15151a',
        cursor: 'pointer', transition: 'all 0.2s', display: 'flex', flexDirection: 'column', gap: 16
      }}
      onMouseEnter={(e) => { e.currentTarget.style.borderColor = '#6366f1'; e.currentTarget.style.transform = 'translateY(-4px)'; }}
      onMouseLeave={(e) => { e.currentTarget.style.borderColor = '#2a2a31'; e.currentTarget.style.transform = 'none'; }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0, color: '#fff', fontSize: 18 }}>{name}</h3>
        {primary.enabled ? <Tag color="success" icon={<CheckCircleOutlined />}>Live</Tag> : <Tag>Disabled</Tag>}
      </div>
      <p style={{ margin: 0, color: '#c0c0c5', fontSize: 13, flex: 1 }}>{primary.description || 'No description.'}</p>
      
      <div style={{ display: 'flex', gap: 12, padding: '12px 0', borderTop: '1px solid #2a2a31', borderBottom: '1px solid #2a2a31' }}>
        <div><div style={{ fontSize: 10, color: '#8a8a93' }}>SCORE</div><div style={{ fontSize: 20, fontWeight: 700, color: primary.latestEvalScore ? '#52c41a' : '#fff' }}>{primary.latestEvalScore || 'N/A'}</div></div>
        <div style={{ width: 1, background: '#2a2a31' }} />
        <div><div style={{ fontSize: 10, color: '#8a8a93' }}>USAGE</div><div style={{ fontSize: 20, fontWeight: 700, color: '#fff' }}>{primary.usageCount || 0}</div></div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12 }}>
        <div style={{ color: '#8a8a93', display: 'flex', gap: 6, alignItems: 'center' }}>
          <HistoryOutlined /> <strong style={{ color: '#fff' }}>{versions.length}</strong> versions
        </div>
        {hasCandidate && <Tag color="blue" icon={<ExperimentOutlined />}>Update Available</Tag>}
      </div>
    </div>
  );
};

// --- 内联版本对比视图 ---
const VersionManager = ({ skillName, versions, primary, onBack }: any) => {
  const [selectedId, setSelectedId] = useState(primary.id);
  const selected = versions.find((v: any) => v.id === selectedId) || primary;

  return (
    <div style={{ padding: 24, height: 'calc(100vh - 60px)', display: 'flex', flexDirection: 'column' }}>
      <div style={{ marginBottom: 24, display: 'flex', alignItems: 'center', gap: 16 }}>
        <Button onClick={onBack} icon={<ArrowRightOutlined style={{ transform: 'rotate(180deg)' }} />}>Back</Button>
        <h2 style={{ margin: 0, color: '#fff' }}>Managing: {skillName}</h2>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '300px 1fr', gap: 24, flex: 1, overflow: 'hidden' }}>
        {/* Left: List */}
        <div style={{ background: '#15151a', border: '1px solid #2a2a31', borderRadius: 8, padding: 16, overflowY: 'auto' }}>
          <h4 style={{ color: '#8a8a93', marginTop: 0 }}>Versions</h4>
          {versions.map((v: any) => (
            <div 
              key={v.id} 
              onClick={() => setSelectedId(v.id)}
              style={{ 
                padding: 12, marginBottom: 8, borderRadius: 6, cursor: 'pointer',
                background: v.id === selectedId ? 'rgba(99, 102, 241, 0.2)' : 'transparent',
                border: v.id === selectedId ? '1px solid #6366f1' : '1px solid transparent'
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#fff' }}>
                <span>v{v.semver}</span>
                {v.enabled && <Tag color="green" style={{ fontSize: 10 }}>Live</Tag>}
              </div>
              <div style={{ fontSize: 11, color: '#8a8a93', marginTop: 4 }}>Score: {v.latestEvalScore || 'N/A'}</div>
            </div>
          ))}
        </div>
        {/* Right: Content */}
        <div style={{ background: '#15151a', border: '1px solid #2a2a31', borderRadius: 8, padding: 24, overflowY: 'auto' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
            <Tag color="blue">Viewing v{selected.semver}</Tag>
            <Button type="primary" disabled={selected.id === primary.id}>Promote this Version</Button>
          </div>
          <pre style={{ whiteSpace: 'pre-wrap', color: '#c0c0c5', fontFamily: 'monospace' }}>
            {selected.skillMd || '// No content available'}
          </pre>
        </div>
      </div>
    </div>
  );
};

const SkillsPage: React.FC = () => {
  const { userId } = useAuth();
  const [selectedGroup, setSelectedGroup] = useState<any>(null);

  const { data: skills, isLoading } = useQuery({
    queryKey: ['skills', userId],
    queryFn: () => getSkills(userId).then(r => r.data),
    enabled: !!userId,
  });

  const groupedSkills = useMemo(() => {
    if (!skills) return [];
    const map = new Map<string, SkillRow[]>();
    for (const s of skills) {
      const key = s.name.trim();
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(s);
    }
    return Array.from(map.entries()).map(([name, versions]) => {
      const sorted = versions.sort((a, b) => (a.enabled === b.enabled ? 0 : a.enabled ? -1 : 1));
      return { name, versions: sorted, primary: sorted[0] };
    });
  }, [skills]);

  if (isLoading) return <div style={{ padding: 48, textAlign: 'center', color: '#fff' }}><Spin /></div>;
  if (selectedGroup) return <VersionManager {...selectedGroup} onBack={() => setSelectedGroup(null)} />;

  return (
    <div style={{ padding: 24, background: '#0f0f12', minHeight: '100vh' }}>
      {/* DEBUG MARKER: If you see this, the new code is loaded! */}
      <div style={{ background: 'red', color: 'white', padding: 10, textAlign: 'center', fontWeight: 'bold', marginBottom: 20 }}>
        ✅ NEW CODE LOADED: V2.0 Aggregated View Active
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 24 }}>
        <h1 style={{ color: '#fff', margin: 0 }}>Skills Library</h1>
        <Badge count={groupedSkills.length} style={{ backgroundColor: '#52c41a' }} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(350px, 1fr))', gap: 20 }}>
        {groupedSkills.map(g => (
          <SkillCard key={g.name} {...g} onClick={() => setSelectedGroup(g)} />
        ))}
      </div>
    </div>
  );
};

export default SkillsPage;
