import React, { useEffect, useState } from 'react';

interface InflightTool {
  name: string;
  input: unknown;
  startTs: number;
}

interface ActivityRailProps {
  inflightTools: Record<string, InflightTool>;
  runtimeStatus: string;
  agentName?: string;
}

type RailTab = 'agents' | 'events';

const formatElapsed = (ms: number): string => {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}m ${r.toString().padStart(2, '0')}s`;
};

const ActivityRail: React.FC<ActivityRailProps> = ({ inflightTools, runtimeStatus, agentName }) => {
  const [tab, setTab] = useState<RailTab>('agents');
  const [now, setNow] = useState(Date.now());
  const entries = Object.entries(inflightTools);

  useEffect(() => {
    if (entries.length === 0) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [entries.length]);

  const earliestStart = entries.reduce<number | null>((acc, [, info]) => {
    if (acc == null) return info.startTs;
    return info.startTs < acc ? info.startTs : acc;
  }, null);

  const laneCount = entries.length;
  const headerElapsed = earliestStart ? formatElapsed(now - earliestStart) : '—';

  const laneStatus = runtimeStatus === 'error' ? 'err' : runtimeStatus === 'running' ? 'run' : 'idle';

  return (
    <aside className="sf-activity-rail" aria-label="Activity rail">
      <div className="sf-rail-header">
        <span className="sf-rail-title">Activity</span>
        <span className="sf-rail-count">{laneCount}</span>
        {agentName && (
          <span className="sf-rail-agent" title="Agent">{agentName}</span>
        )}
        <span className="sf-rail-elapsed">{headerElapsed}</span>
      </div>
      <div className="sf-rail-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'agents'}
          className={`sf-rail-tab${tab === 'agents' ? ' sf-rail-tab--active' : ''}`}
          onClick={() => setTab('agents')}
        >
          Agents
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'events'}
          className={`sf-rail-tab${tab === 'events' ? ' sf-rail-tab--active' : ''}`}
          onClick={() => setTab('events')}
        >
          Events
        </button>
      </div>
      <div className="sf-rail-body">
        {tab === 'agents' ? (
          entries.length === 0 ? (
            <div className="sf-rail-empty">
              {runtimeStatus === 'running' ? 'Agent is thinking…' : 'No active tools'}
            </div>
          ) : (
            entries.map(([id, info]) => {
              const elapsed = formatElapsed(now - info.startTs);
              const statusClass =
                laneStatus === 'err' ? 'sf-lane-status sf-lane-status--err' : 'sf-lane-status';
              const statusLabel = laneStatus === 'err' ? 'error' : 'running';
              return (
                <div key={id} className="sf-lane-item">
                  <div className="sf-lane-head">
                    <span className="sf-lane-name">{info.name}</span>
                    <span className={statusClass}>{statusLabel}</span>
                    <span className="sf-lane-elapsed">{elapsed}</span>
                  </div>
                  <div className="sf-lane-progress" aria-hidden="true">
                    <div className="sf-lane-progress-bar" />
                  </div>
                </div>
              );
            })
          )
        ) : (
          <div className="sf-rail-empty">Event stream coming soon</div>
        )}
      </div>
    </aside>
  );
};

export default ActivityRail;
