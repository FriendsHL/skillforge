import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Modal, message } from 'antd';
import {
  getSessions,
  getSessionMessages,
  extractList,
  deleteSessions,
  getContextBreakdown,
  type ContextBreakdown,
  type ContextBreakdownSegment,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import { ChannelBadge } from '../components/channels/ChannelBadge';
import '../components/agents/agents.css';
import '../components/sessions/sessions.css';
import '../components/skills/skills.css';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);
const EXT_ICON = (
  <svg width={10} height={10} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4">
    <path d="M10 3h3v3M13 3l-6 6M7 4H4a1 1 0 0 0-1 1v7a1 1 0 0 0 1 1h7a1 1 0 0 0 1-1V9" />
  </svg>
);
const TRASH_ICON = (
  <svg width={13} height={13} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
    <path d="M2.5 4h11M6 4V2.5h4V4M4 4l.7 9a1 1 0 0 0 1 .9h4.6a1 1 0 0 0 1-.9L12 4M6.5 7v4M9.5 7v4" />
  </svg>
);

interface SessionRow {
  id: string;
  title: string;
  agent: string;
  agentId?: number | string;
  status: string;
  msgs: number;
  tokens: number;
  ctx: number;
  cost: number;
  turns: number;
  channel: string;
  createdAt: string;
  updatedAt: string;
  raw: Record<string, unknown>;
}

function normalizeSession(raw: Record<string, unknown>): SessionRow {
  const id = String(raw.id || '');
  const title = (raw.title as string) || `Session ${id.slice(0, 8)}`;
  const agent = (raw.agentName as string) || String(raw.agentId || 'unknown');
  const msgs = Number(raw.messageCount || 0);
  const tokens = Number(raw.totalInputTokens || 0) + Number(raw.totalOutputTokens || 0);
  const ctx = Math.min(tokens / 200000, 1);
  const cost = tokens * 0.000003;
  const turns = Math.ceil(msgs / 2);
  let status = String(raw.runtimeStatus || 'idle');
  if (status === 'waiting_user') status = 'waiting';
  const channel = String(raw.channelPlatform || 'web').toLowerCase() || 'web';
  return {
    id,
    title,
    agent,
    agentId: raw.agentId as number | undefined,
    status,
    msgs,
    tokens,
    ctx,
    cost,
    turns,
    channel,
    createdAt: String(raw.createdAt || ''),
    updatedAt: String(raw.updatedAt || ''),
    raw,
  };
}

function statusCls(s: string): string {
  if (s === 'running') return 's-running';
  if (s === 'error') return 's-error';
  if (s === 'done') return 's-done';
  if (s === 'waiting') return 's-waiting';
  return 's-idle';
}

function fmtTime(iso: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const now = Date.now();
  const diff = now - d.getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

function FilterItem({ label, count, active, onClick }: { label: string; count: number; active: boolean; onClick: () => void }) {
  return (
    <button className={`filter-item ${active ? 'on' : ''}`} onClick={onClick}>
      <span>{label}</span>
      <span className="filter-item-count">{count}</span>
    </button>
  );
}

interface SelectCheckboxProps {
  checked: boolean;
  indeterminate?: boolean;
  onChange: () => void;
  ariaLabel: string;
}

function SelectCheckbox({ checked, indeterminate, onChange, ariaLabel }: SelectCheckboxProps) {
  const ref = useRef<HTMLInputElement | null>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = Boolean(indeterminate);
  }, [indeterminate]);
  return (
    <input
      ref={ref}
      type="checkbox"
      className="sf-checkbox"
      checked={checked}
      onChange={onChange}
      onClick={(e) => e.stopPropagation()}
      aria-label={ariaLabel}
    />
  );
}

const SessionList: React.FC = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [q, setQ] = useState('');
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [filterAgent, setFilterAgent] = useState<string | null>(null);
  const [filterChannel, setFilterChannel] = useState<string | null>(null);
  const [open, setOpen] = useState<SessionRow | null>(null);
  const [drawerTab, setDrawerTab] = useState('turns');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [deleting, setDeleting] = useState(false);

  const SESSIONS_KEY = useMemo(() => ['sessions', userId] as const, [userId]);

  const { data: rawSessions = [] } = useQuery({
    queryKey: SESSIONS_KEY,
    queryFn: () => getSessions(userId).then(res => extractList<Record<string, unknown>>(res)),
    staleTime: 0,
  });

  const all = useMemo<SessionRow[]>(() => rawSessions.map(normalizeSession), [rawSessions]);
  const agents = useMemo(() => Array.from(new Set(all.map(s => s.agent))).sort(), [all]);
  const channels = useMemo(() => Array.from(new Set(all.map(s => s.channel))).sort(), [all]);

  const rows = useMemo(() => {
    return all.filter(s => {
      if (q) {
        const ql = q.toLowerCase();
        if (!`${s.id} ${s.title} ${s.agent}`.toLowerCase().includes(ql)) return false;
      }
      if (filterStatus && s.status !== filterStatus) return false;
      if (filterAgent && s.agent !== filterAgent) return false;
      if (filterChannel && s.channel !== filterChannel) return false;
      return true;
    });
  }, [all, q, filterStatus, filterAgent, filterChannel]);

  const toggleStatus = (v: string) => setFilterStatus(s => s === v ? null : v);
  const toggleAgent = (v: string) => setFilterAgent(a => a === v ? null : v);
  const toggleChannel = (v: string) => setFilterChannel(c => c === v ? null : v);

  // Drop stale selections when the underlying list updates (e.g. after delete / WS refresh)
  useEffect(() => {
    setSelectedIds(prev => {
      if (prev.size === 0) return prev;
      const valid = new Set(all.map(s => s.id));
      let changed = false;
      const next = new Set<string>();
      prev.forEach(id => {
        if (valid.has(id)) next.add(id);
        else changed = true;
      });
      return changed ? next : prev;
    });
  }, [all]);

  const toggleOne = (id: string) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const visibleIds = useMemo(() => rows.map(r => r.id), [rows]);
  const allVisibleSelected = visibleIds.length > 0 && visibleIds.every(id => selectedIds.has(id));
  const someVisibleSelected = !allVisibleSelected && visibleIds.some(id => selectedIds.has(id));

  const toggleAll = () => {
    setSelectedIds(prev => {
      if (allVisibleSelected) {
        const next = new Set(prev);
        visibleIds.forEach(id => next.delete(id));
        return next;
      }
      const next = new Set(prev);
      visibleIds.forEach(id => next.add(id));
      return next;
    });
  };

  const clearSelection = () => setSelectedIds(new Set());

  const performDelete = async (ids: string[]) => {
    if (ids.length === 0) return;
    setDeleting(true);
    try {
      const res = await deleteSessions(ids, userId);
      const { deleted, skipped } = res.data;
      if (deleted > 0) message.success(`已删除 ${deleted} 个会话`);
      if (skipped && skipped.length > 0) {
        message.warning(`${skipped.length} 个正在运行的会话已跳过`);
      }
      // 保留 skipped（running）会话的选中状态，只取消已成功删除的
      const skippedIds = new Set((skipped ?? []).map(e => e.id));
      setSelectedIds(prev => {
        const next = new Set(prev);
        ids.forEach(id => { if (!skippedIds.has(id)) next.delete(id); });
        return next;
      });
      queryClient.invalidateQueries({ queryKey: SESSIONS_KEY });
    } catch (err) {
      console.error('[deleteSessions] failed', err);
      message.error('删除失败，请稍后重试');
    } finally {
      setDeleting(false);
    }
  };

  const confirmDelete = (ids: string[], title: string, content: string) => {
    Modal.confirm({
      title,
      content,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => performDelete(ids),
    });
  };

  const handleBatchDelete = () => {
    const ids = Array.from(selectedIds);
    if (ids.length === 0) return;
    confirmDelete(
      ids,
      `删除 ${ids.length} 个会话？`,
      '正在运行的会话会被跳过，其余会话将被永久删除。此操作不可撤销。',
    );
  };

  const handleSingleDelete = (row: SessionRow) => {
    confirmDelete(
      [row.id],
      '删除该会话？',
      `会话「${row.title}」将被永久删除。此操作不可撤销。`,
    );
  };

  // WebSocket for live updates
  const wsRef = useRef<WebSocket | null>(null);
  const unmountedRef = useRef(false);

  useEffect(() => {
    unmountedRef.current = false;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const url = `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`;
    try {
      const ws = new WebSocket(url);
      wsRef.current = ws;
      ws.onmessage = () => {
        if (!unmountedRef.current) queryClient.invalidateQueries({ queryKey: SESSIONS_KEY });
      };
      ws.onerror = () => {};
      ws.onclose = () => { if (wsRef.current === ws) wsRef.current = null; };
    } catch { /* ignore */ }
    return () => {
      unmountedRef.current = true;
      const ws = wsRef.current;
      wsRef.current = null;
      if (ws) try { ws.close(); } catch { /* noop */ }
    };
  }, [userId, queryClient, SESSIONS_KEY]);

  const openDetail = (s: SessionRow) => { setOpen(s); setDrawerTab('turns'); };

  return (
    <div className="agents-view">
      <aside className="agents-filters">
        <div className="agents-filters-h">Search</div>
        <input className="agents-search" placeholder="id, title, agent…" value={q} onChange={e => setQ(e.target.value)} />

        <div className="agents-filters-h">Status</div>
        {['running', 'idle', 'done', 'error', 'waiting'].map(s => (
          <FilterItem key={s} label={s} count={all.filter(x => x.status === s).length} active={filterStatus === s} onClick={() => toggleStatus(s)} />
        ))}

        <div className="agents-filters-h">Agent</div>
        {agents.map(a => (
          <FilterItem key={a} label={a} count={all.filter(x => x.agent === a).length} active={filterAgent === a} onClick={() => toggleAgent(a)} />
        ))}

        <div className="agents-filters-h">Channel</div>
        {channels.map(c => (
          <FilterItem key={c} label={c} count={all.filter(x => x.channel === c).length} active={filterChannel === c} onClick={() => toggleChannel(c)} />
        ))}
      </aside>

      <section className="agents-main">
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Sessions</h1>
            <p className="agents-head-sub">{rows.length} of {all.length} · active conversations</p>
          </div>
          <div className="agents-head-actions">
            <button className="btn-ghost-sf">Export</button>
          </div>
        </header>

        {selectedIds.size > 0 && (
          <div className="sess-batch-bar" role="toolbar" aria-label="Batch actions">
            <span className="sess-batch-count">已选 {selectedIds.size} 个会话</span>
            <div className="sess-batch-actions">
              <button
                type="button"
                className="btn-ghost-sf"
                onClick={clearSelection}
                disabled={deleting}
              >
                取消选择
              </button>
              <button
                type="button"
                className="btn-danger-sf"
                onClick={handleBatchDelete}
                disabled={deleting}
              >
                {deleting ? '删除中…' : '批量删除'}
              </button>
            </div>
          </div>
        )}

        <div className="agents-body">
          {rows.length === 0 ? (
            <div className="sf-empty-state">No sessions match your filters.</div>
          ) : (
            <div className="sess-table-sf sess-table-with-select">
              <div className="sess-table-h">
                <div className="sess-cell-check">
                  <SelectCheckbox
                    checked={allVisibleSelected}
                    indeterminate={someVisibleSelected}
                    onChange={toggleAll}
                    ariaLabel="Select all visible sessions"
                  />
                </div>
                <div>Session</div>
                <div>Agent</div>
                <div>Channel</div>
                <div>Msgs</div>
                <div>Tokens</div>
                <div>Context</div>
                <div>Cost</div>
                <div>Last</div>
                <div />
              </div>
              {rows.map(s => {
                const checked = selectedIds.has(s.id);
                return (
                  <div
                    key={s.id}
                    className={`sess-row ${checked ? 'is-selected' : ''}`}
                    role="button"
                    tabIndex={0}
                    onClick={() => openDetail(s)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        openDetail(s);
                      }
                    }}
                  >
                    <div
                      className="sess-cell-check"
                      onClick={(e) => { e.stopPropagation(); toggleOne(s.id); }}
                    >
                      <SelectCheckbox
                        checked={checked}
                        onChange={() => toggleOne(s.id)}
                        ariaLabel={`Select session ${s.title}`}
                      />
                    </div>
                    <div className="sess-name-col">
                      <span className={`sess-status ${statusCls(s.status)}`}>
                        {s.status === 'running' && <span className="pulse-dot" />}
                        {s.status}
                      </span>
                      <div className="sess-name-text">
                        <b>{s.title}</b>
                        <span>{s.id.slice(0, 12)}</span>
                      </div>
                    </div>
                    <div className="mono-sm">{s.agent}</div>
                    <div><ChannelBadge platform={s.channel} /></div>
                    <div className="mono-sm">{s.msgs}</div>
                    <div className="mono-sm">{s.tokens.toLocaleString()}</div>
                    <div className="ctx-bar" title={`${Math.round(s.ctx * 100)}% of 200K`}>
                      <span style={{ width: `${s.ctx * 100}%`, background: s.ctx > 0.7 ? '#d97b5c' : 'var(--accent)' }} />
                      <em>{Math.round(s.ctx * 100)}%</em>
                    </div>
                    <div className="mono-sm">${s.cost.toFixed(2)}</div>
                    <div className="mono-sm">{fmtTime(s.updatedAt || s.createdAt)}</div>
                    <div className="sess-row-actions">
                      <button
                        type="button"
                        className="sess-row-del"
                        title="Delete session"
                        aria-label={`Delete session ${s.title}`}
                        onClick={(e) => { e.stopPropagation(); handleSingleDelete(s); }}
                      >
                        {TRASH_ICON}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </section>

      {open && (
        <SessionDrawer
          session={open}
          tab={drawerTab}
          setTab={setDrawerTab}
          onClose={() => setOpen(null)}
          onOpenChat={() => navigate(`/chat/${open.id}`)}
          userId={userId}
        />
      )}
    </div>
  );
};

interface MessageItem {
  role: 'user' | 'assistant';
  text: string;
  toolUses?: { name: string; id: string }[];
  toolResults?: { toolUseId: string; content: string; isError: boolean }[];
}

function extractText(content: unknown): string {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .filter((b: Record<string, unknown>) => b.type === 'text')
    .map((b: Record<string, unknown>) => String(b.text || ''))
    .join('\n');
}

function extractToolUses(content: unknown): { name: string; id: string }[] {
  if (!Array.isArray(content)) return [];
  return content
    .filter((b: Record<string, unknown>) => b.type === 'tool_use')
    .map((b: Record<string, unknown>) => ({ name: String(b.name || ''), id: String(b.id || '') }));
}

function extractToolResults(content: unknown): { toolUseId: string; content: string; isError: boolean }[] {
  if (!Array.isArray(content)) return [];
  return content
    .filter((b: Record<string, unknown>) => b.type === 'tool_result')
    .map((b: Record<string, unknown>) => ({
      toolUseId: String(b.tool_use_id || ''),
      content: String(b.content || ''),
      isError: b.is_error === true,
    }));
}

function normalizeMessages(rawMsgs: Record<string, unknown>[]): MessageItem[] {
  return rawMsgs
    .filter(m => m.role === 'user' || m.role === 'assistant')
    .map(m => {
      const role = m.role === 'assistant' ? 'assistant' as const : 'user' as const;
      return {
        role,
        text: extractText(m.content),
        toolUses: role === 'assistant' ? extractToolUses(m.content) : undefined,
        toolResults: role === 'user' ? extractToolResults(m.content) : undefined,
      };
    });
}

function SessionDrawer({ session, tab, setTab, onClose, onOpenChat, userId }: {
  session: SessionRow;
  tab: string;
  setTab: (t: string) => void;
  onClose: () => void;
  onOpenChat: () => void;
  userId: number;
}) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const { data: rawMessages, isLoading: msgsLoading } = useQuery({
    queryKey: ['session-messages', session.id],
    queryFn: () => getSessionMessages(session.id, userId).then(res => res.data as Record<string, unknown>[]),
    staleTime: 30_000,
  });

  const messages = useMemo(() => normalizeMessages(rawMessages ?? []), [rawMessages]);

  const tabs = [
    { id: 'turns', label: 'Turns' },
    { id: 'context', label: 'Context' },
    { id: 'raw', label: 'Raw' },
  ];

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div>
              <h2 className="sf-drawer-title">{session.title}</h2>
              <p className="sf-drawer-subtitle">{session.id} · {session.agent}</p>
            </div>
            <div className="sf-drawer-actions">
              <button className="btn-ghost-sf" onClick={onOpenChat}>{EXT_ICON} Open chat</button>
            </div>
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`sess-status ${statusCls(session.status)}`}>{session.status}</span>
            <span className="kv-chip-sf">{session.msgs} msgs · {session.turns} turns</span>
            <span className="kv-chip-sf">${session.cost.toFixed(2)}</span>
          </div>
        </div>

        <nav className="sf-drawer-tabs">
          {tabs.map(t => (
            <button key={t.id} className={`sf-drawer-tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>

        <div className="sf-drawer-body">
          {tab === 'turns' && (
            <TurnsView messages={messages} loading={msgsLoading} agentName={session.agent} />
          )}
          {tab === 'context' && (
            <ContextView
              sessionId={session.id}
              userId={userId}
              lifetimeTokens={session.tokens}
            />
          )}
          {tab === 'raw' && (
            <pre className="sf-code-block">{JSON.stringify(session.raw, null, 2)}</pre>
          )}
        </div>
      </aside>
    </>
  );
}

function fmtTok(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}K`;
  return n.toLocaleString();
}

function truncate(s: string, max = 80): string {
  if (!s) return '';
  return s.length <= max ? s : `${s.slice(0, max)}…`;
}

interface TurnsViewProps {
  messages: MessageItem[];
  loading: boolean;
  agentName: string;
}

function TurnsView({ messages, loading, agentName }: TurnsViewProps) {
  if (loading) return <div className="sf-empty-state">Loading messages…</div>;
  if (messages.length === 0)
    return <div className="sf-empty-state">No messages in this session.</div>;

  // Build a map tool_use_id → result for inline rendering under the calling turn.
  const resultsByUseId = new Map<string, { content: string; isError: boolean }>();
  for (const m of messages) {
    if (m.toolResults) {
      for (const r of m.toolResults) {
        if (r.toolUseId) resultsByUseId.set(r.toolUseId, { content: r.content, isError: r.isError });
      }
    }
  }

  const visibleTurns = messages
    .map((m, i) => ({ m, i }))
    .filter(({ m }) => {
      const hasText = m.text.trim().length > 0;
      const hasTools = (m.toolUses?.length ?? 0) > 0;
      const hasResults = (m.toolResults?.length ?? 0) > 0;
      // hide user messages that are only tool_results — they get rendered under the agent call
      if (m.role === 'user' && !hasText && hasResults) return false;
      return hasText || hasTools;
    });

  return (
    <div className="turns-chat">
      {visibleTurns.map(({ m, i }) => {
        const isUser = m.role === 'user';
        const label = isUser ? 'You' : agentName || 'Agent';
        return (
          <div key={i} className={`tc-msg ${isUser ? 'tc-msg--user' : 'tc-msg--agent'}`}>
            <div className="tc-msg-avatar" aria-hidden="true">
              {isUser ? 'U' : 'A'}
            </div>
            <div className="tc-msg-body">
              <div className="tc-msg-head">
                <span className="tc-msg-name">{label}</span>
                <span className="tc-msg-seq">#{i + 1}</span>
              </div>
              {m.text && <div className="tc-msg-text">{m.text}</div>}
              {(m.toolUses?.length ?? 0) > 0 && (
                <div className="tc-tool-list">
                  {m.toolUses!.map((t, j) => {
                    const res = t.id ? resultsByUseId.get(t.id) : undefined;
                    return (
                      <div
                        key={`${t.id || t.name}-${j}`}
                        className={`tc-tool ${res?.isError ? 'tc-tool--err' : ''}`}
                      >
                        <div className="tc-tool-head">
                          <span className="tc-tool-dot" aria-hidden="true" />
                          <span className="tc-tool-name">{t.name || 'tool'}</span>
                          {res && (
                            <span className="tc-tool-status">
                              {res.isError ? 'error' : 'ok'}
                            </span>
                          )}
                        </div>
                        {res && (
                          <div className="tc-tool-result">{truncate(res.content, 200)}</div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

interface ContextViewProps {
  sessionId: string;
  userId: number;
  lifetimeTokens: number;
}

function ContextView({ sessionId, userId, lifetimeTokens }: ContextViewProps) {
  const { data, isLoading, isError, refetch } = useQuery<ContextBreakdown>({
    queryKey: ['context-breakdown', sessionId, userId],
    queryFn: async () => {
      const res = await getContextBreakdown(sessionId, userId);
      return res.data;
    },
    staleTime: 15_000,
  });

  if (isLoading) return <div className="sf-empty-state">Estimating context…</div>;
  if (isError || !data) {
    return (
      <div className="sf-empty-state">
        Failed to load context breakdown.{' '}
        <button type="button" className="btn-ghost-sf" onClick={() => refetch()}>
          Retry
        </button>
      </div>
    );
  }

  const pct = data.pct;
  const ringPct = Math.min(100, Math.max(0, pct));
  const hint =
    pct >= 80
      ? 'Compaction recommended before the next long task.'
      : pct >= 50
        ? 'Moderate utilisation — monitor new turns.'
        : 'Plenty of headroom remaining.';

  return (
    <div className="ctx-panel-v2">
      <div className="ctx-v2-big">
        <div className="ctx-v2-ring" style={{ '--v': ringPct / 100 } as React.CSSProperties}>
          <span>
            {ringPct}
            <em>%</em>
          </span>
        </div>
        <div className="ctx-v2-summary">
          <div className="ctx-v2-lbl">Current context window</div>
          <div className="ctx-v2-val">
            {fmtTok(data.total)}{' '}
            <span className="ctx-v2-val-sub">/ {fmtTok(data.windowLimit)}</span>
          </div>
          <p className="ctx-v2-hint">{hint}</p>
        </div>
      </div>

      <ContextSegments segments={data.segments} total={data.total} />

      <div className="ctx-v2-lifetime">
        <div className="ctx-v2-lifetime-row">
          <span>Lifetime charged tokens</span>
          <em>{lifetimeTokens.toLocaleString()}</em>
        </div>
        <p className="ctx-v2-note">
          Estimated with a lightweight heuristic (±10%). Tokens in the current window
          may be lower than lifetime totals because earlier turns have been compacted or
          dropped.
        </p>
      </div>
    </div>
  );
}

function ContextSegments({
  segments,
  total,
}: {
  segments: ContextBreakdownSegment[];
  total: number;
}) {
  const [open, setOpen] = useState<Set<string>>(
    () => new Set(['system_prompt', 'messages']),
  );
  const toggle = (key: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const colorOf: Record<string, string> = {
    system_prompt: '#6366f1',
    tool_schemas: '#8b5cf6',
    messages: '#22c55e',
  };

  return (
    <div className="ctx-v2-segments">
      {total > 0 && (
        <div className="ctx-v2-bar" role="img" aria-label="Context token breakdown">
          {segments.map((s) => {
            const w = (s.tokens / total) * 100;
            if (w <= 0) return null;
            return (
              <div
                key={s.key}
                title={`${s.label} · ${s.tokens.toLocaleString()} tok (${w.toFixed(1)}%)`}
                style={{ width: `${w}%`, background: colorOf[s.key] ?? '#64748b' }}
              />
            );
          })}
        </div>
      )}
      <div className="ctx-v2-seg-list">
        {segments.map((s) => {
          const p = total > 0 ? (s.tokens / total) * 100 : 0;
          const hasChildren = Array.isArray(s.children) && s.children.length > 0;
          const isOpen = open.has(s.key);
          return (
            <div key={s.key} className="ctx-v2-seg">
              <button
                type="button"
                className="ctx-v2-seg-row"
                onClick={() => hasChildren && toggle(s.key)}
                disabled={!hasChildren}
                aria-expanded={hasChildren ? isOpen : undefined}
              >
                <span
                  className="ctx-v2-seg-dot"
                  style={{ background: colorOf[s.key] ?? '#64748b' }}
                />
                <span className="ctx-v2-seg-label">
                  {hasChildren && <span className="ctx-v2-seg-caret">{isOpen ? '▾' : '▸'}</span>}
                  {s.label}
                </span>
                <span className="ctx-v2-seg-tok">{fmtTok(s.tokens)}</span>
                <span className="ctx-v2-seg-pct">{p.toFixed(1)}%</span>
              </button>
              {hasChildren && isOpen && s.children && (
                <div className="ctx-v2-children">
                  {s.children.map((c) => {
                    const cp = total > 0 ? (c.tokens / total) * 100 : 0;
                    return (
                      <div key={c.key} className="ctx-v2-child">
                        <span className="ctx-v2-child-label">{c.label}</span>
                        <span className="ctx-v2-child-tok">{fmtTok(c.tokens)}</span>
                        <span className="ctx-v2-child-pct">{cp.toFixed(1)}%</span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default SessionList;
