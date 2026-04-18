import React, { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getTools, extractList } from '../api';
import '../components/agents/agents.css';
import '../components/skills/skills.css';
import '../components/tools/tools.css';

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);
const COPY_ICON = (
  <svg width={11} height={11} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4">
    <rect x="5" y="5" width="8" height="8" rx="1.5" /><path d="M3 10V4a1 1 0 0 1 1-1h6" />
  </svg>
);
const PLAY_ICON = (
  <svg width={10} height={10} viewBox="0 0 16 16" fill="currentColor"><path d="M4 3l10 5-10 5z" /></svg>
);

interface ToolRow {
  id: string;
  name: string;
  description?: string;
  category: string;
  readOnly: boolean;
  danger: number;
  toolSchema?: unknown;
}

function guessCat(name: string, desc?: string): string {
  const n = (name + ' ' + (desc || '')).toLowerCase();
  if (n.includes('bash') || n.includes('shell') || n.includes('command')) return 'shell';
  if (n.includes('file') || n.includes('read') || n.includes('write') || n.includes('glob') || n.includes('grep') || n.includes('edit') || n.includes('patch')) return 'fs';
  if (n.includes('web') || n.includes('fetch') || n.includes('http') || n.includes('search')) return 'net';
  if (n.includes('agent') || n.includes('spawn') || n.includes('task') || n.includes('sub')) return 'orchestration';
  if (n.includes('memory') || n.includes('store') || n.includes('persist')) return 'memory';
  if (n.includes('slack') || n.includes('linear') || n.includes('notify') || n.includes('integration')) return 'integrations';
  return 'fs';
}

function guessDanger(name: string, readOnly: boolean): number {
  if (readOnly) return 0;
  const n = name.toLowerCase();
  if (n.includes('bash') || n.includes('patch') || n.includes('delete') || n.includes('shell')) return 2;
  if (n.includes('write') || n.includes('edit') || n.includes('spawn') || n.includes('memory_write') || n.includes('post') || n.includes('create')) return 1;
  return 0;
}

function normalizeTool(raw: Record<string, unknown>): ToolRow {
  const name = String(raw.name || '');
  const desc = raw.description ? String(raw.description) : undefined;
  const readOnly = raw.readOnly === true;
  return {
    id: name,
    name,
    description: desc,
    category: guessCat(name, desc),
    readOnly,
    danger: guessDanger(name, readOnly),
    toolSchema: raw.toolSchema,
  };
}

function dangerLabel(d: number): { text: string; cls: string } {
  if (d === 0) return { text: 'safe', cls: 'd-0' };
  if (d === 1) return { text: 'scoped', cls: 'd-1' };
  return { text: 'privileged', cls: 'd-2' };
}

const ToolList: React.FC = () => {
  const [q, setQ] = useState('');
  const [filterCat, setFilterCat] = useState<string | null>(null);
  const [filterDanger, setFilterDanger] = useState<number | null>(null);
  const [open, setOpen] = useState<ToolRow | null>(null);
  const [drawerTab, setDrawerTab] = useState('schema');

  const { data: rawTools = [] } = useQuery({
    queryKey: ['tools'],
    queryFn: () => getTools().then(res => extractList<Record<string, unknown>>(res)),
  });

  const all = useMemo<ToolRow[]>(() => rawTools.map(normalizeTool), [rawTools]);
  const cats = useMemo(() => Array.from(new Set(all.map(t => t.category))).sort(), [all]);

  const rows = useMemo(() => {
    return all.filter(t => {
      if (q) {
        const ql = q.toLowerCase();
        if (!`${t.name} ${t.description || ''} ${t.category}`.toLowerCase().includes(ql)) return false;
      }
      if (filterCat && t.category !== filterCat) return false;
      if (filterDanger != null && t.danger !== filterDanger) return false;
      return true;
    });
  }, [all, q, filterCat, filterDanger]);

  const toggleCat = (v: string) => setFilterCat(c => c === v ? null : v);
  const toggleDanger = (v: number) => setFilterDanger(d => d === v ? null : v);

  const openDetail = (t: ToolRow) => { setOpen(t); setDrawerTab('schema'); };

  return (
    <div className="agents-view">
      {/* Filter sidebar */}
      <aside className="agents-filters">
        <div className="agents-filters-h">Search</div>
        <input className="agents-search" placeholder="tool name, description…" value={q} onChange={e => setQ(e.target.value)} />

        <div className="agents-filters-h">Category</div>
        {cats.map(c => (
          <FilterItem key={c} label={c} count={all.filter(t => t.category === c).length} active={filterCat === c} onClick={() => toggleCat(c)} />
        ))}

        <div className="agents-filters-h">Trust tier</div>
        <FilterItem label="safe" count={all.filter(t => t.danger === 0).length} active={filterDanger === 0} onClick={() => toggleDanger(0)} />
        <FilterItem label="scoped" count={all.filter(t => t.danger === 1).length} active={filterDanger === 1} onClick={() => toggleDanger(1)} />
        <FilterItem label="privileged" count={all.filter(t => t.danger === 2).length} active={filterDanger === 2} onClick={() => toggleDanger(2)} />
      </aside>

      {/* Main */}
      <section className="agents-main">
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Tools</h1>
            <p className="agents-head-sub">{rows.length} of {all.length} · built-in capabilities agents can call</p>
          </div>
          <div className="agents-head-actions">
            <button className="btn-ghost-sf">{COPY_ICON} Export registry</button>
          </div>
        </header>

        <div className="agents-body">
          {rows.length === 0 ? (
            <div className="sf-empty-state">No tools match your filters.</div>
          ) : (
            <div className="tools-table-sf">
              <div className="tools-table-h-sf">
                <div>Tool</div>
                <div>Trust</div>
                <div style={{ textAlign: 'right' }}>Read-only</div>
                <div style={{ textAlign: 'right' }}>Category</div>
                <div style={{ textAlign: 'right' }}>Schema</div>
                <div />
              </div>
              {rows.map(t => {
                const d = dangerLabel(t.danger);
                return (
                  <button key={t.id} className="tools-row-sf" onClick={() => openDetail(t)}>
                    <div className="tools-row-name-col-sf">
                      <span className={`tool-cat-sf cat-${t.category}`}>{t.category}</span>
                      <div>
                        <div className="tools-row-name-sf">{t.name}</div>
                        {t.description && <div className="tools-row-desc-sf">{t.description}</div>}
                      </div>
                    </div>
                    <div><span className={`tool-danger-sf ${d.cls}`}>{d.text}</span></div>
                    <div className="tools-num-sf">{t.readOnly ? 'yes' : 'no'}</div>
                    <div className="tools-num-sf">{t.category}</div>
                    <div className="tools-num-sf">{t.toolSchema ? 'yes' : '—'}</div>
                    <div />
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </section>

      {/* Detail drawer */}
      {open && (
        <ToolDrawer
          tool={open}
          tab={drawerTab}
          setTab={setDrawerTab}
          onClose={() => setOpen(null)}
        />
      )}
    </div>
  );
};

function FilterItem({ label, count, active, onClick }: { label: string; count: number; active: boolean; onClick: () => void }) {
  return (
    <button className={`filter-item ${active ? 'on' : ''}`} onClick={onClick}>
      <span>{label}</span>
      <span className="filter-item-count">{count}</span>
    </button>
  );
}

function ToolDrawer({ tool, tab, setTab, onClose }: {
  tool: ToolRow;
  tab: string;
  setTab: (t: string) => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const d = dangerLabel(tool.danger);
  const tabs = [
    { id: 'schema', label: 'Schema' },
    { id: 'perms', label: 'Permissions' },
  ];

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer" role="dialog">
        <div className="sf-drawer-head">
          <div className="sf-drawer-head-row">
            <div>
              <h2 className="sf-drawer-title">{tool.name}</h2>
              {tool.description && <p className="sf-drawer-subtitle">{tool.description}</p>}
            </div>
            <div className="sf-drawer-actions">
              <button className="btn-ghost-sf">{PLAY_ICON} Try</button>
            </div>
            <button className="sf-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
          </div>
          <div className="sf-drawer-badges">
            <span className={`tool-cat-sf cat-${tool.category}`}>{tool.category}</span>
            <span className={`tool-danger-sf ${d.cls}`}>{d.text}</span>
            <span className="kv-chip-sf">{tool.readOnly ? 'read-only' : 'read-write'}</span>
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
          {tab === 'schema' && (
            <div>
              <div className="sf-section-h">Input schema</div>
              {tool.toolSchema ? (
                <>
                  <div className="sf-code-bar">
                    <span>{tool.name}.json</span>
                    <button className="sf-mini-btn" style={{ marginLeft: 'auto' }}>{COPY_ICON} copy</button>
                  </div>
                  <pre className="sf-code-block">{JSON.stringify(tool.toolSchema, null, 2)}</pre>
                </>
              ) : (
                <div className="sf-empty-state">No schema available for this tool.</div>
              )}
            </div>
          )}

          {tab === 'perms' && (
            <div className="tool-perms-sf">
              <div>
                <div className="sf-section-h">Trust tier</div>
                <span className={`tool-danger-sf ${d.cls}`}>{d.text}</span>
              </div>
              <div>
                <div className="sf-section-h">Access policy</div>
                <p style={{ fontSize: 'var(--font-size-sm)', color: 'var(--fg-2)', margin: 0 }}>
                  {tool.danger === 2
                    ? 'Privileged — user must approve each call in ask mode.'
                    : tool.danger === 1
                    ? 'Scoped — allowed within agent allowlist only.'
                    : 'Safe — runs without approval.'}
                </p>
              </div>
              <div>
                <div className="sf-section-h">Read-only</div>
                <p style={{ fontSize: 'var(--font-size-sm)', color: 'var(--fg-2)', margin: 0 }}>
                  {tool.readOnly ? 'Yes — this tool cannot modify state.' : 'No — this tool can modify files or state.'}
                </p>
              </div>
            </div>
          )}
        </div>
      </aside>
    </>
  );
}

export default ToolList;
