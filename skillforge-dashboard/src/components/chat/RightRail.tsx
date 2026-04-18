import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getSubAgentRuns } from '../../api';
import { IconCompact } from './ChatIcons';

interface InflightTool {
  name: string;
  input: unknown;
  startTs: number;
}

export interface CollabMember {
  handle: string;
  sessionId: string;
  runtimeStatus: string;
  agentId: number;
  depth: number;
  role?: 'leader' | 'reviewer' | 'writer' | 'judge' | 'evaluator' | string;
  title?: string;
}

export interface PeerMessage {
  fromHandle: string;
  toHandle: string;
  timestamp?: string;
  isBroadcast?: boolean;
}

export interface SwimSpan {
  session: string;
  type: 'LLM_CALL' | 'TOOL_CALL' | 'PEER_MESSAGE' | 'ASK_USER' | 'COMPACT' | string;
  start: number;
  dur: number;
}

export interface TokenUsage {
  used: number;
  total: number;
  breakdown?: { label: string; value: number }[];
}

export interface CompactionSummary {
  lightCount: number;
  fullCount: number;
  tokensReclaimed: number;
}

interface RightRailProps {
  collabMembers?: CollabMember[];
  collabRunId?: string | null;
  collabHandle?: string | null;
  collabLeaderSessionId?: string | null;
  peerMessages?: PeerMessage[];
  inflightTools: Record<string, InflightTool>;
  runtimeStatus: string;
  swimSpans?: SwimSpan[];
  tokenUsage?: TokenUsage;
  compaction?: CompactionSummary;
  currentSessionId?: string | null;
  sessionId?: string | null;
  userId?: number;
  onCompactClick?: () => void;
  compacting?: boolean;
}

type Tab = 'team' | 'activity' | 'subagent' | 'context';

const formatElapsed = (ms: number): string => {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}s`;
  return `${Math.floor(s / 60)}m ${(s % 60).toString().padStart(2, '0')}s`;
};

function ActivityTab({
  inflightTools,
  runtimeStatus,
}: {
  inflightTools: Record<string, InflightTool>;
  runtimeStatus: string;
}) {
  const [now, setNow] = useState(Date.now());
  const entries = Object.entries(inflightTools);

  useEffect(() => {
    if (entries.length === 0) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [entries.length]);

  if (entries.length === 0) {
    return (
      <div className="rail-empty-rd">
        {runtimeStatus === 'running' ? 'Agent is thinking…' : 'No active tools.'}
      </div>
    );
  }

  return (
    <>
      <div className="rail-section-title">
        <span>Live tools</span>
        <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--fg-4)' }}>
          {entries.length} active
        </span>
      </div>
      {entries.map(([id, t]) => {
        let inputPreview = '';
        try {
          inputPreview = typeof t.input === 'string' ? t.input : JSON.stringify(t.input);
        } catch {
          inputPreview = '';
        }
        return (
          <div key={id} className="lane-item-rd">
            <div className="lane-head">
              <span className="lane-name">{t.name}</span>
              <span className="lane-status-rd">running</span>
              <span className="lane-elapsed">{formatElapsed(now - t.startTs)}</span>
            </div>
            <div className="lane-progress">
              <div className="lane-progress-bar" />
            </div>
            <div
              style={{
                marginTop: 6,
                fontFamily: 'var(--font-mono)',
                fontSize: 11,
                color: 'var(--fg-3)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {inputPreview}
            </div>
          </div>
        );
      })}
    </>
  );
}

function SwimRiver({ spans }: { spans: SwimSpan[] }) {
  if (!spans.length) return null;
  const maxTime = Math.max(...spans.map((s) => s.start + s.dur));
  const sessionsList = Array.from(new Set(spans.map((s) => s.session)));
  const colors: Record<string, string> = {
    LLM_CALL: 'var(--op-think)',
    TOOL_CALL: 'var(--op-exec)',
    PEER_MESSAGE: 'var(--op-write)',
    ASK_USER: 'var(--op-search)',
    COMPACT: 'var(--fg-4)',
  };
  const ticks = [0, 10, 20, 30].filter((t) => t <= maxTime + 2);

  return (
    <div className="swim">
      <div className="swim-axis">
        {ticks.map((t) => (
          <div
            key={t}
            className="swim-tick"
            style={{ left: `${(t / maxTime) * 100}%` }}
          >
            {t}s
          </div>
        ))}
      </div>
      {sessionsList.map((sess) => (
        <div key={sess} className="swim-lane">
          <div className="swim-lane-label">{sess}</div>
          <div className="swim-track">
            {spans
              .filter((s) => s.session === sess)
              .map((s, i) => (
                <div
                  key={i}
                  className="swim-span"
                  title={`${s.type} · ${s.dur}s`}
                  style={{
                    left: `${(s.start / maxTime) * 100}%`,
                    width: `${(s.dur / maxTime) * 100}%`,
                    background: colors[s.type] ?? 'var(--fg-4)',
                  }}
                />
              ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function TeamTab({
  members,
  collabHandle,
  collabRunId,
  collabLeaderSessionId,
  swimSpans,
  currentSessionId,
  peerMessages,
}: {
  members: CollabMember[];
  collabHandle?: string | null;
  collabRunId?: string | null;
  collabLeaderSessionId?: string | null;
  swimSpans?: SwimSpan[];
  currentSessionId?: string | null;
  peerMessages: PeerMessage[];
}) {
  if (!collabRunId || members.length === 0) {
    return (
      <div className="rail-empty-rd">
        Not part of a team run. Launch a team to see conductor here.
      </div>
    );
  }

  const stats = {
    total: members.length,
    running: members.filter((m) => m.runtimeStatus === 'running').length,
    done: members.filter(
      (m) => m.runtimeStatus === 'completed' || m.runtimeStatus === 'COMPLETED',
    ).length,
  };
  const runStatus = stats.running > 0 ? 'running' : 'completed';

  return (
    <>
      <div className="conductor">
        <div className="cond-head">
          <div>
            <div className="cond-title">
              Team{collabHandle ? ` · ${collabHandle}` : ''}
            </div>
            <div className="cond-sub">
              run {collabRunId.slice(0, 8)}… · {members.length} members
            </div>
          </div>
          <div className={`cond-status ${runStatus === 'completed' ? 'completed' : ''}`}>
            <span className="blob" /> {runStatus}
          </div>
        </div>
        <div className="cond-graph">
          {members.map((m) => {
            const role = (m.role ?? 'assistant') as string;
            const active =
              m.sessionId === currentSessionId ||
              (!!collabLeaderSessionId && m.sessionId === collabLeaderSessionId && !currentSessionId);
            return (
              <div
                key={m.sessionId}
                className={`cond-node depth-${Math.min(m.depth, 2)} ${active ? 'active' : ''}`}
              >
                <div
                  className="role-chip"
                  style={{ background: `var(--role-${role}, var(--accent))` }}
                >
                  {role.charAt(0).toUpperCase()}
                </div>
                <span className="handle">{m.handle}</span>
                <span className="node-title">{m.title || `Agent #${m.agentId}`}</span>
                <span className={`node-status ${m.runtimeStatus}`}>{m.runtimeStatus}</span>
              </div>
            );
          })}
        </div>
        {swimSpans && swimSpans.length > 0 && <SwimRiver spans={swimSpans} />}
        <div className="cond-footer">
          <span>
            <strong>{stats.total}</strong> members
          </span>
          <span style={{ color: 'var(--ok)' }}>
            <strong>{stats.running}</strong> running
          </span>
          <span style={{ color: 'var(--info)' }}>
            <strong>{stats.done}</strong> done
          </span>
        </div>
      </div>

      {peerMessages.length > 0 && (
        <div className="peer-card">
          <div className="peer-head">
            <span>Peer messages</span>
            <span>{peerMessages.length}</span>
          </div>
          <div className="peer-body">
            {peerMessages.map((m, i) => (
              <div key={i} className={`peer-row ${m.isBroadcast ? 'broadcast' : ''}`}>
                {m.timestamp && (
                  <span className="peer-time">
                    {new Date(m.timestamp).toLocaleTimeString([], {
                      hour: '2-digit',
                      minute: '2-digit',
                      second: '2-digit',
                    })}
                  </span>
                )}
                <span className="peer-from">{m.fromHandle}</span>
                <span className="peer-arrow">→</span>
                <span className="peer-from">
                  {m.toHandle === '*' ? 'all' : m.toHandle}
                </span>
                {m.isBroadcast && (
                  <span style={{ color: 'var(--info)', fontSize: 10 }}>(broadcast)</span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </>
  );
}

interface SubAgentRun {
  id: string;
  name: string;
  status: string;
  task: string;
  spawnedAt: string;
}

function normalizeSubRun(raw: Record<string, unknown>): SubAgentRun {
  return {
    id: String(raw.sessionId || raw.id || ''),
    name: String(raw.agentName || raw.name || 'SubAgent'),
    status: String(raw.status || raw.runtimeStatus || 'unknown'),
    task: String(raw.task || raw.title || ''),
    spawnedAt: String(raw.createdAt || raw.spawnedAt || ''),
  };
}

const STATUS_COLORS: Record<string, { bg: string; fg: string }> = {
  running:  { bg: 'rgba(74,122,168,0.12)', fg: '#4a7aa8' },
  idle:     { bg: 'rgba(92,138,74,0.12)',  fg: '#5c8a4a' },
  completed:{ bg: 'var(--bg-hover)',        fg: 'var(--fg-3)' },
  done:     { bg: 'var(--bg-hover)',        fg: 'var(--fg-3)' },
  error:    { bg: 'rgba(184,65,47,0.10)',  fg: '#b84130' },
  waiting:  { bg: 'rgba(212,154,58,0.12)', fg: '#d49a3a' },
  waiting_user: { bg: 'rgba(212,154,58,0.12)', fg: '#d49a3a' },
};

function SubAgentTab({ sessionId, userId }: { sessionId?: string | null; userId?: number }) {
  const { data: rawRuns = [] } = useQuery({
    queryKey: ['subagent-runs', sessionId],
    queryFn: () => getSubAgentRuns(sessionId!, userId!).then(res => {
      const d = res.data;
      return Array.isArray(d) ? d : (d as Record<string, unknown[]>)?.content ?? [];
    }),
    enabled: !!sessionId && userId != null,
    refetchInterval: 5000,
  });

  const runs = (rawRuns as Record<string, unknown>[]).map(normalizeSubRun);

  if (runs.length === 0) {
    return (
      <div className="rail-empty-rd">
        No sub-agent runs in this session.
      </div>
    );
  }

  return (
    <>
      <div className="rail-section-title">
        <span>Sub-agents</span>
        <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--fg-4)' }}>{runs.length}</span>
      </div>
      {runs.map(r => (
        <div key={r.id} className="lane-item-rd" style={{ cursor: 'default' }}>
          <div className="lane-head">
            <span className="lane-name">{r.name}</span>
            <span style={{
              fontFamily: 'var(--font-mono)', fontSize: 10, padding: '2px 7px',
              borderRadius: 'var(--radius-pill, 99px)', letterSpacing: '0.04em',
              background: (STATUS_COLORS[r.status] || STATUS_COLORS.done).bg,
              color: (STATUS_COLORS[r.status] || STATUS_COLORS.done).fg,
            }}>
              {r.status}
            </span>
          </div>
          {r.task && (
            <div style={{
              marginTop: 4,
              fontSize: 12,
              color: 'var(--fg-2)',
              lineHeight: 1.4,
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical' as const,
              overflow: 'hidden',
            }}>
              {r.task}
            </div>
          )}
          {r.spawnedAt && (
            <div style={{ marginTop: 4, fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--fg-4)' }}>
              {new Date(r.spawnedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
            </div>
          )}
        </div>
      ))}
    </>
  );
}

function ContextTab({
  tokenUsage,
  compaction,
  onCompactClick,
  compacting,
}: {
  tokenUsage?: TokenUsage;
  compaction?: CompactionSummary;
  onCompactClick?: () => void;
  compacting?: boolean;
}) {
  const used = tokenUsage?.used ?? 0;
  const total = tokenUsage?.total ?? 200000;
  const pct = total > 0 ? Math.min(100, Math.round((used / total) * 100)) : 0;

  return (
    <div>
      <div className="rail-section-title">
        <span>Context budget</span>
      </div>
      <div
        style={{
          background: 'var(--bg-elev)',
          border: '1px solid var(--border-1)',
          borderRadius: 'var(--radius)',
          padding: 14,
        }}
      >
        <div
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: 26,
            fontWeight: 500,
            letterSpacing: '-0.01em',
          }}
        >
          {used.toLocaleString()}{' '}
          <span style={{ color: 'var(--fg-4)', fontSize: 14 }}>
            / {total.toLocaleString()}
          </span>
        </div>
        <div
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 10,
            color: 'var(--fg-4)',
            marginTop: 2,
            textTransform: 'uppercase',
            letterSpacing: '0.08em',
          }}
        >
          Tokens used · {pct}%
        </div>
        <div
          style={{
            height: 6,
            background: 'var(--bg-hover)',
            borderRadius: 3,
            marginTop: 10,
            overflow: 'hidden',
          }}
        >
          <div
            style={{ width: `${pct}%`, height: '100%', background: 'var(--accent)' }}
          />
        </div>
        {tokenUsage?.breakdown && tokenUsage.breakdown.length > 0 && (
          <div
            style={{
              marginTop: 16,
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gap: 10,
              fontFamily: 'var(--font-mono)',
              fontSize: 11,
              color: 'var(--fg-2)',
            }}
          >
            {tokenUsage.breakdown.map((b) => (
              <div key={b.label}>
                <div
                  style={{
                    color: 'var(--fg-4)',
                    fontSize: 10,
                    textTransform: 'uppercase',
                  }}
                >
                  {b.label}
                </div>
                {b.value.toLocaleString()}
              </div>
            ))}
          </div>
        )}
      </div>

      {compaction && (
        <div style={{ marginTop: 16 }}>
          <div className="rail-section-title">
            <span>Compaction</span>
          </div>
          <div style={{ fontSize: 13, color: 'var(--fg-2)', lineHeight: 1.55 }}>
            {compaction.lightCount} light &amp; {compaction.fullCount} full compactions ·
            reclaimed {compaction.tokensReclaimed.toLocaleString()} tokens total.
            {onCompactClick && (
              <div style={{ marginTop: 10 }}>
                <button
                  type="button"
                  className="chip"
                  onClick={onCompactClick}
                  disabled={compacting}
                  style={{ width: '100%', justifyContent: 'center' }}
                >
                  <IconCompact s={11} />{' '}
                  {compacting ? 'Compacting…' : 'Compact full context now'}
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function RightRail({
  collabMembers = [],
  collabRunId,
  collabHandle,
  collabLeaderSessionId,
  peerMessages = [],
  inflightTools,
  runtimeStatus,
  swimSpans,
  tokenUsage,
  compaction,
  currentSessionId,
  sessionId,
  userId,
  onCompactClick,
  compacting,
}: RightRailProps) {
  const [tab, setTab] = useState<Tab>(collabRunId ? 'team' : 'activity');
  const activeToolCount = Object.keys(inflightTools).length;

  return (
    <aside className="rail">
      <div className="rail-tabs">
        <button
          type="button"
          className={`rail-tab ${tab === 'team' ? 'on' : ''}`}
          onClick={() => setTab('team')}
        >
          Team <span className="count">{collabMembers.length}</span>
        </button>
        <button
          type="button"
          className={`rail-tab ${tab === 'activity' ? 'on' : ''}`}
          onClick={() => setTab('activity')}
        >
          Activity <span className="count">{activeToolCount}</span>
        </button>
        <button
          type="button"
          className={`rail-tab ${tab === 'subagent' ? 'on' : ''}`}
          onClick={() => setTab('subagent')}
        >
          SubAgent
        </button>
        <button
          type="button"
          className={`rail-tab ${tab === 'context' ? 'on' : ''}`}
          onClick={() => setTab('context')}
        >
          Context
        </button>
      </div>
      <div className="rail-body">
        {tab === 'team' && (
          <TeamTab
            members={collabMembers}
            collabHandle={collabHandle}
            collabRunId={collabRunId}
            collabLeaderSessionId={collabLeaderSessionId}
            swimSpans={swimSpans}
            currentSessionId={currentSessionId}
            peerMessages={peerMessages}
          />
        )}
        {tab === 'activity' && (
          <ActivityTab inflightTools={inflightTools} runtimeStatus={runtimeStatus} />
        )}
        {tab === 'subagent' && (
          <SubAgentTab sessionId={sessionId} userId={userId} />
        )}
        {tab === 'context' && (
          <ContextTab
            tokenUsage={tokenUsage}
            compaction={compaction}
            onCompactClick={onCompactClick}
            compacting={compacting}
          />
        )}
      </div>
    </aside>
  );
}

export default RightRail;
