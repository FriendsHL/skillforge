import React, { useEffect, useMemo, useRef, useState } from 'react';
import { message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSkills, uploadSkill, deleteSkill,
  getSkillDetail, toggleSkill, extractList,
} from '../api';
import '../components/agents/agents.css';
import '../components/skills/skills.css';

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
const COPY_ICON = (
  <svg width={11} height={11} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4">
    <rect x="5" y="5" width="8" height="8" rx="1.5" /><path d="M3 10V4a1 1 0 0 1 1-1h6" />
  </svg>
);

interface SkillRow {
  id: number | string;
  name: string;
  description?: string;
  source: 'system' | 'custom';
  lang: string;
  enabled: boolean;
  system: boolean;
  requiredTools?: string;
  createdAt?: string;
  tags: string[];
  readOnly?: boolean;
  toolSchema?: unknown;
}

interface SkillDetailData {
  name: string;
  description?: string;
  skillMd?: string;
  promptContent?: string;
  references?: Record<string, string>;
  scripts?: Array<{ name: string; content: string }>;
  requiredTools?: string;
  enabled?: boolean;
  createdAt?: string;
}

function guessLang(name: string): string {
  const lower = name.toLowerCase();
  if (lower.endsWith('.ts') || lower.includes('typescript')) return 'ts';
  if (lower.endsWith('.py') || lower.includes('python')) return 'py';
  if (lower.endsWith('.sh') || lower.includes('bash') || lower.includes('shell')) return 'sh';
  if (lower.includes('json') || lower.includes('schema')) return 'json';
  return 'md';
}

function deriveTags(row: Record<string, unknown>): string[] {
  const tags: string[] = [];
  const tools = row.requiredTools;
  if (typeof tools === 'string' && tools.trim()) {
    tools.split(',').forEach(t => { if (t.trim()) tags.push(t.trim()); });
  }
  return tags;
}

function normalizeSkill(raw: Record<string, unknown>): SkillRow {
  const name = String(raw.name || '');
  const isSystem = raw.system === true || raw.source === 'system';
  return {
    id: raw.id != null ? raw.id as number : name,
    name,
    description: raw.description ? String(raw.description) : undefined,
    source: isSystem ? 'system' : 'custom',
    lang: guessLang(name),
    enabled: raw.enabled !== false,
    system: isSystem,
    requiredTools: raw.requiredTools ? String(raw.requiredTools) : undefined,
    createdAt: raw.createdAt ? String(raw.createdAt) : undefined,
    tags: deriveTags(raw),
    readOnly: raw.readOnly as boolean | undefined,
    toolSchema: raw.toolSchema,
  };
}

function timeAgo(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const ms = Date.now() - d.getTime();
  const m = Math.floor(ms / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const days = Math.floor(h / 24);
  return `${days}d ago`;
}

const SkillList: React.FC = () => {
  const queryClient = useQueryClient();
  const [view, setView] = useState<'grid' | 'table'>('grid');
  const [q, setQ] = useState('');
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [filterSource, setFilterSource] = useState<string | null>(null);
  const [open, setOpen] = useState<SkillRow | null>(null);
  const [drawerTab, setDrawerTab] = useState('readme');
  const [creating, setCreating] = useState(false);

  const { data: rawSkills = [] } = useQuery({
    queryKey: ['skills'],
    queryFn: () => getSkills().then(res => extractList<Record<string, unknown>>(res)),
  });

  const all = useMemo<SkillRow[]>(() => rawSkills.map(normalizeSkill), [rawSkills]);

  const rows = useMemo(() => {
    return all.filter(s => {
      if (q) {
        const ql = q.toLowerCase();
        const hay = `${s.name} ${s.description || ''} ${s.tags.join(' ')}`.toLowerCase();
        if (!hay.includes(ql)) return false;
      }
      if (filterStatus) {
        if (filterStatus === 'active' && !s.enabled) return false;
        if (filterStatus === 'disabled' && s.enabled) return false;
      }
      if (filterSource && s.source !== filterSource) return false;
      return true;
    });
  }, [all, q, filterStatus, filterSource]);

  const toggle = (key: 'status' | 'source', value: string) => {
    if (key === 'status') setFilterStatus(v => v === value ? null : value);
    else setFilterSource(v => v === value ? null : value);
  };

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteSkill(id),
    onSuccess: () => { message.success('Skill deleted'); queryClient.invalidateQueries({ queryKey: ['skills'] }); },
    onError: () => message.error('Failed to delete'),
  });
  const toggleMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) => toggleSkill(id, enabled),
    onSuccess: (_, v) => { message.success(v.enabled ? 'Enabled' : 'Disabled'); queryClient.invalidateQueries({ queryKey: ['skills'] }); },
    onError: () => message.error('Toggle failed'),
  });
  const uploadMutation = useMutation({
    mutationFn: (file: File) => uploadSkill(file, 1),
    onSuccess: () => { message.success('Skill uploaded'); queryClient.invalidateQueries({ queryKey: ['skills'] }); setCreating(false); },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { error?: string } } };
      message.error(e.response?.data?.error || 'Upload failed');
    },
  });

  const openDetail = (s: SkillRow) => { setOpen(s); setDrawerTab('readme'); };

  return (
    <div className="agents-view">
      {/* Filter sidebar */}
      <aside className="agents-filters">
        <div className="agents-filters-h">Search</div>
        <input className="agents-search" placeholder="name, tag, description…" value={q} onChange={e => setQ(e.target.value)} />

        <div className="agents-filters-h">Status</div>
        <FilterItem label="active" count={all.filter(s => s.enabled).length} active={filterStatus === 'active'} onClick={() => toggle('status', 'active')} />
        <FilterItem label="disabled" count={all.filter(s => !s.enabled).length} active={filterStatus === 'disabled'} onClick={() => toggle('status', 'disabled')} />

        <div className="agents-filters-h">Source</div>
        <FilterItem label="system" count={all.filter(s => s.source === 'system').length} active={filterSource === 'system'} onClick={() => toggle('source', 'system')} />
        <FilterItem label="custom" count={all.filter(s => s.source === 'custom').length} active={filterSource === 'custom'} onClick={() => toggle('source', 'custom')} />
      </aside>

      {/* Main */}
      <section className="agents-main">
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Skills</h1>
            <p className="agents-head-sub">{rows.length} of {all.length} · reusable building blocks for agents</p>
          </div>
          <div className="agents-head-actions">
            <div className="view-seg">
              <button className={view === 'grid' ? 'on' : ''} onClick={() => setView('grid')}>Grid</button>
              <button className={view === 'table' ? 'on' : ''} onClick={() => setView('table')}>Table</button>
            </div>
            <button className="btn-primary-sf" onClick={() => setCreating(true)}>{PLUS_ICON} New skill</button>
          </div>
        </header>

        <div className="agents-body">
          {rows.length === 0 ? (
            <div className="sf-empty-state">No skills match your filters.</div>
          ) : view === 'grid' ? (
            <div className="skills-grid-sf">
              {rows.map(s => (
                <button key={s.id} className="skill-card-sf" onClick={() => openDetail(s)}>
                  <div className="skill-card-head-sf">
                    <span className={`skill-lang-sf lang-${s.lang}`}>{s.lang}</span>
                    <span className="skill-name-sf">{s.name}</span>
                    {!s.enabled && <span className="skill-draft-sf">disabled</span>}
                    <span className={`skill-source-sf src-${s.source}`}>{s.source}</span>
                  </div>
                  {s.description && <div className="skill-sum-sf">{s.description}</div>}
                  <div className="skill-meta-sf">
                    {s.createdAt && <span>{timeAgo(s.createdAt)}</span>}
                  </div>
                  {s.tags.length > 0 && (
                    <div className="skill-tags-sf">
                      {s.tags.map(t => <span key={t} className="tag-chip-sf">#{t}</span>)}
                    </div>
                  )}
                </button>
              ))}
            </div>
          ) : (
            <table className="skills-table-sf">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Description</th>
                  <th>Source</th>
                  <th>Status</th>
                  <th>Updated</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(s => (
                  <tr key={s.id} onClick={() => openDetail(s)}>
                    <td>
                      <div className="t-name-sf">
                        <span className={`skill-lang-sf lang-${s.lang}`}>{s.lang}</span>
                        <b>{s.name}</b>
                      </div>
                    </td>
                    <td>{s.description || '—'}</td>
                    <td><span className={`skill-source-sf src-${s.source}`}>{s.source}</span></td>
                    <td>
                      <span className={`status-pill-sf ${s.enabled ? 's-ok' : 's-draft'}`}>
                        <span className="status-dot-sf" /> {s.enabled ? 'active' : 'disabled'}
                      </span>
                    </td>
                    <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>{timeAgo(s.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>

      {/* Detail drawer */}
      {open && (
        <SkillDrawer
          skill={open}
          tab={drawerTab}
          setTab={setDrawerTab}
          onClose={() => setOpen(null)}
          onToggle={(id, en) => toggleMutation.mutate({ id: id as number, enabled: en })}
          onDelete={(id) => { deleteMutation.mutate(id as number); setOpen(null); }}
        />
      )}

      {/* New Skill modal */}
      {creating && (
        <NewSkillModal
          onClose={() => setCreating(false)}
          onUpload={(file) => uploadMutation.mutate(file)}
          uploading={uploadMutation.isPending}
        />
      )}
    </div>
  );
};

/* ─── Filter item ─── */
function FilterItem({ label, count, active, onClick }: { label: string; count: number; active: boolean; onClick: () => void }) {
  return (
    <button className={`filter-item ${active ? 'on' : ''}`} onClick={onClick}>
      <span>{label}</span>
      <span className="filter-item-count">{count}</span>
    </button>
  );
}

/* ─── Skill Detail Drawer ─── */
function SkillDrawer({ skill, tab, setTab, onClose, onToggle, onDelete }: {
  skill: SkillRow;
  tab: string;
  setTab: (t: string) => void;
  onClose: () => void;
  onToggle: (id: number | string, enabled: boolean) => void;
  onDelete: (id: number | string) => void;
}) {
  const { data: detail, isLoading } = useQuery<SkillDetailData>({
    queryKey: ['skill-detail', skill.id],
    queryFn: () => getSkillDetail(skill.id).then(r => r.data),
    enabled: typeof skill.id === 'number',
  });

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const tabs = [
    { id: 'readme', label: 'README' },
    { id: 'files', label: 'Files', count: detail?.references ? Object.keys(detail.references).length : 0 },
    { id: 'source', label: 'Source' },
    ...(skill.toolSchema ? [{ id: 'schema', label: 'Schema' }] : []),
  ];

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div>
              <h2 className="sf-drawer-title">{skill.name}</h2>
              {skill.description && <p className="sf-drawer-subtitle">{skill.description}</p>}
            </div>
            <div className="sf-drawer-actions">
              {!skill.system && (
                <>
                  <button className="btn-ghost-sf" onClick={() => onToggle(skill.id, !skill.enabled)}>
                    {skill.enabled ? 'Disable' : 'Enable'}
                  </button>
                  <button className="btn-ghost-sf" style={{ color: 'var(--color-err)' }} onClick={() => onDelete(skill.id)}>
                    Delete
                  </button>
                </>
              )}
            </div>
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`skill-lang-sf lang-${skill.lang}`}>{skill.lang}</span>
            <span className={`skill-source-sf src-${skill.source}`}>{skill.source}</span>
            <span className={`status-pill-sf ${skill.enabled ? 's-ok' : 's-draft'}`}>
              <span className="status-dot-sf" /> {skill.enabled ? 'active' : 'disabled'}
            </span>
          </div>
        </div>

        <nav className="sf-drawer-tabs">
          {tabs.map(t => (
            <button key={t.id} className={`sf-drawer-tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>
              {t.label}
              {'count' in t && t.count != null && <span className="badge">{t.count}</span>}
            </button>
          ))}
        </nav>

        <div className="sf-drawer-body">
          {isLoading && typeof skill.id === 'number' && (
            <div className="sf-empty-state">Loading…</div>
          )}

          {tab === 'readme' && (
            <div className="sf-code-block" style={{ whiteSpace: 'pre-wrap' }}>
              {detail?.skillMd || detail?.promptContent || skill.description || 'No README content.'}
            </div>
          )}

          {tab === 'files' && (
            detail?.references && Object.keys(detail.references).length > 0 ? (
              <div className="sf-bundle-tree">
                <div className="sf-bundle-h">
                  <span>Bundle contents</span>
                  <span className="sf-mono-sm">{Object.keys(detail.references).length} files</span>
                </div>
                {Object.entries(detail.references).map(([name, content]) => (
                  <div key={name} className="sf-bundle-row">
                    <span className={`skill-lang-sf lang-${name.split('.').pop() || 'md'}`}>{name.split('.').pop()}</span>
                    <span className="sf-mono-sm">{name}</span>
                    <span className="sf-mono-sm" style={{ marginLeft: 'auto' }}>
                      {typeof content === 'string' ? `${(content.length / 1024).toFixed(1)} KB` : '—'}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <div className="sf-empty-state">No reference files.</div>
            )
          )}

          {tab === 'source' && (
            detail?.scripts && detail.scripts.length > 0 ? (
              detail.scripts.map(s => (
                <div key={s.name} style={{ marginBottom: 14 }}>
                  <div className="sf-code-bar">
                    <span>{s.name}</span>
                    <button className="sf-mini-btn" style={{ marginLeft: 'auto' }}>{COPY_ICON} copy</button>
                  </div>
                  <pre className="sf-code-block">{s.content}</pre>
                </div>
              ))
            ) : (
              <div className="sf-empty-state">No source scripts.</div>
            )
          )}

          {tab === 'schema' && skill.toolSchema != null && (
            <div>
              <div className="sf-section-h">Input schema</div>
              <pre className="sf-code-block">{JSON.stringify(skill.toolSchema, null, 2)}</pre>
            </div>
          )}
        </div>
      </aside>
    </>
  );
}

/* ─── New Skill Modal ─── */
function NewSkillModal({ onClose, onUpload, uploading }: {
  onClose: () => void;
  onUpload: (file: File) => void;
  uploading: boolean;
}) {
  const [mode, setMode] = useState<'upload' | 'blank'>('upload');
  const [file, setFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const onFile = (f: File | undefined) => {
    if (!f) return;
    if (!/\.(zip|tar|tgz)$/i.test(f.name)) { message.warning('Only .zip files are supported'); return; }
    setFile(f);
  };

  return (
    <div className="sf-modal-scrim" onClick={onClose}>
      <div className="sf-modal" onClick={e => e.stopPropagation()}>
        <div className="sf-modal-h">
          <h3>New skill</h3>
          <button className="sf-icon-btn" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
        </div>

        <div className="sf-modal-tabs">
          <button className={`sf-modal-tab-btn ${mode === 'upload' ? 'on' : ''}`} onClick={() => setMode('upload')}>Upload bundle</button>
          <button className={`sf-modal-tab-btn ${mode === 'blank' ? 'on' : ''}`} onClick={() => setMode('blank')}>Start blank</button>
        </div>

        <div className="sf-modal-body">
          {mode === 'upload' && (
            <>
              <div
                className={`sf-upload ${dragOver ? 'drag' : ''} ${file ? 'has-file' : ''}`}
                onClick={() => fileRef.current?.click()}
                onDragOver={e => { e.preventDefault(); setDragOver(true); }}
                onDragLeave={() => setDragOver(false)}
                onDrop={e => { e.preventDefault(); setDragOver(false); onFile(e.dataTransfer.files[0]); }}
              >
                <input ref={fileRef} type="file" accept=".zip" style={{ display: 'none' }} onChange={e => onFile(e.target.files?.[0])} />
                {!file ? (
                  <>
                    <div className="sf-upload-icon">{PLUS_ICON}</div>
                    <div className="sf-upload-t">Drop a <b>.zip</b> skill bundle here</div>
                    <div className="sf-upload-s">or click to browse</div>
                    <div className="sf-upload-hint">
                      Bundle must contain a <code>SKILL.md</code> manifest at the root.
                    </div>
                  </>
                ) : (
                  <div className="sf-upload-ok">
                    <div>
                      <div className="sf-upload-filename">{file.name}</div>
                      <div className="sf-upload-filemeta">{(file.size / 1024).toFixed(1)} KB</div>
                    </div>
                    <button className="sf-mini-btn" onClick={e => { e.stopPropagation(); setFile(null); }}>replace</button>
                  </div>
                )}
              </div>
            </>
          )}

          {mode === 'blank' && (
            <div className="sf-empty-state" style={{ padding: '30px 20px' }}>
              Blank skill creation requires the CLI.<br />
              Use <code style={{ fontFamily: 'var(--font-mono)', background: 'var(--bg-hover)', padding: '2px 6px', borderRadius: 3 }}>skillforge skill init</code> to scaffold a new skill.
            </div>
          )}
        </div>

        <div className="sf-modal-f">
          <button className="btn-ghost-sf" onClick={onClose}>Cancel</button>
          <button
            className="btn-primary-sf"
            disabled={!file || uploading}
            onClick={() => file && onUpload(file)}
          >
            {uploading ? 'Uploading…' : 'Install skill'}
          </button>
        </div>
      </div>
    </div>
  );
}

export default SkillList;
