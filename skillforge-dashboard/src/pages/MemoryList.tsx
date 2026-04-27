import React, { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  batchArchiveMemories,
  batchDeleteMemories,
  batchRestoreMemories,
  batchUpdateMemoryStatus,
  deleteMemory,
  extractList,
  getMemories,
  getMemoryStats,
  type MemoryLifecycleStatus,
  searchMemories,
  updateMemory,
  updateMemoryStatus,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import '../components/agents/agents.css';
import '../components/memory/memory.css';
import '../components/skills/skills.css';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);

type MemoryTab = MemoryLifecycleStatus;
type MemorySort = 'score' | 'recall' | 'updated';

interface MemoryRow {
  id: number;
  key: string;
  scope: string;
  agent: string;
  value: string;
  kind: string;
  hits: number;
  pinned: boolean;
  updated: string;
  updatedAtMs: number;
  status: MemoryLifecycleStatus;
  importance: string;
  score: number | null;
  raw: Record<string, unknown>;
}

function guessScope(type: string): string {
  if (type === 'project') return 'project';
  if (type === 'feedback') return 'user';
  if (type === 'reference') return 'team';
  if (type === 'knowledge') return 'agent';
  return 'user';
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

function normalizeMemory(raw: Record<string, unknown>): MemoryRow {
  const type = String(raw.type || 'preference');
  const updatedAt = String(raw.updatedAt || raw.createdAt || '');
  return {
    id: Number(raw.id || 0),
    key: String(raw.title || raw.key || ''),
    scope: guessScope(type),
    agent: 'all',
    value: String(raw.content || raw.value || ''),
    kind: type,
    hits: Number(raw.recallCount || 0),
    pinned: String(raw.importance || 'medium') === 'high',
    updated: fmtTime(updatedAt),
    updatedAtMs: updatedAt ? new Date(updatedAt).getTime() : 0,
    status: String(raw.status || 'ACTIVE').toUpperCase() as MemoryLifecycleStatus,
    importance: String(raw.importance || 'medium'),
    score: raw.lastScore == null ? null : Number(raw.lastScore),
    raw,
  };
}

function FilterItem({ label, count, active, onClick }: { label: string; count: number; active: boolean; onClick: () => void }) {
  return (
    <button className={`filter-item ${active ? 'on' : ''}`} onClick={onClick}>
      <span>{label}</span>
      <span className="filter-item-count">{count}</span>
    </button>
  );
}

const TAB_ORDER: MemoryTab[] = ['ACTIVE', 'STALE', 'ARCHIVED'];
const TAB_LABEL: Record<MemoryTab, string> = { ACTIVE: 'Active', STALE: 'Stale', ARCHIVED: 'Archived' };

const MemoryList: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [q, setQ] = useState('');
  const [activeSearch, setActiveSearch] = useState('');
  const [filterScope, setFilterScope] = useState<string | null>(null);
  const [filterKind, setFilterKind] = useState<string | null>(null);
  const [statusTab, setStatusTab] = useState<MemoryTab>('ACTIVE');
  const [sortBy, setSortBy] = useState<MemorySort>('updated');
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [open, setOpen] = useState<MemoryRow | null>(null);
  const [editVal, setEditVal] = useState('');

  const listQuery = useQuery({
    queryKey: ['memories', userId, statusTab],
    queryFn: () => getMemories(userId, undefined, statusTab).then(res => extractList<Record<string, unknown>>(res)),
    enabled: activeSearch === '',
  });
  const searchQuery = useQuery({
    queryKey: ['memories', userId, 'search', activeSearch],
    queryFn: () => searchMemories(userId, activeSearch).then(res => extractList<Record<string, unknown>>(res)),
    enabled: activeSearch !== '',
  });
  const statsQuery = useQuery({
    queryKey: ['memories', userId, 'stats'],
    queryFn: () => getMemoryStats(userId).then(res => res.data),
  });

  const rawMemories = (activeSearch ? searchQuery.data : listQuery.data) ?? [];
  const all = useMemo<MemoryRow[]>(() => rawMemories.map(normalizeMemory), [rawMemories]);
  const scopes = ['project', 'team', 'user', 'agent'];
  const kinds = useMemo(() => Array.from(new Set(all.map(m => m.kind))).sort(), [all]);

  const rows = useMemo(() => {
    const filtered = all.filter(m => {
      if (m.status !== statusTab) return false;
      if (filterScope && m.scope !== filterScope) return false;
      if (filterKind && m.kind !== filterKind) return false;
      return true;
    });
    const ranked = [...filtered];
    ranked.sort((left, right) => {
      if (sortBy === 'score') return (right.score ?? -1) - (left.score ?? -1);
      if (sortBy === 'recall') return right.hits - left.hits;
      return right.updatedAtMs - left.updatedAtMs;
    });
    return ranked;
  }, [all, statusTab, filterScope, filterKind, sortBy]);

  useEffect(() => {
    setSelectedIds(prev => prev.filter(id => rows.some(row => row.id === id)));
  }, [rows]);

  const toggleScope = (v: string) => setFilterScope(s => s === v ? null : v);
  const toggleKind = (v: string) => setFilterKind(k => k === v ? null : v);
  const handleSearch = () => setActiveSearch(q.trim());
  const openEdit = (m: MemoryRow) => { setOpen(m); setEditVal(m.value); };
  const allSelected = rows.length > 0 && selectedIds.length === rows.length;

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['memories', userId] });
  };

  const updateMut = useMutation({
    mutationFn: ({ id, content }: { id: number; content: string }) => updateMemory(id, { content }),
    onSuccess: invalidate,
  });
  const deleteMut = useMutation({
    mutationFn: (id: number) => deleteMemory(id),
    onSuccess: () => {
      setOpen(null);
      invalidate();
    },
  });
  const restoreMut = useMutation({
    mutationFn: (id: number) => updateMemoryStatus(id, userId, 'ACTIVE'),
    onSuccess: invalidate,
  });
  const batchArchiveMut = useMutation({
    mutationFn: (ids: number[]) => batchArchiveMemories(userId, ids),
    onSuccess: () => {
      setSelectedIds([]);
      invalidate();
    },
  });
  const batchRestoreMut = useMutation({
    mutationFn: (ids: number[]) => batchRestoreMemories(userId, ids),
    onSuccess: () => {
      setSelectedIds([]);
      invalidate();
    },
  });
  const batchStatusMut = useMutation({
    mutationFn: ({ ids, status }: { ids: number[]; status: MemoryLifecycleStatus }) =>
      batchUpdateMemoryStatus(userId, ids, status),
    onSuccess: () => {
      setSelectedIds([]);
      invalidate();
    },
  });
  const batchDeleteMut = useMutation({
    mutationFn: (ids: number[]) => batchDeleteMemories(userId, ids),
    onSuccess: () => {
      setSelectedIds([]);
      setOpen(null);
      invalidate();
    },
  });

  const busy = updateMut.isPending || deleteMut.isPending || restoreMut.isPending
    || batchArchiveMut.isPending || batchRestoreMut.isPending || batchStatusMut.isPending || batchDeleteMut.isPending;
  const stats = statsQuery.data;
  const nearCapacity = stats != null && stats.capacityCap > 0 && stats.active / stats.capacityCap >= 0.8;

  const toggleSelected = (id: number) => {
    setSelectedIds(prev => prev.includes(id) ? prev.filter(v => v !== id) : [...prev, id]);
  };

  return (
    <div className="agents-view">
      <aside className="agents-filters">
        <div className="agents-filters-h">Search</div>
        <input
          className="agents-search"
          placeholder="key, value…"
          value={q}
          onChange={e => {
            setQ(e.target.value);
            if (e.target.value === '') setActiveSearch('');
          }}
          onKeyDown={e => { if (e.key === 'Enter') handleSearch(); }}
        />

        <div className="agents-filters-h">Status</div>
        {TAB_ORDER.map(tab => (
          <FilterItem
            key={tab}
            label={TAB_LABEL[tab]}
            count={tab === 'ACTIVE' ? (stats?.active ?? 0) : tab === 'STALE' ? (stats?.stale ?? 0) : (stats?.archived ?? 0)}
            active={statusTab === tab}
            onClick={() => setStatusTab(tab)}
          />
        ))}

        <div className="agents-filters-h">Scope</div>
        {scopes.map(s => (
          <FilterItem key={s} label={s} count={all.filter(x => x.scope === s).length} active={filterScope === s} onClick={() => toggleScope(s)} />
        ))}

        <div className="agents-filters-h">Type</div>
        {kinds.map(k => (
          <FilterItem key={k} label={k} count={all.filter(x => x.kind === k).length} active={filterKind === k} onClick={() => toggleKind(k)} />
        ))}
      </aside>

      <section className="agents-main">
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Memory</h1>
            <p className="agents-head-sub">{rows.length} of {all.length} · lifecycle-managed memory store</p>
          </div>
          <div className="agents-head-actions">
            <button className="btn-ghost-sf">Export</button>
          </div>
        </header>

        {nearCapacity && statusTab === 'ACTIVE' && (
          <div className="mem-banner">
            <div>
              <strong>Memory near full.</strong> {stats?.active} / {stats?.capacityCap} active memories in use.
            </div>
            <button className="sf-mini-btn" onClick={() => setStatusTab('STALE')}>Review stale</button>
          </div>
        )}

        <div className="mem-toolbar">
          <div className="mem-tabs" role="tablist" aria-label="Memory status">
            {TAB_ORDER.map(tab => (
              <button
                key={tab}
                role="tab"
                aria-selected={statusTab === tab}
                className={`mem-tab ${statusTab === tab ? 'on' : ''}`}
                onClick={() => setStatusTab(tab)}
              >
                {TAB_LABEL[tab]}
              </button>
            ))}
          </div>
          <div className="mem-toolbar-right">
            <label className="mem-sort">
              <span>Sort</span>
              <select value={sortBy} onChange={e => setSortBy(e.target.value as MemorySort)}>
                <option value="updated">Updated</option>
                <option value="score">Score</option>
                <option value="recall">Recall</option>
              </select>
            </label>
          </div>
        </div>

        {selectedIds.length > 0 && (
          <div className="mem-bulkbar">
            <label className="mem-selectall">
              <input
                type="checkbox"
                checked={allSelected}
                onChange={() => setSelectedIds(allSelected ? [] : rows.map(row => row.id))}
              />
              <span>{selectedIds.length} selected</span>
            </label>
            <div className="mem-bulk-actions">
              {statusTab === 'ACTIVE' && (
                <>
                  <button className="sf-mini-btn" disabled={busy} onClick={() => batchStatusMut.mutate({ ids: selectedIds, status: 'STALE' })}>Mark stale</button>
                  <button className="sf-mini-btn" disabled={busy} onClick={() => batchArchiveMut.mutate(selectedIds)}>Archive</button>
                </>
              )}
              {statusTab === 'STALE' && (
                <>
                  <button className="sf-mini-btn" disabled={busy} onClick={() => batchStatusMut.mutate({ ids: selectedIds, status: 'ACTIVE' })}>Mark active</button>
                  <button className="sf-mini-btn" disabled={busy} onClick={() => batchArchiveMut.mutate(selectedIds)}>Archive</button>
                </>
              )}
              {statusTab === 'ARCHIVED' && (
                <button className="sf-mini-btn" disabled={busy} onClick={() => batchRestoreMut.mutate(selectedIds)}>Restore</button>
              )}
              <button className="sf-mini-btn danger" disabled={busy} onClick={() => batchDeleteMut.mutate(selectedIds)}>Delete</button>
            </div>
          </div>
        )}

        <div className="agents-body">
          {rows.length === 0 ? (
            <div className="sf-empty-state">No memories match your filters.</div>
          ) : (
            <div className="memory-grid">
              {rows.map(m => (
                <button key={m.id} className={`mem-card scope-${m.scope} ${m.pinned ? 'pinned' : ''}`} onClick={() => openEdit(m)}>
                  <div className="mem-card-h">
                    <input
                      type="checkbox"
                      checked={selectedIds.includes(m.id)}
                      onChange={() => toggleSelected(m.id)}
                      onClick={e => e.stopPropagation()}
                    />
                    <span className={`mem-scope scope-${m.scope}`}>{m.scope}</span>
                    <span className={`mem-status mem-status--${m.status.toLowerCase()}`}>{m.status.toLowerCase()}</span>
                    <span className={`mem-importance mem-importance--${m.importance}`}>{m.importance}</span>
                    <span className="mem-kind">{m.kind}</span>
                  </div>
                  <div className="mem-key">{m.key}</div>
                  <div className="mem-val">{m.value}</div>
                  <div className="mem-foot">
                    <span>score <b>{m.score == null ? '—' : m.score.toFixed(2)}</b></span>
                    <span>·</span>
                    <span>recall <b>{m.hits}</b></span>
                    <span>·</span>
                    <span>updated {m.updated}</span>
                  </div>
                  {m.status === 'ARCHIVED' && (
                    <div className="mem-card-inline-actions">
                      <button
                        className="sf-mini-btn"
                        disabled={busy}
                        onClick={(e) => {
                          e.stopPropagation();
                          restoreMut.mutate(m.id);
                        }}
                      >
                        Restore
                      </button>
                    </div>
                  )}
                </button>
              ))}
            </div>
          )}
        </div>
      </section>

      {open && (
        <MemoryDrawer
          memory={open}
          editVal={editVal}
          setEditVal={setEditVal}
          onClose={() => setOpen(null)}
          onSave={() => updateMut.mutate({ id: open.id, content: editVal })}
          onRevert={() => setEditVal(open.value)}
          onDelete={() => deleteMut.mutate(open.id)}
          onRestore={() => restoreMut.mutate(open.id)}
          saving={updateMut.isPending}
          busy={busy}
        />
      )}
    </div>
  );
};

function MemoryDrawer({ memory, editVal, setEditVal, onClose, onSave, onRevert, onDelete, onRestore, saving, busy }: {
  memory: MemoryRow;
  editVal: string;
  setEditVal: (v: string) => void;
  onClose: () => void;
  onSave: () => void;
  onRevert: () => void;
  onDelete: () => void;
  onRestore: () => void;
  saving: boolean;
  busy: boolean;
}) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const dirty = editVal !== memory.value;

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div>
              <h2 className="sf-drawer-title">{memory.key}</h2>
              <p className="sf-drawer-subtitle">scope · {memory.scope} → {memory.agent}</p>
            </div>
            <div className="sf-drawer-actions">
              {memory.status === 'ARCHIVED' && (
                <button className="btn-ghost-sf" onClick={onRestore} disabled={busy}>Restore</button>
              )}
              <button className="btn-ghost-sf" style={{ color: '#8a2a2a' }} onClick={onDelete} disabled={busy}>Delete</button>
            </div>
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`mem-scope scope-${memory.scope}`}>{memory.scope}</span>
            <span className={`mem-status mem-status--${memory.status.toLowerCase()}`}>{memory.status.toLowerCase()}</span>
            <span className={`mem-importance mem-importance--${memory.importance}`}>{memory.importance}</span>
            <span className="kv-chip-sf">score · {memory.score == null ? '—' : memory.score.toFixed(2)}</span>
            <span className="kv-chip-sf">recall · {memory.hits}</span>
            <span className="kv-chip-sf">updated {memory.updated}</span>
          </div>
        </div>

        <div className="sf-drawer-body">
          <div className="spec-block">
            <div className="spec-h"><h3>Value</h3></div>
            <div className="prompt-block">
              <div className="prompt-file-bar">
                <span className={`mode-dot ${dirty ? 'dirty' : ''}`} />
                <span>{memory.kind} {dirty ? '· unsaved' : '· synced'}</span>
              </div>
              <textarea className="prompt-editor" value={editVal} onChange={e => setEditVal(e.target.value)} spellCheck={false} />
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
              <button className="btn-ghost-sf" onClick={onRevert} disabled={!dirty}>Revert</button>
              <button className="btn-primary-sf" onClick={onSave} disabled={!dirty || saving}>{saving ? 'Saving…' : 'Save'}</button>
            </div>
          </div>

          <div className="spec-block">
            <div className="spec-h"><h3>Metadata</h3></div>
            <div className="mem-meta-grid">
              <div><span>key</span><em>{memory.key}</em></div>
              <div><span>scope</span><em>{memory.scope}</em></div>
              <div><span>agent</span><em>{memory.agent}</em></div>
              <div><span>type</span><em>{memory.kind}</em></div>
              <div><span>status</span><em>{memory.status}</em></div>
              <div><span>importance</span><em>{memory.importance}</em></div>
              <div><span>score</span><em>{memory.score == null ? '—' : memory.score.toFixed(2)}</em></div>
              <div><span>recall</span><em>{String(memory.hits)}</em></div>
              <div><span>updated</span><em>{memory.updated}</em></div>
            </div>
          </div>
        </div>
      </aside>
    </>
  );
}

export default MemoryList;
