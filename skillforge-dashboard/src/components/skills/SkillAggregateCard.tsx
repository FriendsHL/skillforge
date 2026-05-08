import React from 'react';
import { Tag, Tooltip } from 'antd';
import { ExperimentOutlined, CheckCircleOutlined, HistoryOutlined } from '@ant-design/icons';
import type { SkillRow } from '../../api';
import { formatScore, visualForScore } from './evalScore';
import { timeAgo } from './utils';

interface SkillAggregateCardProps {
  name: string;
  primary: SkillRow;
  versions: SkillRow[];
  onClick: () => void;
}

/**
 * SKILL-DASHBOARD-POLISH V2.0 — Aggregated Card with Version Tree Preview.
 * 
 * This card represents a single Skill Name, aggregating all its versions.
 */
export const SkillAggregateCard: React.FC<SkillAggregateCardProps> = ({ name, primary, versions, onClick }) => {
  const hasMultipleVersions = versions.length > 1;
  const enabledVersion = versions.find(v => v.enabled);
  const candidateVersion = versions.find(v => !v.enabled && v.parentSkillId); // Assuming candidates have parentSkillId
  
  const scoreColor = visualForScore(primary.latestEvalScore || 0).color;

  return (
    <div 
      className="skill-aggregate-card"
      onClick={onClick}
      style={{
        border: '1px solid var(--border-subtle, #2a2a31)',
        borderRadius: 12,
        padding: 20,
        background: 'var(--bg-secondary, #15151a)',
        cursor: 'pointer',
        transition: 'all 0.2s ease',
        position: 'relative',
        display: 'flex',
        flexDirection: 'column',
        gap: 16,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.borderColor = 'var(--accent, #6366f1)';
        e.currentTarget.style.transform = 'translateY(-4px)';
        e.currentTarget.style.boxShadow = '0 8px 24px rgba(0,0,0,0.2)';
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.borderColor = 'var(--border-subtle, #2a2a31)';
        e.currentTarget.style.transform = 'none';
        e.currentTarget.style.boxShadow = 'none';
      }}
    >
      {/* Header: Name & Status */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <h3 style={{ margin: 0, fontSize: 18, fontWeight: 600, color: 'var(--fg-1, #fff)' }}>
          {name}
        </h3>
        {enabledVersion ? (
          <Tag color="success" icon={<CheckCircleOutlined />}>Live</Tag>
        ) : (
          <Tag color="default">Disabled</Tag>
        )}
      </div>

      {/* Description */}
      <p style={{ margin: 0, fontSize: 13, color: 'var(--fg-2, #c0c0c5)', lineHeight: 1.5, flex: 1 }}>
        {primary.description || 'No description provided for this skill.'}
      </p>

      {/* Metrics Row */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0', borderTop: '1px solid var(--border-subtle, #2a2a31)', borderBottom: '1px solid var(--border-subtle, #2a2a31)' }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--fg-3, #8a8a93)', textTransform: 'uppercase' }}>Score</div>
          <div style={{ fontSize: 20, fontWeight: 700, color: scoreColor }}>
            {formatScore(primary.latestEvalScore)}
          </div>
        </div>
        <div style={{ width: 1, height: 32, background: 'var(--border-subtle, #2a2a31)' }} />
        <div>
          <div style={{ fontSize: 10, color: 'var(--fg-3, #8a8a93)', textTransform: 'uppercase' }}>Usage</div>
          <div style={{ fontSize: 20, fontWeight: 700, color: 'var(--fg-1, #fff)' }}>
            {primary.usageCount || 0}
          </div>
        </div>
      </div>

      {/* Version Tree Preview Footer */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--fg-3, #8a8a93)' }}>
          <HistoryOutlined />
          <span>
            <strong style={{ color: 'var(--fg-1, #fff)' }}>{versions.length}</strong> version{versions.length > 1 ? 's' : ''} total
          </span>
        </div>
        
        {candidateVersion && (
          <Tag color="blue" icon={<ExperimentOutlined />} style={{ margin: 0 }}>
            v{candidateVersion.semver} Candidate
          </Tag>
        )}
      </div>
    </div>
  );
};
