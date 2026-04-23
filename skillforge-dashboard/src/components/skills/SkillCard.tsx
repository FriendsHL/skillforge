import React from 'react';
import type { SkillRow } from './types';
import { timeAgo } from './utils';

interface SkillCardProps {
  skill: SkillRow;
  onClick: () => void;
}

export function SkillCard({ skill, onClick }: SkillCardProps) {
  return (
    <button className="skill-card-sf" onClick={onClick}>
      <div className="skill-card-head-sf">
        <span className={`skill-lang-sf lang-${skill.lang}`}>{skill.lang}</span>
        <span className="skill-name-sf">
          {skill.name}
          {skill.semver && (
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
              {skill.semver}
            </span>
          )}
        </span>
        {!skill.enabled && <span className="skill-draft-sf">disabled</span>}
        <span className={`skill-source-sf src-${skill.source}`}>{skill.source}</span>
      </div>
      {skill.description && <div className="skill-sum-sf">{skill.description}</div>}
      <div className="skill-meta-sf">
        {skill.createdAt && <span>{timeAgo(skill.createdAt)}</span>}
      </div>
      {skill.tags.length > 0 && (
        <div className="skill-tags-sf">
          {skill.tags.map(t => <span key={t} className="tag-chip-sf">#{t}</span>)}
        </div>
      )}
    </button>
  );
}
