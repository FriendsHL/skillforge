import React, { useEffect, useMemo, useState } from 'react';
import { message, Modal } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import {
  getScriptMethods,
  getScriptMethod,
  createScriptMethod,
  deleteScriptMethod,
  toggleScriptMethod,
  getCompiledMethods,
  getCompiledMethod,
  approveCompiledMethod,
  rejectCompiledMethod,
  compileCompiledMethod,
  deleteCompiledMethod,
} from '../api';
import type {
  ScriptMethodSummary,
  ScriptMethodDetail,
  CompiledMethodSummary,
  CompiledMethodDetail,
  CreateScriptMethodRequest,
} from '../api';
import { useAuth } from '../contexts/AuthContext';
import '../components/agents/agents.css';
import '../components/skills/skills.css';
import '../components/hook-methods/hook-methods.css';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);
const PLUS_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
    <path d="M8 3v10M3 8h10" />
  </svg>
);

type ActiveTab = 'script' | 'compiled';

function timeAgo(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return String(iso);
  const ms = Date.now() - d.getTime();
  const m = Math.floor(ms / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const days = Math.floor(h / 24);
  return `${days}d ago`;
}

function statusClass(status: string): string {
  switch (status) {
    case 'pending_review': return 's-pending';
    case 'compiled': return 's-compiled';
    case 'active': return 's-active';
    case 'rejected': return 's-rejected';
    default: return '';
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'pending_review': return 'pending';
    case 'compiled': return 'compiled';
    case 'active': return 'active';
    case 'rejected': return 'rejected';
    default: return status;
  }
}

const HookMethods: React.FC = () => {
  const [tab, setTab] = useState<ActiveTab>('script');
  const [q, setQ] = useState('');
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [view, setView] = useState<'grid' | 'table'>('grid');
  const [creating, setCreating] = useState(false);

  const { data: rawScripts = [], isLoading: scriptsLoading, isError: scriptsError } = useQuery({
    queryKey: ['script-methods'],
    queryFn: () => getScriptMethods().then(r => r.data),
  });

  const { data: rawCompiled = [], isLoading: compiledLoading, isError: compiledError } = useQuery({
    queryKey: ['compiled-methods'],
    queryFn: () => getCompiledMethods().then(r => r.data),
  });

  const loading = tab === 'script' ? scriptsLoading : compiledLoading;
  const error = tab === 'script' ? scriptsError : compiledError;

  return (
    <div className="agents-view">
      <FilterSidebar
        tab={tab}
        q={q}
        setQ={setQ}
        filterStatus={filterStatus}
        setFilterStatus={setFilterStatus}
        scripts={rawScripts}
        compiled={rawCompiled}
      />
      <section className="agents-main">
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Hook Methods</h1>
            <p className="agents-head-sub">
              {tab === 'script'
                ? `${rawScripts.length} script methods — bash/node hooks for external calls`
                : `${rawCompiled.length} compiled methods — Java classes for internal access`}
            </p>
          </div>
          <div className="agents-head-actions">
            <div className="view-seg">
              <button className={view === 'grid' ? 'on' : ''} onClick={() => setView('grid')}>Grid</button>
              <button className={view === 'table' ? 'on' : ''} onClick={() => setView('table')}>Table</button>
            </div>
            {tab === 'script' && (
              <button className="btn-primary-sf" onClick={() => setCreating(true)}>
                {PLUS_ICON} New method
              </button>
            )}
          </div>
        </header>

        <div className="hm-tab-bar">
          <button className={`hm-tab-btn ${tab === 'script' ? 'on' : ''}`} onClick={() => { setTab('script'); setFilterStatus(null); }}>
            Script Methods <span className="hm-tab-count">{rawScripts.length}</span>
          </button>
          <button className={`hm-tab-btn ${tab === 'compiled' ? 'on' : ''}`} onClick={() => { setTab('compiled'); setFilterStatus(null); }}>
            Compiled Methods <span className="hm-tab-count">{rawCompiled.length}</span>
          </button>
        </div>

        <div className="agents-body">
          {loading ? (
            <div className="sf-empty-state">Loading…</div>
          ) : error ? (
            <div className="sf-empty-state" style={{ color: 'var(--color-err)' }}>
              Failed to load {tab === 'script' ? 'script' : 'compiled'} methods. Check your connection and try again.
            </div>
          ) : tab === 'script' ? (
            <ScriptMethodsPanel
              methods={rawScripts}
              q={q}
              filterStatus={filterStatus}
              view={view}
            />
          ) : (
            <CompiledMethodsPanel
              methods={rawCompiled}
              q={q}
              filterStatus={filterStatus}
              view={view}
            />
          )}
        </div>
      </section>

      {creating && (
        <CreateScriptMethodModal onClose={() => setCreating(false)} />
      )}
    </div>
  );
};

/* ─── Filter Sidebar ─── */
function FilterSidebar({
  tab, q, setQ, filterStatus, setFilterStatus, scripts, compiled,
}: {
  tab: ActiveTab;
  q: string;
  setQ: (v: string) => void;
  filterStatus: string | null;
  setFilterStatus: (v: string | null) => void;
  scripts: ScriptMethodSummary[];
  compiled: CompiledMethodSummary[];
}) {
  const toggle = (value: string) => setFilterStatus(filterStatus === value ? null : value);

  return (
    <aside className="agents-filters">
      <div className="agents-filters-h">Search</div>
      <input
        className="agents-search"
        placeholder="ref, name, description…"
        value={q}
        onChange={e => setQ(e.target.value)}
      />

      {tab === 'script' && (
        <>
          <div className="agents-filters-h">Status</div>
          <FilterItem label="enabled" count={scripts.filter(s => s.enabled).length} active={filterStatus === 'enabled'} onClick={() => toggle('enabled')} />
          <FilterItem label="disabled" count={scripts.filter(s => !s.enabled).length} active={filterStatus === 'disabled'} onClick={() => toggle('disabled')} />
        </>
      )}

      {tab === 'compiled' && (
        <>
          <div className="agents-filters-h">Status</div>
          <FilterItem label="active" count={compiled.filter(c => c.status === 'active').length} active={filterStatus === 'active'} onClick={() => toggle('active')} />
          <FilterItem label="compiled" count={compiled.filter(c => c.status === 'compiled').length} active={filterStatus === 'compiled'} onClick={() => toggle('compiled')} />
          <FilterItem label="pending" count={compiled.filter(c => c.status === 'pending_review').length} active={filterStatus === 'pending_review'} onClick={() => toggle('pending_review')} />
          <FilterItem label="rejected" count={compiled.filter(c => c.status === 'rejected').length} active={filterStatus === 'rejected'} onClick={() => toggle('rejected')} />
        </>
      )}
    </aside>
  );
}

function FilterItem({ label, count, active, onClick }: { label: string; count: number; active: boolean; onClick: () => void }) {
  return (
    <button className={`filter-item ${active ? 'on' : ''}`} onClick={onClick}>
      <span>{label}</span>
      <span className="filter-item-count">{count}</span>
    </button>
  );
}

/* ─── Script Methods Panel ─── */
function ScriptMethodsPanel({
  methods, q, filterStatus, view,
}: {
  methods: ScriptMethodSummary[];
  q: string;
  filterStatus: string | null;
  view: 'grid' | 'table';
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState<ScriptMethodSummary | null>(null);

  const filtered = useMemo(() => {
    return methods.filter(m => {
      if (q) {
        const ql = q.toLowerCase();
        const hay = `${m.ref} ${m.displayName} ${m.description || ''}`.toLowerCase();
        if (!hay.includes(ql)) return false;
      }
      if (filterStatus === 'enabled' && !m.enabled) return false;
      if (filterStatus === 'disabled' && m.enabled) return false;
      return true;
    });
  }, [methods, q, filterStatus]);

  const deleteMut = useMutation({
    mutationFn: (id: number) => deleteScriptMethod(id),
    onSuccess: () => { message.success('Script method deleted'); queryClient.invalidateQueries({ queryKey: ['script-methods'] }); },
    onError: () => message.error('Failed to delete'),
  });

  const toggleMut = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) => toggleScriptMethod(id, enabled),
    onSuccess: (_, v) => { message.success(v.enabled ? 'Enabled' : 'Disabled'); queryClient.invalidateQueries({ queryKey: ['script-methods'] }); },
    onError: () => message.error('Toggle failed'),
  });

  return (
    <>
      {filtered.length === 0 ? (
        <div className="sf-empty-state">
          {methods.length === 0
            ? 'No script methods registered yet. Code Agent creates them automatically.'
            : 'No script methods match your filters.'}
        </div>
      ) : view === 'grid' ? (
        <div className="hm-grid">
          {filtered.map(m => (
            <button key={m.id} className="hm-card" onClick={() => setOpen(m)}>
              <div className="hm-card-head">
                <span className={`hm-lang-pill lang-${m.lang}`}>{m.lang}</span>
                <span className="hm-ref">{m.ref}</span>
                {!m.enabled && (
                  <span className="status-pill-sf s-draft"><span className="status-dot-sf" /> disabled</span>
                )}
              </div>
              {m.displayName && <div className="hm-display-name">{m.displayName}</div>}
              {m.description && <div className="hm-desc">{m.description}</div>}
              <div className="hm-meta">
                <span>{timeAgo(m.createdAt)}</span>
              </div>
            </button>
          ))}
        </div>
      ) : (
        <table className="hm-table">
          <thead>
            <tr>
              <th>Ref</th>
              <th>Name</th>
              <th>Lang</th>
              <th>Status</th>
              <th>Updated</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map(m => (
              <tr key={m.id} onClick={() => setOpen(m)}>
                <td>
                  <div className="t-ref">
                    <b>{m.ref}</b>
                  </div>
                </td>
                <td>{m.displayName || '—'}</td>
                <td><span className={`hm-lang-pill lang-${m.lang}`}>{m.lang}</span></td>
                <td>
                  <span className={`status-pill-sf ${m.enabled ? 's-ok' : 's-draft'}`}>
                    <span className="status-dot-sf" /> {m.enabled ? 'enabled' : 'disabled'}
                  </span>
                </td>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>{timeAgo(m.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {open && (
        <ScriptMethodDrawer
          summary={open}
          onClose={() => setOpen(null)}
          onToggle={(id, en) => toggleMut.mutate({ id, enabled: en })}
          onDelete={(id) => {
            Modal.confirm({
              title: 'Delete script method?',
              content: `This will unregister "${open.ref}" and delete it permanently.`,
              okText: 'Delete',
              okButtonProps: { danger: true },
              onOk: () => { deleteMut.mutate(id); setOpen(null); },
            });
          }}
        />
      )}
    </>
  );
}

/* ─── Script Method Drawer ─── */
function ScriptMethodDrawer({ summary, onClose, onToggle, onDelete }: {
  summary: ScriptMethodSummary;
  onClose: () => void;
  onToggle: (id: number, enabled: boolean) => void;
  onDelete: (id: number) => void;
}) {
  const [drawerTab, setDrawerTab] = useState('info');

  const { data: detail } = useQuery<ScriptMethodDetail>({
    queryKey: ['script-method-detail', summary.id],
    queryFn: () => getScriptMethod(summary.id).then(r => r.data),
  });

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const tabs = [
    { id: 'info', label: 'Info' },
    { id: 'source', label: 'Source' },
    ...(detail?.argsSchema ? [{ id: 'schema', label: 'Schema' }] : []),
  ];

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog" aria-modal="true" aria-labelledby="script-drawer-title">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div>
              <h2 id="script-drawer-title" className="sf-drawer-title">{summary.ref}</h2>
              {summary.displayName && <p className="sf-drawer-subtitle">{summary.displayName}</p>}
            </div>
            <div className="sf-drawer-actions">
              <button className="btn-ghost-sf" onClick={() => onToggle(summary.id, !summary.enabled)}>
                {summary.enabled ? 'Disable' : 'Enable'}
              </button>
              <button className="btn-ghost-sf" style={{ color: 'var(--color-err)' }} onClick={() => onDelete(summary.id)}>
                Delete
              </button>
            </div>
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`hm-lang-pill lang-${summary.lang}`}>{summary.lang}</span>
            <span className="hm-type-pill type-script">script</span>
            <span className={`status-pill-sf ${summary.enabled ? 's-ok' : 's-draft'}`}>
              <span className="status-dot-sf" /> {summary.enabled ? 'enabled' : 'disabled'}
            </span>
          </div>
        </div>

        <nav className="sf-drawer-tabs">
          {tabs.map(t => (
            <button key={t.id} className={`sf-drawer-tab ${drawerTab === t.id ? 'on' : ''}`} onClick={() => setDrawerTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>

        <div className="sf-drawer-body">
          {drawerTab === 'info' && (
            <div>
              {summary.description && (
                <div style={{ marginBottom: 16 }}>
                  <div className="sf-section-h">Description</div>
                  <p style={{ color: 'var(--fg-2)', fontSize: 'var(--font-size-sm)', lineHeight: 1.6, margin: 0 }}>
                    {summary.description}
                  </p>
                </div>
              )}
              <div className="sf-section-h">Details</div>
              <div className="sf-usage-list">
                <div className="sf-usage-row">
                  <div className="sf-usage-row-body">
                    <b>Ref</b>
                    <span style={{ fontFamily: 'var(--font-mono)' }}>{summary.ref}</span>
                  </div>
                </div>
                <div className="sf-usage-row">
                  <div className="sf-usage-row-body">
                    <b>Language</b>
                    <span>{summary.lang}</span>
                  </div>
                </div>
                <div className="sf-usage-row">
                  <div className="sf-usage-row-body">
                    <b>Created</b>
                    <span>{summary.createdAt ? new Date(summary.createdAt).toLocaleString() : '—'}</span>
                  </div>
                </div>
                <div className="sf-usage-row">
                  <div className="sf-usage-row-body">
                    <b>Updated</b>
                    <span>{summary.updatedAt ? new Date(summary.updatedAt).toLocaleString() : '—'}</span>
                  </div>
                </div>
              </div>
            </div>
          )}

          {drawerTab === 'source' && (
            <div>
              <div className="sf-code-bar">
                <span>{summary.ref}.{summary.lang}</span>
              </div>
              <pre className="sf-code-block">{detail?.scriptBody || 'Loading…'}</pre>
            </div>
          )}

          {drawerTab === 'schema' && detail?.argsSchema && (
            <div>
              <div className="sf-section-h">Arguments Schema</div>
              <pre className="sf-code-block">{formatJsonSafe(detail.argsSchema)}</pre>
            </div>
          )}
        </div>
      </aside>
    </>
  );
}

/* ─── Create Script Method Modal (placeholder for manual creation) ─── */
function CreateScriptMethodModal({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [ref, setRef] = useState('agent.');
  const [displayName, setDisplayName] = useState('');
  const [lang, setLang] = useState('bash');
  const [scriptBody, setScriptBody] = useState('');
  const [description, setDescription] = useState('');

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const createMut = useMutation({
    mutationFn: (data: CreateScriptMethodRequest) => createScriptMethod(data),
    onSuccess: () => {
      message.success('Script method created');
      queryClient.invalidateQueries({ queryKey: ['script-methods'] });
      onClose();
    },
    onError: (err: unknown) => {
      message.error(extractErrorMessage(err, 'Failed to create'));
    },
  });

  const handleSubmit = () => {
    if (!ref || !displayName || !scriptBody) {
      message.warning('ref, displayName, and scriptBody are required');
      return;
    }
    createMut.mutate({ ref, displayName, lang, scriptBody, description, ownerId: userId });
  };

  return (
    <div className="sf-modal-scrim" onClick={onClose}>
      <div className="sf-modal" onClick={e => e.stopPropagation()} style={{ width: 'min(700px, 94vw)' }}>
        <div className="sf-modal-h">
          <h3>New Script Method</h3>
          <button className="sf-icon-btn" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
        </div>

        <div className="sf-modal-body">
          <div className="sf-modal-row">
            <div className="sf-modal-field">
              <label>Ref (agent.xxx)</label>
              <input value={ref} onChange={e => setRef(e.target.value)} placeholder="agent.my-hook" />
            </div>
            <div className="sf-modal-field">
              <label>Display Name</label>
              <input value={displayName} onChange={e => setDisplayName(e.target.value)} placeholder="My Hook Method" />
            </div>
          </div>
          <div className="sf-modal-row">
            <div className="sf-modal-field">
              <label>Language</label>
              <select value={lang} onChange={e => setLang(e.target.value)}>
                <option value="bash">bash</option>
                <option value="node">node</option>
              </select>
            </div>
            <div className="sf-modal-field">
              <label>Description</label>
              <input value={description} onChange={e => setDescription(e.target.value)} placeholder="What this method does" />
            </div>
          </div>
          <div className="sf-modal-field">
            <label>Script Body</label>
            <textarea
              rows={12}
              value={scriptBody}
              onChange={e => setScriptBody(e.target.value)}
              placeholder="#!/bin/bash&#10;echo 'hello'"
              style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
            />
          </div>
        </div>

        <div className="sf-modal-f">
          <button className="btn-ghost-sf" onClick={onClose}>Cancel</button>
          <button className="btn-primary-sf" disabled={createMut.isPending} onClick={handleSubmit}>
            {createMut.isPending ? 'Creating…' : 'Create method'}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ─── Compiled Methods Panel ─── */
function CompiledMethodsPanel({
  methods, q, filterStatus, view,
}: {
  methods: CompiledMethodSummary[];
  q: string;
  filterStatus: string | null;
  view: 'grid' | 'table';
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState<CompiledMethodSummary | null>(null);

  const filtered = useMemo(() => {
    return methods.filter(m => {
      if (q) {
        const ql = q.toLowerCase();
        const hay = `${m.ref} ${m.displayName} ${m.description || ''}`.toLowerCase();
        if (!hay.includes(ql)) return false;
      }
      if (filterStatus && m.status !== filterStatus) return false;
      return true;
    });
  }, [methods, q, filterStatus]);

  const deleteMut = useMutation({
    mutationFn: (id: number) => deleteCompiledMethod(id),
    onSuccess: () => { message.success('Compiled method deleted'); queryClient.invalidateQueries({ queryKey: ['compiled-methods'] }); },
    onError: () => message.error('Failed to delete'),
  });

  return (
    <>
      {filtered.length === 0 ? (
        <div className="sf-empty-state">
          {methods.length === 0
            ? 'No compiled methods submitted yet. Code Agent generates these for internal-access hooks.'
            : 'No compiled methods match your filters.'}
        </div>
      ) : view === 'grid' ? (
        <div className="hm-grid">
          {filtered.map(m => (
            <button key={m.id} className="hm-card" onClick={() => setOpen(m)}>
              <div className="hm-card-head">
                <span className="hm-lang-pill lang-java">java</span>
                <span className="hm-ref">{m.ref}</span>
                <span className={`hm-status-pill ${statusClass(m.status)}`}>
                  <span className="hm-status-dot" /> {statusLabel(m.status)}
                </span>
              </div>
              {m.displayName && <div className="hm-display-name">{m.displayName}</div>}
              {m.description && <div className="hm-desc">{m.description}</div>}
              {m.compileError && <div className="hm-compile-err">{m.compileError}</div>}
              <div className="hm-meta">
                <span>{timeAgo(m.createdAt)}</span>
                {m.hasCompiledBytes && <span>· compiled</span>}
              </div>
            </button>
          ))}
        </div>
      ) : (
        <table className="hm-table">
          <thead>
            <tr>
              <th>Ref</th>
              <th>Name</th>
              <th>Status</th>
              <th>Compiled</th>
              <th>Updated</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map(m => (
              <tr key={m.id} onClick={() => setOpen(m)}>
                <td>
                  <div className="t-ref">
                    <span className="hm-lang-pill lang-java">java</span>
                    <b>{m.ref}</b>
                  </div>
                </td>
                <td>{m.displayName || '—'}</td>
                <td>
                  <span className={`hm-status-pill ${statusClass(m.status)}`}>
                    <span className="hm-status-dot" /> {statusLabel(m.status)}
                  </span>
                </td>
                <td>{m.hasCompiledBytes ? 'Yes' : 'No'}</td>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>{timeAgo(m.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {open && (
        <CompiledMethodDrawer
          summary={open}
          onClose={() => setOpen(null)}
          onDelete={(id) => {
            Modal.confirm({
              title: 'Delete compiled method?',
              content: `This will unregister "${open.ref}" and delete it permanently.`,
              okText: 'Delete',
              okButtonProps: { danger: true },
              onOk: () => { deleteMut.mutate(id); setOpen(null); },
            });
          }}
        />
      )}
    </>
  );
}

/* ─── Compiled Method Drawer ─── */
function CompiledMethodDrawer({ summary, onClose, onDelete }: {
  summary: CompiledMethodSummary;
  onClose: () => void;
  onDelete: (id: number) => void;
}) {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [drawerTab, setDrawerTab] = useState('info');

  const { data: detail } = useQuery<CompiledMethodDetail>({
    queryKey: ['compiled-method-detail', summary.id],
    queryFn: () => getCompiledMethod(summary.id).then(r => r.data),
  });

  const approveMut = useMutation({
    mutationFn: () => approveCompiledMethod(summary.id, userId),
    onSuccess: () => {
      message.success('Method approved and activated');
      queryClient.invalidateQueries({ queryKey: ['compiled-methods'] });
      queryClient.invalidateQueries({ queryKey: ['compiled-method-detail', summary.id] });
    },
    onError: (err: unknown) => {
      message.error(extractErrorMessage(err, 'Approval failed'));
    },
  });

  const rejectMut = useMutation({
    mutationFn: () => rejectCompiledMethod(summary.id, userId),
    onSuccess: () => {
      message.success('Method rejected');
      queryClient.invalidateQueries({ queryKey: ['compiled-methods'] });
      queryClient.invalidateQueries({ queryKey: ['compiled-method-detail', summary.id] });
    },
    onError: (err: unknown) => message.error(extractErrorMessage(err, 'Reject failed')),
  });

  const compileMut = useMutation({
    mutationFn: () => compileCompiledMethod(summary.id),
    onSuccess: () => {
      message.success('Compilation successful');
      queryClient.invalidateQueries({ queryKey: ['compiled-methods'] });
      queryClient.invalidateQueries({ queryKey: ['compiled-method-detail', summary.id] });
    },
    onError: (err: unknown) => {
      message.error(extractErrorMessage(err, 'Compilation failed'));
    },
  });

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const tabs = [
    { id: 'info', label: 'Info' },
    { id: 'source', label: 'Source' },
    ...(detail?.argsSchema ? [{ id: 'schema', label: 'Schema' }] : []),
  ];

  const canApprove = summary.status === 'compiled';
  const canReject = summary.status !== 'rejected' && summary.status !== 'active';
  const canCompile = summary.status === 'pending_review' || summary.status === 'rejected';

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog" aria-modal="true" aria-labelledby="compiled-drawer-title">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div>
              <h2 id="compiled-drawer-title" className="sf-drawer-title">{summary.ref}</h2>
              {summary.displayName && <p className="sf-drawer-subtitle">{summary.displayName}</p>}
            </div>
            <div className="sf-drawer-actions">
              <button className="btn-ghost-sf" style={{ color: 'var(--color-err)' }} onClick={() => onDelete(summary.id)}>
                Delete
              </button>
            </div>
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className="hm-lang-pill lang-java">java</span>
            <span className="hm-type-pill type-compiled">compiled</span>
            <span className={`hm-status-pill ${statusClass(summary.status)}`}>
              <span className="hm-status-dot" /> {statusLabel(summary.status)}
            </span>
            {summary.hasCompiledBytes && (
              <span className="kv-chip-sf">bytecode ready</span>
            )}
          </div>
        </div>

        <nav className="sf-drawer-tabs">
          {tabs.map(t => (
            <button key={t.id} className={`sf-drawer-tab ${drawerTab === t.id ? 'on' : ''}`} onClick={() => setDrawerTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>

        <div className="sf-drawer-body">
          {drawerTab === 'info' && (
            <div>
              {summary.description && (
                <div style={{ marginBottom: 16 }}>
                  <div className="sf-section-h">Description</div>
                  <p style={{ color: 'var(--fg-2)', fontSize: 'var(--font-size-sm)', lineHeight: 1.6, margin: 0 }}>
                    {summary.description}
                  </p>
                </div>
              )}

              {summary.compileError && (
                <div style={{ marginBottom: 16 }}>
                  <div className="sf-section-h">Compile Error</div>
                  <pre className="hm-compile-err" style={{
                    WebkitLineClamp: 'unset',
                    display: 'block',
                    whiteSpace: 'pre-wrap',
                    maxHeight: 200,
                    overflowY: 'auto',
                  } as React.CSSProperties}>
                    {summary.compileError}
                  </pre>
                </div>
              )}

              <div className="sf-section-h">Details</div>
              <div className="sf-usage-list">
                <div className="sf-usage-row">
                  <div className="sf-usage-row-body">
                    <b>Ref</b>
                    <span style={{ fontFamily: 'var(--font-mono)' }}>{summary.ref}</span>
                  </div>
                </div>
                <div className="sf-usage-row">
                  <div className="sf-usage-row-body">
                    <b>Status</b>
                    <span>{statusLabel(summary.status)}</span>
                  </div>
                </div>
                {summary.generatedBySessionId && (
                  <div className="sf-usage-row">
                    <div className="sf-usage-row-body">
                      <b>Generated by Session</b>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>{summary.generatedBySessionId}</span>
                    </div>
                  </div>
                )}
                {summary.reviewedByUserId && (
                  <div className="sf-usage-row">
                    <div className="sf-usage-row-body">
                      <b>Reviewed by</b>
                      <span>User #{summary.reviewedByUserId}</span>
                    </div>
                  </div>
                )}
                <div className="sf-usage-row">
                  <div className="sf-usage-row-body">
                    <b>Created</b>
                    <span>{summary.createdAt ? new Date(summary.createdAt).toLocaleString() : '—'}</span>
                  </div>
                </div>
                <div className="sf-usage-row">
                  <div className="sf-usage-row-body">
                    <b>Updated</b>
                    <span>{summary.updatedAt ? new Date(summary.updatedAt).toLocaleString() : '—'}</span>
                  </div>
                </div>
              </div>
            </div>
          )}

          {drawerTab === 'source' && (
            <div>
              <div className="sf-code-bar">
                <span>{summary.ref}.java</span>
              </div>
              <pre className="sf-code-block">{detail?.sourceCode || 'Loading…'}</pre>
            </div>
          )}

          {drawerTab === 'schema' && detail?.argsSchema && (
            <div>
              <div className="sf-section-h">Arguments Schema</div>
              <pre className="sf-code-block">{formatJsonSafe(detail.argsSchema)}</pre>
            </div>
          )}
        </div>

        {(canApprove || canReject || canCompile) && (
          <div className="hm-drawer-actions">
            {canCompile && (
              <button className="btn-compile" disabled={compileMut.isPending} onClick={() => compileMut.mutate()}>
                {compileMut.isPending ? 'Compiling…' : 'Compile'}
              </button>
            )}
            {canApprove && (
              <button className="btn-approve" disabled={approveMut.isPending} onClick={() => approveMut.mutate()}>
                {approveMut.isPending ? 'Approving…' : 'Approve & Activate'}
              </button>
            )}
            {canReject && (
              <button className="btn-reject" disabled={rejectMut.isPending} onClick={() => rejectMut.mutate()}>
                {rejectMut.isPending ? 'Rejecting…' : 'Reject'}
              </button>
            )}
            <div style={{ flex: 1 }} />
          </div>
        )}
      </aside>
    </>
  );
}

/* ─── Helpers ─── */
function extractErrorMessage(err: unknown, fallback: string): string {
  if (axios.isAxiosError(err)) {
    const serverMsg = err.response?.data?.error;
    if (typeof serverMsg === 'string' && serverMsg) return serverMsg;
  }
  return fallback;
}

function formatJsonSafe(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

export default HookMethods;
