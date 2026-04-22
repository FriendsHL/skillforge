import React from 'react';
import type { AgentDto } from '../../api/schemas';

interface AgentCardProps {
  agent: AgentDto;
  onOpen: (agent: AgentDto) => void;
}

function initials(name: string): string {
  const parts = name.replace(/[·•]/g, ' ').split(/\s+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return name.slice(0, 2).toUpperCase();
}

function parseCount(raw: unknown): number {
  if (!raw) return 0;
  if (Array.isArray(raw)) return raw.length;
  if (typeof raw === 'string') {
    try {
      const arr = JSON.parse(raw);
      return Array.isArray(arr) ? arr.length : 0;
    } catch {
      return 0;
    }
  }
  return 0;
}

function parseBehaviorRuleCount(raw: unknown): number {
  if (!raw) return 0;
  try {
    const cfg = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (cfg && typeof cfg === 'object') {
      const builtin = cfg.builtinRuleIds;
      const custom = cfg.customRules;
      let count = 0;
      if (Array.isArray(builtin)) count += builtin.length;
      if (Array.isArray(custom)) count += custom.length;
      return count;
    }
  } catch { /* ignore */ }
  return 0;
}

function parseHookCount(raw: unknown): number {
  if (!raw) return 0;
  try {
    let data = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (data && typeof data === 'object' && 'hooks' in data) {
      data = (data as Record<string, unknown>).hooks;
    }
    if (Array.isArray(data)) return data.length;
    if (data && typeof data === 'object') {
      return Object.values(data).reduce<number>(
        (sum, arr) => sum + (Array.isArray(arr) ? arr.length : 0), 0,
      );
    }
  } catch { /* ignore */ }
  return 0;
}

function Sparkline({ seed = 7, hotAt = 4 }: { seed?: number; hotAt?: number }) {
  const bars = Array.from({ length: 12 }).map((_, i) => {
    const v = ((Math.sin((seed + i) * 1.7) + 1.2) * 0.5) * 100 + 10;
    return Math.min(100, v);
  });
  return (
    <div className="agent-mini-spark" title="Activity">
      {bars.map((h, i) => (
        <span key={i} style={{ height: `${Math.max(2, h * 0.16)}px` }} className={i >= hotAt ? 'hot' : ''} />
      ))}
    </div>
  );
}

function guessRole(agent: AgentDto): string {
  const name = (agent.name || '').toLowerCase();
  const desc = (agent.description || '').toLowerCase();
  const combined = name + ' ' + desc;
  if (combined.includes('review') || combined.includes('审查')) return 'reviewer';
  if (combined.includes('judge') || combined.includes('eval') || combined.includes('评估')) return 'judge';
  if (combined.includes('writer') || combined.includes('draft') || combined.includes('write')) return 'writer';
  if (combined.includes('lead') || combined.includes('leader') || combined.includes('plan') || combined.includes('主')) return 'leader';
  return 'leader';
}

const AgentCard: React.FC<AgentCardProps> = React.memo(({ agent, onOpen }) => {
  const role = guessRole(agent);
  const skillCount = parseCount(agent.skillIds);
  const toolCount = parseCount(agent.toolIds);
  const ruleCount = parseBehaviorRuleCount(agent.behaviorRules);
  const hookCount = parseHookCount(agent.lifecycleHooks);
  const mode = agent.executionMode || 'ask';

  return (
    <div className="agent-card" onClick={() => onOpen(agent)}>
      <div className="agent-card-head">
        <div className={`agent-mark ${role}`}>
          {initials(agent.name)}
        </div>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div className="agent-name">{agent.name}</div>
          <div className="agent-handle">
            {agent.modelId || 'default'} · {mode}
          </div>
        </div>
      </div>
      {agent.description && <p className="agent-desc">{agent.description}</p>}

      <div className="agent-stats">
        <div className="agent-stat">
          <div className="agent-stat-n">{ruleCount}</div>
          <div className="agent-stat-l">rules</div>
        </div>
        <div className="agent-stat">
          <div className="agent-stat-n">{hookCount}</div>
          <div className="agent-stat-l">hooks</div>
        </div>
        <div className="agent-stat">
          <div className="agent-stat-n">{skillCount}</div>
          <div className="agent-stat-l">skills</div>
        </div>
      </div>

      <div className="agent-meta-row">
        <span className="agent-meta-tag">{toolCount === 0 ? 'all' : toolCount} tools</span>
        <span className="agent-meta-dot" />
        <span className="agent-meta-tag">{mode}</span>
        <span style={{ marginLeft: 'auto' }}>
          <Sparkline seed={agent.id * 3} hotAt={6} />
        </span>
      </div>
    </div>
  );
});

export default AgentCard;
export { initials, parseCount, guessRole };
