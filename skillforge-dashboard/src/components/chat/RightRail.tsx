import { useEffect, useId, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  getSubAgentRuns,
  getContextBreakdown,
  type ContextBreakdown,
  type ContextBreakdownSegment,
} from '../../api';
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

// Palette for top-level segments in the stacked bar — mapped by key.
const SEGMENT_COLORS: Record<string, string> = {
  system_prompt: '#6366f1',
  tool_schemas: '#8b5cf6',
  messages: '#22c55e',
};
const FALLBACK_COLOR = '#64748b';

function colorFor(key: string): string {
  return SEGMENT_COLORS[key] ?? FALLBACK_COLOR;
}

function fmtTokens(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}K`;
  return n.toLocaleString();
}

function ContextTab({
  sessionId,
  userId,
  cumulativeTokens,
  compaction,
  onCompactClick,
  compacting,
}: {
  sessionId?: string | null;
  userId?: number;
  cumulativeTokens?: { input: number; output: number };
  compaction?: CompactionSummary;
  onCompactClick?: () => void;
  compacting?: boolean;
}) {
  const [expanded, setExpanded] = useState<Set<string>>(
    () => new Set(['system_prompt', 'messages']),
  );

  const { data, isLoading, isError, refetch, isFetching } = useQuery<ContextBreakdown>({
    queryKey: ['context-breakdown', sessionId, userId],
    queryFn: async () => {
      if (!sessionId || userId === undefined) {
        throw new Error('missing session / user');
      }
      const res = await getContextBreakdown(sessionId, userId);
      return res.data;
    },
    enabled: Boolean(sessionId && userId !== undefined),
    staleTime: 15_000,
  });

  const toggle = (key: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const total = data?.total ?? 0;
  const windowLimit = data?.windowLimit ?? 0;
  const pct = data?.pct ?? 0;
  const segments: ContextBreakdownSegment[] = data?.segments ?? [];

  return (
    <div>
      <div className="rail-section-title">
        <span>Current context window</span>
        <button
          type="button"
          onClick={() => refetch()}
          disabled={isFetching}
          style={{
            background: 'transparent',
            border: 0,
            color: 'var(--fg-4)',
            cursor: isFetching ? 'default' : 'pointer',
            fontSize: 10,
            fontFamily: 'var(--font-mono)',
            letterSpacing: '0.08em',
            textTransform: 'uppercase',
          }}
          title="Recompute breakdown"
        >
          {isFetching ? 'refreshing…' : 'refresh'}
        </button>
      </div>

      <div
        style={{
          background: 'var(--bg-elev)',
          border: '1px solid var(--border-1)',
          borderRadius: 'var(--radius)',
          padding: 14,
        }}
      >
        {isLoading && (
          <div style={{ fontSize: 12, color: 'var(--fg-4)' }}>Estimating…</div>
        )}
        {isError && (
          <div style={{ fontSize: 12, color: 'var(--err, #ef4444)' }}>
            Failed to load breakdown.{' '}
            <button
              type="button"
              onClick={() => refetch()}
              style={{
                background: 'transparent',
                border: 0,
                color: 'var(--accent)',
                cursor: 'pointer',
                padding: 0,
                font: 'inherit',
              }}
            >
              Retry
            </button>
          </div>
        )}
        {data && (
          <>
            <div
              style={{
                fontFamily: 'var(--font-serif)',
                fontSize: 26,
                fontWeight: 500,
                letterSpacing: '-0.01em',
              }}
            >
              {fmtTokens(total)}{' '}
              <span style={{ color: 'var(--fg-4)', fontSize: 14 }}>
                / {fmtTokens(windowLimit)}
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
              Estimated · {pct}% of window
            </div>
            <StackedBar segments={segments} total={total} />
            <SegmentList
              segments={segments}
              total={total}
              expanded={expanded}
              onToggle={toggle}
            />
            <p
              style={{
                marginTop: 10,
                fontSize: 10,
                color: 'var(--fg-4)',
                lineHeight: 1.5,
              }}
            >
              Tokens estimated with a lightweight heuristic (CJK ≈ 1 tok/char, else
              ~3.5 char/tok); accuracy is ±10%.
            </p>
          </>
        )}
      </div>

      {cumulativeTokens && (
        <div style={{ marginTop: 16 }}>
          <div className="rail-section-title">
            <span>Lifetime usage</span>
          </div>
          <div
            style={{
              fontSize: 12,
              color: 'var(--fg-2)',
              lineHeight: 1.6,
              fontFamily: 'var(--font-mono)',
            }}
          >
            <div>
              <span style={{ color: 'var(--fg-4)' }}>input&nbsp;&nbsp;</span>
              {cumulativeTokens.input.toLocaleString()}
            </div>
            <div>
              <span style={{ color: 'var(--fg-4)' }}>output&nbsp;</span>
              {cumulativeTokens.output.toLocaleString()}
            </div>
            <div style={{ color: 'var(--fg-4)', fontSize: 10, marginTop: 4 }}>
              Charged across the whole session history.
            </div>
          </div>
        </div>
      )}

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

function StackedBar({
  segments,
  total,
}: {
  segments: ContextBreakdownSegment[];
  total: number;
}) {
  if (total <= 0) return null;
  return (
    <div
      role="img"
      aria-label="Context token breakdown"
      style={{
        marginTop: 12,
        display: 'flex',
        height: 10,
        width: '100%',
        borderRadius: 4,
        overflow: 'hidden',
        background: 'var(--bg-hover)',
      }}
    >
      {segments.map((s) => {
        const w = (s.tokens / total) * 100;
        if (w <= 0) return null;
        return (
          <div
            key={s.key}
            title={`${s.label} · ${s.tokens.toLocaleString()} tok (${w.toFixed(1)}%)`}
            style={{
              width: `${w}%`,
              background: colorFor(s.key),
            }}
          />
        );
      })}
    </div>
  );
}

function SegmentList({
  segments,
  total,
  expanded,
  onToggle,
}: {
  segments: ContextBreakdownSegment[];
  total: number;
  expanded: Set<string>;
  onToggle: (key: string) => void;
}) {
  const idPrefix = useId();
  return (
    <div style={{ marginTop: 14, display: 'flex', flexDirection: 'column', gap: 10 }}>
      {segments.map((s) => {
        const pct = total > 0 ? (s.tokens / total) * 100 : 0;
        const hasChildren = Array.isArray(s.children) && s.children.length > 0;
        const isOpen = expanded.has(s.key);
        const panelId = `${idPrefix}-${s.key}`;
        return (
          <div key={s.key}>
            <button
              type="button"
              onClick={() => hasChildren && onToggle(s.key)}
              disabled={!hasChildren}
              style={{
                cursor: hasChildren ? 'pointer' : 'default',
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                width: '100%',
                background: 'transparent',
                border: 0,
                padding: 0,
                color: 'inherit',
                textAlign: 'left',
                font: 'inherit',
                outlineOffset: 2,
              }}
              aria-expanded={hasChildren ? isOpen : undefined}
              aria-controls={hasChildren ? panelId : undefined}
            >
              <span
                style={{
                  width: 8,
                  height: 8,
                  borderRadius: 2,
                  background: colorFor(s.key),
                  flexShrink: 0,
                }}
              />
              <span
                style={{
                  fontSize: 12,
                  color: 'var(--fg-2)',
                  flex: 1,
                  minWidth: 0,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {hasChildren && (
                  <span
                    style={{
                      fontFamily: 'var(--font-mono)',
                      fontSize: 9,
                      color: 'var(--fg-4)',
                      marginRight: 6,
                    }}
                  >
                    {isOpen ? '▾' : '▸'}
                  </span>
                )}
                {s.label}
              </span>
              <span
                style={{
                  fontFamily: 'var(--font-mono)',
                  fontSize: 11,
                  color: 'var(--fg-3)',
                  flexShrink: 0,
                }}
              >
                {fmtTokens(s.tokens)}
              </span>
              <span
                style={{
                  fontFamily: 'var(--font-mono)',
                  fontSize: 10,
                  color: 'var(--fg-4)',
                  flexShrink: 0,
                  minWidth: 36,
                  textAlign: 'right',
                }}
              >
                {pct.toFixed(1)}%
              </span>
            </button>
            {hasChildren && isOpen && s.children && (
              <div
                id={panelId}
                style={{
                  marginTop: 6,
                  marginLeft: 16,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 4,
                  borderLeft: '1px solid var(--border-1, rgba(255,255,255,0.08))',
                  paddingLeft: 10,
                }}
              >
                {s.children.map((c) => {
                  const cPct = total > 0 ? (c.tokens / total) * 100 : 0;
                  return (
                    <div
                      key={c.key}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        fontSize: 11,
                        color: 'var(--fg-3)',
                      }}
                    >
                      <span style={{ flex: 1, minWidth: 0 }}>{c.label}</span>
                      <span
                        style={{
                          fontFamily: 'var(--font-mono)',
                          color: 'var(--fg-3)',
                        }}
                      >
                        {fmtTokens(c.tokens)}
                      </span>
                      <span
                        style={{
                          fontFamily: 'var(--font-mono)',
                          color: 'var(--fg-4)',
                          fontSize: 10,
                          minWidth: 36,
                          textAlign: 'right',
                        }}
                      >
                        {cPct.toFixed(1)}%
                      </span>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
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
            sessionId={sessionId}
            userId={userId}
            cumulativeTokens={
              tokenUsage && tokenUsage.breakdown
                ? {
                    input:
                      tokenUsage.breakdown.find((b) => b.label === 'input')?.value ?? 0,
                    output:
                      tokenUsage.breakdown.find((b) => b.label === 'output')?.value ?? 0,
                  }
                : undefined
            }
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
