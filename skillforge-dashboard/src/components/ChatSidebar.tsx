import { useMemo } from 'react';
import { IconPlus, IconTeam } from './chat/ChatIcons';

interface AgentLite {
  id: number;
  name: string;
  description?: string | null;
  online?: boolean;
  sessions?: number;
}

interface SessionLite {
  id?: string;
  sessionId?: string;
  agentId?: number | string | null;
  title?: string | null;
  collabRunId?: string | null;
  messageCount?: number | null;
  updatedAt?: string | null;
  [key: string]: unknown;
}

interface ChatSidebarProps {
  agents: AgentLite[];
  sessions: SessionLite[];
  selectedAgent: number | undefined;
  activeSessionId: string | undefined;
  onSelectAgent: (id: number) => void;
  onNewChat: () => void;
  onSelectSession: (sid: string) => void;
  loading?: boolean;
}

const formatRelative = (iso?: string | null): string => {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const diffMs = Date.now() - d.getTime();
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDay = Math.floor(diffHr / 24);
  if (diffDay < 7) return `${diffDay}d ago`;
  return d.toLocaleDateString();
};

const isToday = (iso?: string | null): boolean => {
  if (!iso) return false;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return false;
  const now = new Date();
  return (
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  );
};

function ChatSidebar({
  agents,
  sessions,
  selectedAgent,
  activeSessionId,
  onSelectAgent,
  onNewChat,
  onSelectSession,
  loading,
}: ChatSidebarProps) {
  const agentSessionCounts = useMemo(() => {
    const counts: Record<number, number> = {};
    for (const s of sessions) {
      if (typeof s.agentId === 'number') {
        counts[s.agentId] = (counts[s.agentId] ?? 0) + 1;
      }
    }
    return counts;
  }, [sessions]);

  const onlineCount = agents.filter((a) => a.online).length;

  const { todaySessions, earlierSessions } = useMemo(() => {
    const today: SessionLite[] = [];
    const earlier: SessionLite[] = [];
    for (const s of sessions) {
      if (isToday(s.updatedAt)) today.push(s);
      else earlier.push(s);
    }
    return { todaySessions: today, earlierSessions: earlier };
  }, [sessions]);

  const renderSession = (s: SessionLite) => {
    const sid = String(s.id ?? s.sessionId ?? '');
    const isActive = activeSessionId === sid;
    const title =
      s.title && s.title !== 'New Session'
        ? s.title
        : `Session ${sid.slice(0, 8)}`;
    return (
      <button
        key={sid}
        type="button"
        className={`session-row ${isActive ? 'active' : ''}`}
        onClick={() => onSelectSession(sid)}
        aria-current={isActive ? 'true' : undefined}
      >
        <div className="session-title">
          {s.collabRunId && (
            <span className="team-badge" title="Team session">
              <IconTeam s={9} /> team
            </span>
          )}
          <span>{title}</span>
        </div>
        <div className="session-sub">
          {typeof s.messageCount === 'number' && (
            <>
              <span>{s.messageCount} msgs</span>
              <span className="dot">·</span>
            </>
          )}
          <span>{formatRelative(s.updatedAt)}</span>
        </div>
      </button>
    );
  };

  return (
    <aside className="side">
      <div className="side-head">
        <div className="side-label">
          <span>Agents</span>
          <span style={{ color: 'var(--fg-4)' }}>{onlineCount} online</span>
        </div>
        <div className="agent-picker">
          {agents.map((a) => {
            const count = agentSessionCounts[a.id] ?? 0;
            return (
              <button
                key={a.id}
                type="button"
                className={`agent-row ${selectedAgent === a.id ? 'active' : ''}`}
                onClick={() => onSelectAgent(a.id)}
              >
                <span className={`agent-dot ${a.online ? 'online' : ''}`} />
                <div className="agent-name">{a.name}</div>
                <span className="agent-meta">{count}</span>
              </button>
            );
          })}
          {agents.length === 0 && (
            <div style={{ padding: 10, fontSize: 12, color: 'var(--fg-4)' }}>
              No agents available
            </div>
          )}
        </div>
      </div>

      <button type="button" className="new-chat-btn" onClick={onNewChat}>
        <IconPlus s={13} /> New chat
      </button>

      <div className="side-sessions">
        {loading ? (
          <div style={{ padding: 12, fontSize: 12, color: 'var(--fg-4)' }}>
            Loading sessions…
          </div>
        ) : sessions.length === 0 ? (
          <div style={{ padding: 12, fontSize: 12, color: 'var(--fg-4)' }}>
            No sessions yet
          </div>
        ) : (
          <>
            {todaySessions.length > 0 && (
              <>
                <div className="session-group-label">Today</div>
                {todaySessions.map(renderSession)}
              </>
            )}
            {earlierSessions.length > 0 && (
              <>
                <div className="session-group-label">Earlier</div>
                {earlierSessions.map(renderSession)}
              </>
            )}
          </>
        )}
      </div>

      <div className="side-foot">
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10 }}>
          skillforge
        </span>
        <span
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 10,
            color: 'var(--ok)',
          }}
        >
          ● connected
        </span>
      </div>
    </aside>
  );
}

export default ChatSidebar;
