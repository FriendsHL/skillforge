import React, { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getMemories, searchMemories, updateMemory, deleteMemory, extractList } from '../api';
import { useAuth } from '../contexts/AuthContext';
import '../components/agents/agents.css';
import '../components/memory/memory.css';
import '../components/skills/skills.css';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);

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
  raw: Record<string, unknown>;
}

function guessScope(type: string): string {
  if (type === 'project') return 'project';
  if (type === 'feedback') return 'user';
  if (type === 'reference') return 'team';
  if (type === 'knowledge') return 'agent';
  return 'user';
}

function normalizeMemory(raw: Record<string, unknown>): MemoryRow {
  const type = String(raw.type || 'preference');
  return {
    id: Number(raw.id || 0),
    key: String(raw.title || raw.key || ''),
    scope: guessScope(type),
    agent: 'all',
    value: String(raw.content || raw.value || ''),
    kind: type,
    hits: 0,
    pinned: false,
    updated: fmtTime(String(raw.updatedAt || raw.createdAt || '')),
    raw,
  };
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

const MemoryList: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [q, setQ] = useState('');
  const [activeSearch, setActiveSearch] = useState('');
  const [filterScope, setFilterScope] = useState<string | null>(null);
  const [filterKind, setFilterKind] = useState<string | null>(null);
  const [open, setOpen] = useState<MemoryRow | null>(null);
  const [editVal, setEditVal] = useState('');

  const listQuery = useQuery({
    queryKey: ['memories', userId],
    queryFn: () => getMemories(userId).then(res => extractList<Record<string, unknown>>(res)),
    enabled: activeSearch === '',
  });
  const searchQuery = useQuery({
    queryKey: ['memories', userId, 'search', activeSearch],
    queryFn: () => searchMemories(userId, activeSearch).then(res => extractList<Record<string, unknown>>(res)),
    enabled: activeSearch !== '',
  });

  const rawMemories = (activeSearch ? searchQuery.data : listQuery.data) ?? [];
  const all = useMemo<MemoryRow[]>(() => rawMemories.map(normalizeMemory), [rawMemories]);
  const scopes = ['project', 'team', 'user', 'agent'];
  const kinds = useMemo(() => Array.from(new Set(all.map(m => m.kind))).sort(), [all]);

  const rows = useMemo(() => {
    return all.filter(m => {
      if (filterScope && m.scope !== filterScope) return false;
      if (filterKind && m.kind !== filterKind) return false;
      return true;
    });
  }, [all, filterScope, filterKind]);

  const toggleScope = (v: string) => setFilterScope(s => s === v ? null : v);
  const toggleKind = (v: string) => setFilterKind(k => k === v ? null : v);

  const handleSearch = () => setActiveSearch(q.trim());

  const openEdit = (m: MemoryRow) => { setOpen(m); setEditVal(m.value); };

  const updateMut = useMutation({
    mutationFn: ({ id, content }: { id: number; content: string }) => updateMemory(id, { content }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['memories', userId] }),
  });

  const deleteMut = useMutation({
    mutationFn: (id: number) => deleteMemory(id),
    onSuccess: () => {
      setOpen(null);
      queryClient.invalidateQueries({ queryKey: ['memories', userId] });
    },
  });

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
            <p className="agents-head-sub">{rows.length} of {all.length} · persistent key/value store across sessions</p>
          </div>
          <div className="agents-head-actions">
            <button className="btn-ghost-sf">Export</button>
          </div>
        </header>

        <div className="agents-body">
          {rows.length === 0 ? (
            <div className="sf-empty-state">No memories match your filters.</div>
          ) : (
            <div className="memory-grid">
              {rows.map(m => (
                <button key={m.id} className={`mem-card scope-${m.scope} ${m.pinned ? 'pinned' : ''}`} onClick={() => openEdit(m)}>
                  <div className="mem-card-h">
                    <span className={`mem-scope scope-${m.scope}`}>{m.scope}</span>
                    {m.pinned && <span className="mem-pinned" title="pinned">★</span>}
                    <span className="mem-kind">{m.kind}</span>
                  </div>
                  <div className="mem-key">{m.key}</div>
                  <div className="mem-val">{m.value}</div>
                  <div className="mem-foot">
                    <span>agent · <b>{m.agent}</b></span>
                    <span>·</span>
                    <span>updated {m.updated}</span>
                  </div>
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
          saving={updateMut.isPending}
        />
      )}
    </div>
  );
};

function MemoryDrawer({ memory, editVal, setEditVal, onClose, onSave, onRevert, onDelete, saving }: {
  memory: MemoryRow;
  editVal: string;
  setEditVal: (v: string) => void;
  onClose: () => void;
  onSave: () => void;
  onRevert: () => void;
  onDelete: () => void;
  saving: boolean;
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
              <button className="btn-ghost-sf" style={{ color: '#8a2a2a' }} onClick={onDelete}>Delete</button>
            </div>
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`mem-scope scope-${memory.scope}`}>{memory.scope}</span>
            <span className="kv-chip-sf">type · {memory.kind}</span>
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
              <div><span>updated</span><em>{memory.updated}</em></div>
            </div>
          </div>
        </div>
      </aside>
    </>
  );
}

export default MemoryList;
