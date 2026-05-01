import { useEffect, useId, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
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

interface LoopSpan {
  id: string;
  type: 'LLM_CALL' | 'TOOL_CALL';
  name: string;
  startTs: number;
  endTs?: number;
  status?: 'success' | 'error';
  durationMs?: number;
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
  loopSpans?: LoopSpan[];
}

type Tab = 'context' | 'activity' | 'subagent' | 'team';

const formatElapsed = (ms: number): string => {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}s`;
  return `${Math.floor(s / 60)}m ${(s % 60).toString().padStart(2, '0')}s`;
};

function formatDuration(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

const SPAN_COLORS: Record<string, string> = {
  LLM_CALL: '#818cf8',
  TOOL_CALL: '#f59e0b',
};

function ActivityTab({
  inflightTools,
  runtimeStatus,
  loopSpans,
}: {
  inflightTools: Record<string, InflightTool>;
  runtimeStatus: string;
  loopSpans?: LoopSpan[];
}) {
  const [now, setNow] = useState(Date.now());
  const scrollRef = useRef<HTMLDivElement>(null);
  const hasSpans = loopSpans && loopSpans.length > 0;
  const hasInflight = Object.keys(inflightTools).length > 0;

  useEffect(() => {
    if (!hasInflight && runtimeStatus !== 'running') return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [hasInflight, runtimeStatus]);

  // Auto-scroll to bottom when new spans appear
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [loopSpans?.length]);

  if (!hasSpans && !hasInflight) {
    return (
      <div className="rail-empty-rd">
        {runtimeStatus === 'running' ? 'Agent is thinking…' : 'No activity.'}
      </div>
    );
  }

  const spans = loopSpans ?? [];
  const loopStart = spans.length > 0 ? spans[0].startTs : now;
  const lastSpanEnd = spans.length > 0
    ? Math.max(...spans.map((s) => s.endTs ?? s.startTs))
    : now;
  const loopEnd = runtimeStatus === 'running' ? Math.max(now, lastSpanEnd) : lastSpanEnd;
  const totalMs = Math.max(loopEnd - loopStart, 1);
  const spanSumMs = spans.reduce((sum, s) => sum + (s.endTs ? s.endTs - s.startTs : now - s.startTs), 0);
  const isRunning = runtimeStatus === 'running';
  const doneCount = spans.filter((s) => !!s.endTs).length;
  const errCount = spans.filter((s) => s.status === 'error').length;

  return (
    <div className="mw-container">
      <div className="rail-section-title">
        <span>Loop</span>
        <span className="mw-header-meta">
          {doneCount}/{spans.length} done · {formatElapsed(spanSumMs)}{errCount > 0 && <>{' '}· <span style={{ color: 'var(--err, #ef4444)' }}>{errCount} err</span></>}
        </span>
      </div>

      {/* Time scale */}
      <div className="mw-timescale">
        {[0, 0.5, 1].map((t) => (
          <span key={t} className="mw-tick" style={{ left: `${t * 100}%` }}>
            {formatElapsed(totalMs * t)}
          </span>
        ))}
      </div>

      <div className="mini-waterfall-scroll" ref={scrollRef}>
        <div className="mini-waterfall">
          {/* Agent root bar */}
          <div className={`mw-span mw-agent-root ${isRunning ? 'mw-agent-root--running' : ''}`}>
            <div className="mw-span-head">
              <span className="mw-dot" style={{
                background: isRunning
                  ? undefined  /* CSS handles running pulse with --fg-2 */
                  : errCount > 0
                    ? '#ef4444'
                    : '#16a34a',
                opacity: isRunning ? undefined : 0.8,
              }} />
              <span className="mw-type mw-type--agent">Agent</span>
              <span className="mw-name">Total</span>
              {!isRunning && errCount > 0 && (
                <span className="mw-end-badge mw-end-badge--err">✗ error</span>
              )}
              {!isRunning && errCount === 0 && doneCount === spans.length && spans.length > 0 && (
                <span className="mw-end-badge mw-end-badge--ok">✓ done</span>
              )}
              <span className="mw-dur">{formatElapsed(totalMs)}</span>
            </div>
            <div className="mw-bar-track">
              <div
                className={`mw-bar ${isRunning ? 'mw-bar--live' : ''}`}
                style={{
                  left: '0%',
                  width: '100%',
                  background: isRunning
                    ? '#06b6d4'
                    : errCount > 0
                      ? '#ef4444'
                      : '#16a34a',
                  opacity: isRunning ? 0.35 : 0.5,
                }}
              />
            </div>
          </div>

          {/* Span rows */}
          {spans.map((span) => {
            const done = !!span.endTs;
            const isError = span.status === 'error';
            const color = SPAN_COLORS[span.type] ?? 'var(--fg-4)';
            const spanStart = span.startTs - loopStart;
            const spanDur = done
              ? (span.endTs! - span.startTs)
              : now - span.startTs;
            const barLeft = (spanStart / totalMs) * 100;
            const barWidth = Math.min(Math.max((spanDur / totalMs) * 100, 2), 100 - barLeft);
            const elapsed = done ? formatDuration(spanDur) : formatElapsed(spanDur);

            return (
              <div
                key={span.id}
                className={`mw-span ${done ? 'mw-span--done' : 'mw-span--active'} ${isError ? 'mw-span--err' : ''}`}
              >
                <div className="mw-span-head">
                  <span className="mw-dot" style={{ background: done && !isError ? color : undefined }} />
                  <span className={`mw-type mw-type--${span.type === 'LLM_CALL' ? 'llm' : 'tool'}`}>
                    {span.type === 'LLM_CALL' ? 'LLM' : 'Tool'}
                  </span>
                  <span className="mw-name">{span.name}</span>
                  {done && isError && (
                    <span className="mw-end-badge mw-end-badge--err">✗</span>
                  )}
                  <span className="mw-dur">
                    {!done && <span className="mw-spinner" />}
                    {elapsed}
                  </span>
                </div>
                <div className="mw-bar-track">
                  <div
                    className={`mw-bar ${done ? '' : 'mw-bar--live'} ${isError ? 'mw-bar--err' : ''}`}
                    style={{
                      left: `${barLeft}%`,
                      width: `${barWidth}%`,
                      background: isError ? 'var(--err, #ef4444)' : color,
                    }}
                  />
                </div>
              </div>
            );
          })}
        </div>

        {/* Running indicator — thin pulsing line, not a span row */}
        {isRunning && (
          <div className="mw-running-indicator">
            <span className="mw-running-dot" />
            <span className="mw-running-label">thinking…</span>
          </div>
        )}
      </div>
    </div>
  );
}

function SwimRiver({ spans, members }: { spans: SwimSpan[]; members: CollabMember[] }) {
  if (!spans.length) return null;
  const maxTime = Math.max(...spans.map((s) => s.start + s.dur));
  const sessionsList = Array.from(new Set(spans.map((s) => s.session)));
  const sessionLabel = (sess: string) => {
    const m = members.find((x) => x.sessionId === sess);
    return m ? m.handle : sess.slice(0, 8);
  };
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
      {sessionsList.map((sess) => {
        const m = members.find((x) => x.sessionId === sess);
        return (
          <div key={sess} className="swim-lane">
            <div className="swim-lane-label" title={sess}>
              {sessionLabel(sess)}
            </div>
            <div className="swim-track">
              {spans
                .filter((s) => s.session === sess)
                .map((s, i) => {
                  const peerInfo = (s as SwimSpan & { fromHandle?: string; toHandle?: string; isBroadcast?: boolean }); 
                  const tooltip = peerInfo.toHandle
                    ? `${peerInfo.fromHandle || sess} → ${peerInfo.toHandle}`
                    : `${s.type} · ${s.dur.toFixed(1)}s`;
                  const roleColor = m ? `var(--role-${m.role ?? 'assistant'}, var(--accent))` : colors[s.type] ?? 'var(--fg-4)';
                  return (
                    <div
                      key={i}
                      className="swim-span"
                      title={tooltip}
                      style={{
                        left: `${(s.start / maxTime) * 100}%`,
                        width: `${Math.max(s.dur / maxTime * 100, 1.5)}%`,
                        background: roleColor,
                        borderRadius: 2,
                      }}
                    />
                  );
                })}
            </div>
          </div>
        );
      })}
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
  const [flashNodes, setFlashNodes] = useState<Set<string>>(new Set());
  const prevMsgCount = useRef(peerMessages.length);

  // Flash nodes when new peer messages arrive
  useEffect(() => {
    if (peerMessages.length > prevMsgCount.current && peerMessages.length > 0) {
      const latest = peerMessages[peerMessages.length - 1];
      const ids = new Set<string>();
      const fromM = members.find((m) => m.handle === latest.fromHandle);
      if (fromM) ids.add(fromM.sessionId);
      if (!latest.isBroadcast) {
        const toM = members.find((m) => m.handle === latest.toHandle);
        if (toM) ids.add(toM.sessionId);
      }
      setFlashNodes(ids);
      const timer = setTimeout(() => setFlashNodes(new Set()), 800);
      prevMsgCount.current = peerMessages.length;
      return () => clearTimeout(timer);
    }
    prevMsgCount.current = peerMessages.length;
  }, [peerMessages.length]);

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

  // Build swim spans from peer messages if no swimSpans provided
  const effectiveSwimSpans: SwimSpan[] = swimSpans && swimSpans.length > 0
    ? swimSpans
    : peerMessages.length > 0
      ? (() => {
          const firstTs = peerMessages[0].timestamp
            ? new Date(peerMessages[0].timestamp).getTime()
            : Date.now();
          return peerMessages.map((pm) => {
            const ts = pm.timestamp ? new Date(pm.timestamp).getTime() : Date.now();
            const offset = Math.max(0, (ts - firstTs) / 1000);
            const fromM = members.find((m) => m.handle === pm.fromHandle);
            return {
              session: fromM?.sessionId ?? pm.fromHandle,
              type: 'PEER_MESSAGE' as const,
              start: offset,
              dur: 0.4,
              fromHandle: pm.fromHandle,
              toHandle: pm.toHandle,
              isBroadcast: pm.isBroadcast,
            } as SwimSpan & { fromHandle?: string; toHandle?: string; isBroadcast?: boolean };
          });
        })()
      : [];

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

        {/* Node graph */}
        <div className="cond-graph">
          {members.map((m) => {
            const role = (m.role ?? 'assistant') as string;
            const active =
              m.sessionId === currentSessionId ||
              (!!collabLeaderSessionId && m.sessionId === collabLeaderSessionId && !currentSessionId);
            const flashing = flashNodes.has(m.sessionId);
            return (
              <div
                key={m.sessionId}
                className={`cond-node depth-${Math.min(m.depth, 2)} ${active ? 'active' : ''} ${flashing ? 'flashing' : ''}`}
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

        {/* Swim river (now data-driven from peer messages) */}
        {effectiveSwimSpans.length > 0 && (
          <SwimRiver spans={effectiveSwimSpans} members={members} />
        )}

        {/* Stats */}
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

      {/* Peer message log — prominent, living */}
      {peerMessages.length > 0 && (
        <div className="peer-card">
          <div className="peer-head">
            <span>Messages</span>
            <div className="peer-head-right">
              <span className="peer-legend">
                <span className="peer-legend-dot" /> direct
              </span>
              <span className="peer-legend">
                <span className="peer-legend-dot peer-legend-dot--bc" /> broadcast
              </span>
            </div>
          </div>
          <div className="peer-body peer-body--log">
            {peerMessages.slice(-30).reverse().map((m, i) => {
              const isNew = i === 0; // newest is first in reversed order
              return (
                <div key={`${m.timestamp}-${i}`} className={`peer-row peer-row--log ${m.isBroadcast ? 'broadcast' : ''} ${isNew ? 'peer-row--new' : ''}`}>
                  <span className="peer-time">
                    {m.timestamp
                      ? new Date(m.timestamp).toLocaleTimeString([], {
                          hour: '2-digit',
                          minute: '2-digit',
                          second: '2-digit',
                        })
                      : ''}
                  </span>
                  <span className="peer-arrow peer-arrow--from">{m.fromHandle}</span>
                  <span className="peer-arrow-icon">
                    {m.isBroadcast ? '📢' : '→'}
                  </span>
                  <span className="peer-arrow peer-arrow--to">
                    {m.isBroadcast ? 'all' : m.toHandle}
                  </span>
                </div>
              );
            })}
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
  childSessionId?: string;
  finalMessage?: string;
  completedAt?: string;
}

function normalizeSubRun(raw: Record<string, unknown>): SubAgentRun {
  return {
    id: String(raw.runId || raw.sessionId || raw.id || ''),
    name: String(raw.agentName || raw.childAgentName || raw.name || 'SubAgent'),
    status: String(raw.status || raw.runtimeStatus || 'unknown'),
    task: String(raw.task || raw.title || ''),
    spawnedAt: String(raw.createdAt || raw.spawnedAt || ''),
    childSessionId: raw.childSessionId ? String(raw.childSessionId) : undefined,
    finalMessage: raw.finalMessage ? String(raw.finalMessage) : undefined,
    completedAt: raw.completedAt ? String(raw.completedAt) : undefined,
  };
}

const STATUS_COLORS: Record<string, { bg: string; fg: string }> = {
  running:  { bg: 'rgba(74,122,168,0.12)', fg: '#4a7aa8' },
  RUNNING:  { bg: 'rgba(74,122,168,0.12)', fg: '#4a7aa8' },
  idle:     { bg: 'rgba(92,138,74,0.12)',  fg: '#5c8a4a' },
  completed:{ bg: 'var(--bg-hover)',        fg: 'var(--fg-3)' },
  COMPLETED:{ bg: 'var(--bg-hover)',        fg: 'var(--fg-3)' },
  done:     { bg: 'var(--bg-hover)',        fg: 'var(--fg-3)' },
  error:    { bg: 'rgba(184,65,47,0.10)',  fg: '#b84130' },
  FAILED:   { bg: 'rgba(184,65,47,0.10)',  fg: '#b84130' },
  waiting:  { bg: 'rgba(212,154,58,0.12)', fg: '#d49a3a' },
  waiting_user: { bg: 'rgba(212,154,58,0.12)', fg: '#d49a3a' },
};

const RUNNING_STATUSES = new Set(['running', 'RUNNING', 'in_progress']);

function SubAgentTab({ sessionId, userId }: { sessionId?: string | null; userId?: number }) {
  const navigate = useNavigate();
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

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

  function toggleExpand(id: string, e: React.MouseEvent) {
    e.stopPropagation();
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

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
      {runs.map(r => {
        const isRunning = RUNNING_STATUSES.has(r.status);
        const isExpanded = expandedIds.has(r.id);
        const color = STATUS_COLORS[r.status] || STATUS_COLORS.done;
        const hasChild = !!r.childSessionId;

        return (
          <div
            key={r.id}
            className={`lane-item-rd sa-item ${isRunning ? 'sa-item--running' : ''} ${hasChild ? 'sa-item--clickable' : ''}`}
            onClick={() => { if (hasChild) navigate(`/chat/${r.childSessionId}`); }}
          >
            <div className="lane-head">
              <span className="lane-name">{r.name}</span>
              <span style={{
                fontFamily: 'var(--font-mono)', fontSize: 10, padding: '2px 7px',
                borderRadius: 'var(--radius-pill, 99px)', letterSpacing: '0.04em',
                background: color.bg,
                color: color.fg,
              }}>
                {r.status}
              </span>
              {r.spawnedAt && (
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 9, color: 'var(--fg-4)', flexShrink: 0 }}>
                  {new Date(r.spawnedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </span>
              )}
            </div>

            {/* Task summary */}
            {r.task && (
              <div className="sa-task" onClick={(e) => toggleExpand(r.id, e)}>
                <span className="sa-task-text">{r.task}</span>
                <span className={`sa-expand-icon ${isExpanded ? 'sa-expand-icon--open' : ''}`}>▾</span>
              </div>
            )}

            {/* Progress bar for running */}
            {isRunning && (
              <div className="lane-progress" style={{ marginTop: 6 }}>
                <div className="lane-progress-bar" />
              </div>
            )}

            {/* Expanded details */}
            {isExpanded && r.finalMessage && (
              <div className="sa-detail">
                <div className="sa-detail-label">Result</div>
                <div className="sa-detail-text">{r.finalMessage}</div>
              </div>
            )}
            {isExpanded && !r.finalMessage && isRunning && (
              <div className="sa-detail">
                <div className="sa-detail-text" style={{ opacity: 0.5, fontStyle: 'italic' }}>
                  Working…
                </div>
              </div>
            )}

            {/* Click hint */}
            {hasChild && !isExpanded && (
              <div className="sa-hint">
                {isRunning ? 'View session →' : 'Open session →'}
              </div>
            )}
          </div>
        );
      })}
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
          border: '1px solid var(--border-1, rgba(255,255,255,0.08))',
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
  loopSpans,
}: RightRailProps) {
  const [tab, setTab] = useState<Tab>(collabRunId ? 'team' : 'activity');
  const loopSpanCount = loopSpans?.length ?? 0;
  const activeToolCount = Object.keys(inflightTools).length;
  const badgeCount = loopSpanCount > 0 ? loopSpanCount : activeToolCount;

  return (
    <aside className="rail">
      <div className="rail-tabs">
        <button
          type="button"
          className={`rail-tab ${tab === 'context' ? 'on' : ''}`}
          onClick={() => setTab('context')}
        >
          Context
        </button>
        <button
          type="button"
          className={`rail-tab ${tab === 'activity' ? 'on' : ''}`}
          onClick={() => setTab('activity')}
        >
          Activity <span className="count">{badgeCount}</span>
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
          className={`rail-tab ${tab === 'team' ? 'on' : ''}`}
          onClick={() => setTab('team')}
        >
          Team <span className="count">{collabMembers.length}</span>
        </button>
      </div>
      <div className="rail-body">
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
        {tab === 'activity' && (
          <ActivityTab inflightTools={inflightTools} runtimeStatus={runtimeStatus} loopSpans={loopSpans} />
        )}
        {tab === 'subagent' && (
          <SubAgentTab sessionId={sessionId} userId={userId} />
        )}
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
      </div>
    </aside>
  );
}

export default RightRail;
