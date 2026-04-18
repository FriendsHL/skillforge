import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { updateAgent, getTools, getSkills, getLifecycleHookMethods, extractList, type UpdateAgentRequest } from '../../api';
import type { AgentDto } from '../../api/schemas';
import { initials, guessRole } from './AgentCard';

interface HookEventMeta { id: string; phase: string; desc: string; abort: boolean }
interface HookTypeMeta { id: string; desc: string }

const HOOK_EVENTS: HookEventMeta[] = [
  { id: 'SessionStart',     phase: 'lifecycle', desc: 'Runs once when a new session spins up. Seed context, fetch state.',       abort: true  },
  { id: 'UserPromptSubmit', phase: 'input',     desc: 'Before the prompt hits the model. Filter, enrich, reject unsafe input.', abort: true  },
  { id: 'PreToolUse',       phase: 'tool',      desc: 'Fires before any tool call. Validate args, rate-limit, deny.',           abort: true  },
  { id: 'PostToolUse',      phase: 'tool',      desc: 'After a tool returns. Inspect, log, post-process the result.',           abort: false },
  { id: 'PostResponse',     phase: 'output',    desc: 'After the model finishes a turn. Grade, store, notify.',                 abort: false },
  { id: 'SessionEnd',       phase: 'lifecycle', desc: 'When the session closes. Flush logs, emit metrics, notify channels.',    abort: false },
];
const HOOK_EVENT_BY_ID = Object.fromEntries(HOOK_EVENTS.map(e => [e.id, e]));

const HOOK_TYPES: HookTypeMeta[] = [
  { id: 'skill',   desc: 'Call a named skill in the skill registry.' },
  { id: 'method',  desc: 'Invoke a built-in method (http.notify, log.file, feishu.notify…).' },
  { id: 'command', desc: 'Shell out to a script or binary.' },
];
const HOOK_TYPE_BY_ID = Object.fromEntries(HOOK_TYPES.map(t => [t.id, t]));

const CLOSE_ICON = (
  <svg width={14} height={14} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M4 4l8 8M12 4l-8 8" />
  </svg>
);
const PLUS_ICON = (
  <svg width={13} height={13} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
    <path d="M8 3v10M3 8h10" />
  </svg>
);
const COPY_ICON = (
  <svg width={11} height={11} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4">
    <rect x="5" y="5" width="8" height="8" rx="1.5" /><path d="M3 10V4a1 1 0 0 1 1-1h6" />
  </svg>
);
const PLAY_ICON = (
  <svg width={11} height={11} viewBox="0 0 16 16" fill="currentColor"><path d="M4 3l10 5-10 5z" /></svg>
);
const CHEVRON_DOWN = (
  <svg width={10} height={10} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M4 6l4 4 4-4" />
  </svg>
);

function parseArr(raw: unknown): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw.map(String);
  if (typeof raw === 'string') {
    try { const a = JSON.parse(raw); return Array.isArray(a) ? a.map(String) : []; } catch { return []; }
  }
  return [];
}

interface RuleItem { kind: string; text: string; group: string }

function parseRules(raw: unknown): RuleItem[] {
  if (!raw) return [];
  try {
    const cfg = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!cfg || typeof cfg !== 'object') return [];
    const rules: RuleItem[] = [];
    const overrides = (cfg as Record<string, unknown>).ruleOverrides;
    if (overrides && typeof overrides === 'object') {
      for (const [key, val] of Object.entries(overrides)) {
        if (val === true) rules.push({ kind: 'MUST', text: key, group: 'preset' });
      }
    }
    const custom = (cfg as Record<string, unknown>).customRules;
    if (Array.isArray(custom)) {
      for (const c of custom) {
        if (c && typeof c === 'object') {
          rules.push({
            kind: (c as Record<string, string>).severity ?? 'SHOULD',
            text: (c as Record<string, string>).text ?? String(c),
            group: 'custom',
          });
        }
      }
    }
    return rules;
  } catch { return []; }
}

interface HookItem { event: string; name: string; type: string; description?: string; abortOnError?: boolean }

function parseHooks(raw: unknown): HookItem[] {
  if (!raw) return [];
  try {
    let data = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (data && typeof data === 'object' && 'hooks' in data) {
      data = (data as Record<string, unknown>).hooks;
    }
    if (Array.isArray(data)) return data as HookItem[];
    if (data && typeof data === 'object') {
      const result: HookItem[] = [];
      for (const [event, handlers] of Object.entries(data as Record<string, unknown>)) {
        if (Array.isArray(handlers)) {
          for (const h of handlers) {
            if (h && typeof h === 'object') {
              const entry = h as Record<string, unknown>;
              const handler = entry.handler as Record<string, string> | undefined;
              const name = handler?.skill || handler?.method || handler?.command
                || (entry as Record<string, string>).skill || (entry as Record<string, string>).method || (entry as Record<string, string>).command || 'unknown';
              const type = (handler?.skill || (entry as Record<string, string>).skill) ? 'skill'
                : (handler?.method || (entry as Record<string, string>).method) ? 'method' : 'command';
              result.push({ event, name: String(name), type });
            }
          }
        }
      }
      return result;
    }
  } catch { /* ignore */ }
  return [];
}

function useClickOutside(ref: React.RefObject<HTMLElement | null>, handler: () => void) {
  useEffect(() => {
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) handler();
    };
    window.addEventListener('mousedown', h);
    return () => window.removeEventListener('mousedown', h);
  }, [ref, handler]);
}

function HookEventPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(ref, close);
  const cur = HOOK_EVENT_BY_ID[value] || HOOK_EVENTS[0];
  return (
    <div className="hook-picker-sf" ref={ref}>
      <button type="button" className={`hook-picker-btn-sf event ${open ? 'open' : ''}`} onClick={() => setOpen(o => !o)}>
        <span className={`phase-dot-sf ph-${cur.phase}`} />
        <span className="hook-picker-label">{cur.id}</span>
        {CHEVRON_DOWN}
      </button>
      {open && (
        <div className="hook-pop-sf" role="listbox">
          <div className="hook-pop-head-sf">Lifecycle event</div>
          {HOOK_EVENTS.map(e => (
            <button
              key={e.id}
              type="button"
              className={`hook-pop-item-sf ${e.id === value ? 'on' : ''}`}
              onClick={() => { onChange(e.id); setOpen(false); }}
            >
              <div className="hook-pop-item-top-sf">
                <span className={`phase-dot-sf ph-${e.phase}`} />
                <span className="hook-pop-item-name-sf">{e.id}</span>
                {e.abort && <span className="hook-abort-tag-sf">CAN ABORT</span>}
              </div>
              <div className="hook-pop-item-desc-sf">{e.desc}</div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function HookTypePicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(ref, close);
  const cur = HOOK_TYPE_BY_ID[value] || HOOK_TYPES[0];
  return (
    <div className="hook-picker-sf" ref={ref}>
      <button type="button" className={`hook-picker-btn-sf type type-${cur.id} ${open ? 'open' : ''}`} onClick={() => setOpen(o => !o)}>
        <span className="hook-picker-label">{cur.id}</span>
        {CHEVRON_DOWN}
      </button>
      {open && (
        <div className="hook-pop-sf" role="listbox">
          <div className="hook-pop-head-sf">Handler type</div>
          {HOOK_TYPES.map(t => (
            <button
              key={t.id}
              type="button"
              className={`hook-pop-item-sf ${t.id === value ? 'on' : ''}`}
              onClick={() => { onChange(t.id); setOpen(false); }}
            >
              <div className="hook-pop-item-top-sf">
                <span className={`type-pill-sf type-${t.id}`}>{t.id}</span>
              </div>
              <div className="hook-pop-item-desc-sf">{t.desc}</div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

const COMMON_COMMANDS: CatalogItem[] = [
  { id: 'bash', desc: 'Execute a shell script' },
  { id: 'curl', desc: 'HTTP request via CLI' },
  { id: 'node', desc: 'Run Node.js script' },
  { id: 'python', desc: 'Run Python script' },
  { id: 'git', desc: 'Git operations' },
  { id: 'docker', desc: 'Docker CLI' },
  { id: 'npm', desc: 'Node package manager' },
  { id: 'pnpm', desc: 'Fast Node package manager' },
  { id: 'npx', desc: 'Execute npm package binaries' },
  { id: 'make', desc: 'Run Makefile targets' },
];

interface CatalogItem { id: string; desc?: string; tag?: string }

function HookHandlerPicker({ value, type, onChange, skillCatalog, methodCatalog }: {
  value: string;
  type: string;
  onChange: (v: string) => void;
  skillCatalog: CatalogItem[];
  methodCatalog: CatalogItem[];
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const ref = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(ref, close);

  useEffect(() => {
    if (open) setTimeout(() => inputRef.current?.focus(), 20);
  }, [open]);

  const catalog = type === 'skill' ? skillCatalog : type === 'method' ? methodCatalog : COMMON_COMMANDS;
  const ql = q.trim().toLowerCase();
  const filtered = ql
    ? catalog.filter(c => c.id.toLowerCase().includes(ql) || (c.desc || '').toLowerCase().includes(ql))
    : catalog;

  return (
    <div className="hook-handler-picker-sf" ref={ref}>
      <button
        type="button"
        className={`hook-handler-btn-sf ${open ? 'open' : ''} ${value ? 'has-val' : ''}`}
        onClick={() => setOpen(o => !o)}
        title={value || `Pick a ${type}`}
      >
        <span style={{ color: 'var(--fg-4)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>→</span>
        <span className="hook-handler-val">{value || `pick ${type}…`}</span>
        {CHEVRON_DOWN}
      </button>
      {open && (
        <div className="hook-pop-sf handler-pop" role="listbox">
          <div className="attach-pop-head-sf">
            <input
              ref={inputRef}
              className="attach-pop-search-sf"
              placeholder={`Search ${type}s…`}
              value={q}
              onChange={e => setQ(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter' && q.trim()) {
                  onChange(q.trim());
                  setQ('');
                  setOpen(false);
                }
              }}
            />
            <span className="attach-pop-count-sf">{filtered.length}</span>
          </div>
          <div className="attach-pop-list-sf" style={{ maxHeight: 200 }}>
            {filtered.length === 0 && (
              <div className="attach-pop-empty-sf">
                {q ? <span>No match. Press <b>Enter</b> to use "{q}".</span> : `No ${type}s available.`}
              </div>
            )}
            {filtered.map(c => (
              <button
                key={c.id}
                type="button"
                className={`attach-pop-item-sf ${c.id === value ? 'on' : ''}`}
                onClick={() => { onChange(c.id); setQ(''); setOpen(false); }}
              >
                <div className="attach-pop-item-top-sf">
                  <span className="attach-pop-item-name-sf">{c.id}</span>
                </div>
                {c.desc && <div className="attach-pop-item-desc-sf">{c.desc}</div>}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function AttachPicker({ kind, catalog, attached, onPick }: {
  kind: string;
  catalog: CatalogItem[];
  attached: string[];
  onPick: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const ref = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(ref, close);

  useEffect(() => {
    if (open) setTimeout(() => inputRef.current?.focus(), 20);
  }, [open]);

  const attachedSet = useMemo(() => new Set(attached), [attached]);
  const available = useMemo(() => catalog.filter(c => !attachedSet.has(c.id)), [catalog, attachedSet]);
  const ql = q.trim().toLowerCase();
  const filtered = ql
    ? available.filter(c => c.id.toLowerCase().includes(ql) || (c.desc || '').toLowerCase().includes(ql) || (c.tag || '').toLowerCase().includes(ql))
    : available;

  return (
    <div className="attach-picker-sf" ref={ref}>
      <button type="button" className="chip-pill-add-sf" onClick={() => setOpen(o => !o)}>
        {PLUS_ICON} add {kind}
      </button>
      {open && (
        <div className="attach-pop-sf">
          <div className="attach-pop-head-sf">
            <input
              ref={inputRef}
              className="attach-pop-search-sf"
              placeholder={`Search ${kind}s…`}
              value={q}
              onChange={e => setQ(e.target.value)}
            />
            <span className="attach-pop-count-sf">{filtered.length}</span>
          </div>
          <div className="attach-pop-list-sf">
            {filtered.length === 0 && (
              <div className="attach-pop-empty-sf">
                {available.length === 0 ? `All ${kind}s already attached.` : `No ${kind}s match "${q}".`}
              </div>
            )}
            {filtered.map(c => (
              <button
                key={c.id}
                type="button"
                className="attach-pop-item-sf"
                onClick={() => { onPick(c.id); setQ(''); setOpen(false); }}
              >
                <div className="attach-pop-item-top-sf">
                  <span className={`attach-pop-item-name-sf ${kind}`}>{c.id}</span>
                  {c.tag && <span className="attach-pop-tag-sf">{c.tag}</span>}
                </div>
                {c.desc && <div className="attach-pop-item-desc-sf">{c.desc}</div>}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

interface AgentDrawerProps {
  agent: AgentDto;
  onClose: () => void;
}

const AgentDrawer: React.FC<AgentDrawerProps> = ({ agent, onClose }) => {
  const queryClient = useQueryClient();
  const [tab, setTab] = useState('overview');
  const [mdFile, setMdFile] = useState<'AGENT.md' | 'SOUL.md' | 'MEMORY.md'>('AGENT.md');
  const [dirty, setDirty] = useState<Record<string, boolean>>({});
  const [promptDraft, setPromptDraft] = useState({
    'AGENT.md': agent.systemPrompt || '',
    'SOUL.md': agent.soulPrompt || '',
    'MEMORY.md': '',
  });
  const [mode, setMode] = useState(agent.executionMode || 'ask');

  const [rules, setRules] = useState<RuleItem[]>(() => parseRules(agent.behaviorRules));
  const [hooks, setHooks] = useState<HookItem[]>(() => parseHooks(agent.lifecycleHooks));

  const [testInput, setTestInput] = useState('');
  const [testOut, setTestOut] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);

  const role = guessRole(agent);
  const [skills, setSkills] = useState<string[]>(() => parseArr(agent.skillIds));
  const [tools, setTools] = useState<string[]>(() => parseArr(agent.toolIds));

  const { data: skillsCatalog = [] } = useQuery({
    queryKey: ['skills'],
    queryFn: () => getSkills().then(res => extractList<Record<string, unknown>>(res)),
    staleTime: 60_000,
  });
  const skillCatalogItems = useMemo<CatalogItem[]>(
    () => skillsCatalog.map(s => ({ id: String(s.name), desc: s.description ? String(s.description) : undefined, tag: s.kind ? String(s.kind) : undefined })),
    [skillsCatalog],
  );

  const { data: toolsCatalog = [] } = useQuery({
    queryKey: ['tools'],
    queryFn: () => getTools().then(res => extractList<Record<string, unknown>>(res)),
    staleTime: 60_000,
  });
  const toolCatalogItems = useMemo<CatalogItem[]>(
    () => toolsCatalog.map(t => ({ id: String(t.name), desc: t.description ? String(t.description) : undefined, tag: t.category ? String(t.category) : undefined })),
    [toolsCatalog],
  );

  const { data: rawMethods = [] } = useQuery({
    queryKey: ['lifecycle-hook-methods'],
    queryFn: () => getLifecycleHookMethods().then(res => Array.isArray(res.data) ? res.data : []),
    staleTime: 120_000,
  });
  const methodCatalogItems = useMemo<CatalogItem[]>(
    () => rawMethods.map((m: { name: string; displayName?: string; description?: string }) => ({
      id: m.name,
      desc: m.description || m.displayName,
    })),
    [rawMethods],
  );

  const addSkill = (id: string) => setSkills(s => [...s, id]);
  const removeSkill = (id: string) => setSkills(s => s.filter(x => x !== id));
  const addTool = (id: string) => setTools(t => [...t, id]);
  const removeTool = (id: string) => setTools(t => t.filter(x => x !== id));

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: number; payload: UpdateAgentRequest }) => updateAgent(id, payload),
    onSuccess: () => {
      message.success('Agent updated');
      queryClient.invalidateQueries({ queryKey: ['agents'] });
    },
    onError: () => message.error('Failed to update agent'),
  });

  const handleSavePrompts = () => {
    const payload: UpdateAgentRequest = {
      systemPrompt: promptDraft['AGENT.md'],
      soulPrompt: promptDraft['SOUL.md'],
    };
    updateMutation.mutate({ id: agent.id, payload });
    setDirty({});
  };

  const handleModeChange = (newMode: 'ask' | 'auto') => {
    setMode(newMode);
    updateMutation.mutate({ id: agent.id, payload: { executionMode: newMode } });
  };

  const addRule = () => {
    setRules(r => [...r, { kind: 'SHOULD', text: 'New rule — click to edit', group: 'custom' }]);
  };
  const removeRule = (i: number) => setRules(r => r.filter((_, j) => j !== i));
  const cycleKind = (i: number) => {
    const kinds = ['MUST', 'MUST NOT', 'SHOULD'];
    setRules(r => r.map((x, j) => j === i ? { ...x, kind: kinds[(kinds.indexOf(x.kind) + 1) % kinds.length] } : x));
  };
  const updateRuleText = (i: number, text: string) => setRules(r => r.map((x, j) => j === i ? { ...x, text } : x));

  const addHook = () => {
    setHooks(h => [...h, { event: 'PreToolUse', name: 'new-hook', type: 'skill' }]);
  };
  const removeHook = (i: number) => setHooks(h => h.filter((_, j) => j !== i));
  const updateHook = (i: number, patch: Partial<HookItem>) => setHooks(h => h.map((x, j) => j === i ? { ...x, ...patch } : x));

  const runTest = () => {
    setTesting(true);
    setTestOut('');
    const full = `▶ Calling ${agent.name} (${agent.modelId || 'default'}, mode: ${mode})\n\n[tool:grep] searching...\n→ Found relevant files\n\n[assistant]\nI've analyzed the request and here are my findings...\n\nShall I proceed with the implementation?`;
    let i = 0;
    const timer = setInterval(() => {
      i += 3 + Math.floor(Math.random() * 4);
      setTestOut(full.slice(0, i));
      if (i >= full.length) { clearInterval(timer); setTesting(false); }
    }, 22);
  };

  const tabs = [
    { id: 'overview', label: 'Overview' },
    { id: 'prompts', label: 'Prompts' },
    { id: 'rules', label: 'Rules', badge: rules.length },
    { id: 'hooks', label: 'Hooks', badge: hooks.length },
    { id: 'toolsSkills', label: 'Tools & Skills', badge: skills.length + tools.length },
    { id: 'test', label: 'Test' },
  ];

  return (
    <>
      <div className="agent-drawer-backdrop" onClick={onClose} />
      <div className="agent-drawer" role="dialog" aria-label={`${agent.name} details`}>
        <div className="agent-drawer-head">
          <div className="agent-drawer-head-row">
            <div className={`agent-mark ${role}`} style={{ width: 40, height: 40, fontSize: 18 }}>
              {initials(agent.name)}
            </div>
            <div style={{ minWidth: 0 }}>
              <h2 className="agent-drawer-title">{agent.name}</h2>
              <div className="agent-drawer-subtitle">
                {agent.modelId || 'default model'} · {mode}
              </div>
            </div>
            <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
              <div className="mode-seg-sf" title="Execution mode">
                <button className={mode === 'ask' ? 'on' : ''} onClick={() => handleModeChange('ask')}>ASK</button>
                <button className={mode === 'auto' ? 'on' : ''} onClick={() => handleModeChange('auto')}>AUTO</button>
              </div>
              <button className="agent-drawer-close" onClick={onClose} title="Close (Esc)">{CLOSE_ICON}</button>
            </div>
          </div>
        </div>

        <div className="agent-drawer-tabs">
          {tabs.map(t => (
            <button key={t.id} className={`agent-drawer-tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>
              {t.label}{t.badge !== undefined && <span className="badge">{t.badge}</span>}
            </button>
          ))}
        </div>

        <div className="agent-drawer-body">
          {tab === 'overview' && (
            <>
              {agent.description && (
                <p style={{ color: 'var(--fg-2)', fontSize: 14, lineHeight: 1.6, margin: '0 0 18px' }}>
                  {agent.description}
                </p>
              )}
              <div className="overview-grid">
                <div className="overview-card"><div className="overview-k">Model</div><div className="overview-v mono">{agent.modelId || '—'}</div></div>
                <div className="overview-card"><div className="overview-k">Mode</div><div className="overview-v mono">{mode}</div></div>
                <div className="overview-card"><div className="overview-k">Role (inferred)</div><div className="overview-v mono">{role}</div></div>
                <div className="overview-card"><div className="overview-k">ID</div><div className="overview-v mono">{agent.id}</div></div>
              </div>
              <div className="spec-block">
                <div className="spec-h"><h3>Configuration</h3></div>
                <div className="overview-grid" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
                  <div className="overview-card"><div className="overview-k">Rules</div><div className="overview-v">{rules.length}</div></div>
                  <div className="overview-card"><div className="overview-k">Hooks</div><div className="overview-v">{hooks.length}</div></div>
                  <div className="overview-card"><div className="overview-k">Skills</div><div className="overview-v">{skills.length}</div></div>
                </div>
              </div>
            </>
          )}

          {tab === 'prompts' && (
            <>
              <div className="md-files">
                {(['AGENT.md', 'SOUL.md', 'MEMORY.md'] as const).map(f => (
                  <button key={f} className={`md-file-tab ${mdFile === f ? 'on' : ''}`} onClick={() => setMdFile(f)}>
                    {f}
                    <span className={`dot ${dirty[f] ? '' : 'clean'}`} />
                  </button>
                ))}
              </div>
              {mdFile === 'MEMORY.md' ? (
                <div style={{ color: 'var(--fg-3)', fontSize: 13, padding: 20, textAlign: 'center', border: '1px dashed var(--border-1)', borderRadius: 8 }}>
                  Memory is automatically injected from the Memory system.
                </div>
              ) : (
                <>
                  <div className="prompt-block">
                    <div className="prompt-file-bar">
                      <span className={`mode-dot ${dirty[mdFile] ? 'dirty' : ''}`} />
                      <span>{mdFile} {dirty[mdFile] ? '· unsaved' : '· synced'}</span>
                      <button className="mini-btn">{COPY_ICON} copy</button>
                    </div>
                    <textarea
                      className="prompt-editor"
                      value={promptDraft[mdFile]}
                      onChange={(e) => {
                        const v = e.target.value;
                        setPromptDraft(d => ({ ...d, [mdFile]: v }));
                        setDirty(d => ({ ...d, [mdFile]: true }));
                      }}
                      spellCheck={false}
                    />
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
                    <button className="btn-ghost-sf" onClick={() => setDirty(d => ({ ...d, [mdFile]: false }))}>Revert</button>
                    <button className="btn-primary-sf" onClick={handleSavePrompts}>
                      {dirty[mdFile] ? 'Save draft' : 'Saved'}
                    </button>
                  </div>
                </>
              )}
            </>
          )}

          {tab === 'rules' && (
            <>
              <div className="spec-h">
                <h3>Behavior rules — {rules.length}</h3>
                <div className="spec-h-actions">
                  <button className="btn-ghost-sf" onClick={addRule}>{PLUS_ICON} Add rule</button>
                </div>
              </div>
              <div className="rules-hint">
                <span><span className="rule-kind-sf rule-kind-inline MUST">MUST</span> strict requirement</span>
                <span><span className="rule-kind-sf rule-kind-inline MUST-NOT">MUST NOT</span> forbidden</span>
                <span><span className="rule-kind-sf rule-kind-inline SHOULD">SHOULD</span> preferred</span>
              </div>
              {rules.length === 0 ? (
                <div className="empty-state-sf">
                  No rules yet. Click <b>Add rule</b> to create one.
                </div>
              ) : (
                (() => {
                  const groups = Array.from(new Set(rules.map(r => r.group)));
                  return groups.map(g => (
                    <div key={g} className="rule-group-block">
                      <div className="rule-group-label">
                        <span className="rule-group-btn">{g}</span>
                        <span className="rule-group-count">{rules.filter(r => r.group === g).length}</span>
                      </div>
                      <div className="rules-list-sf">
                        {rules.map((r, i) => r.group === g && (
                          <div key={i} className="rule-row-sf">
                            <button
                              className={`rule-kind-sf ${r.kind.replace(' ', '-')}`}
                              onClick={() => cycleKind(i)}
                              title="click to cycle"
                            >
                              {r.kind}
                            </button>
                            {editing === i ? (
                              <textarea
                                autoFocus
                                className="rule-edit-input-sf"
                                defaultValue={r.text}
                                onBlur={(e) => { updateRuleText(i, e.target.value.trim() || r.text); setEditing(null); }}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); (e.target as HTMLTextAreaElement).blur(); }
                                  if (e.key === 'Escape') setEditing(null);
                                }}
                              />
                            ) : (
                              <div className="rule-text-sf" onClick={() => setEditing(i)} title="click to edit">{r.text}</div>
                            )}
                            <button className="rule-del-sf" onClick={() => removeRule(i)} title="Remove">{CLOSE_ICON}</button>
                          </div>
                        ))}
                      </div>
                    </div>
                  ));
                })()
              )}
            </>
          )}

          {tab === 'hooks' && (
            <>
              <div className="spec-h">
                <h3>Lifecycle hooks — {hooks.length}</h3>
                <div className="spec-h-actions">
                  <button className="btn-ghost-sf" onClick={addHook}>{PLUS_ICON} Add hook</button>
                </div>
              </div>
              <div className="hooks-list-sf">
                <div className="hook-head-sf">
                  <span>event</span>
                  <span>handler</span>
                  <span>type</span>
                  <span />
                </div>
                {hooks.map((h, i) => {
                  const meta = HOOK_EVENT_BY_ID[h.event];
                  return (
                    <div key={i} className="hook-entry-sf">
                      <div className="hook-row-sf">
                        <HookEventPicker value={h.event} onChange={(v) => updateHook(i, { event: v })} />
                        <HookHandlerPicker
                          value={h.name}
                          type={h.type}
                          onChange={(v) => updateHook(i, { name: v })}
                          skillCatalog={skillCatalogItems}
                          methodCatalog={methodCatalogItems}
                        />
                        <HookTypePicker value={h.type} onChange={(v) => updateHook(i, { type: v })} />
                        <button className="hook-del-sf" onClick={() => removeHook(i)} title="Remove">{CLOSE_ICON}</button>
                      </div>
                      <div className="hook-config-sf">
                        <div className="hook-config-row-sf">
                          <input
                            className="hook-desc-input-sf"
                            value={h.description || ''}
                            onChange={(e) => updateHook(i, { description: e.target.value })}
                            placeholder="Description (optional)"
                          />
                        </div>
                        {meta?.abort && (
                          <label className="hook-toggle-sf">
                            <input
                              type="checkbox"
                              checked={h.abortOnError ?? false}
                              onChange={(e) => updateHook(i, { abortOnError: e.target.checked })}
                            />
                            <span>Abort on error</span>
                          </label>
                        )}
                      </div>
                    </div>
                  );
                })}
                {hooks.length === 0 && (
                  <div className="empty-state-sf" style={{ padding: '32px 20px' }}>
                    <div style={{ fontSize: 13, color: 'var(--fg-2)', marginBottom: 12 }}>
                      Lifecycle hooks let you run code at key points during an agent session — before/after tool calls, on user input, at session start/end.
                    </div>
                    <button className="btn-ghost-sf" onClick={addHook} style={{ margin: '0 auto' }}>{PLUS_ICON} Add your first hook</button>
                  </div>
                )}
              </div>
            </>
          )}

          {tab === 'toolsSkills' && (
            <>
              <div className="spec-block">
                <div className="spec-h">
                  <h3>Skills <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-4)' }}>— {skills.length}</span></h3>
                </div>
                <div className="chip-row-sf">
                  {skills.map(s => (
                    <span key={s} className="chip-pill-sf skill removable">
                      {s}
                      <button className="chip-pill-x-sf" onClick={() => removeSkill(s)} title="Remove">{CLOSE_ICON}</button>
                    </span>
                  ))}
                  <AttachPicker kind="skill" catalog={skillCatalogItems} attached={skills} onPick={addSkill} />
                </div>
              </div>
              <div className="spec-block">
                <div className="spec-h">
                  <h3>Tools <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-4)' }}>— {tools.length === 0 ? 'all' : tools.length}</span></h3>
                </div>
                <div className="chip-row-sf">
                  {tools.length === 0 && <span style={{ color: 'var(--fg-3)', fontSize: 13 }}>No restriction — agent can call any tool.</span>}
                  {tools.map(t => (
                    <span key={t} className="chip-pill-sf tool removable">
                      {t}
                      <button className="chip-pill-x-sf" onClick={() => removeTool(t)} title="Remove">{CLOSE_ICON}</button>
                    </span>
                  ))}
                  <AttachPicker kind="tool" catalog={toolCatalogItems} attached={tools} onPick={addTool} />
                </div>
              </div>
            </>
          )}

          {tab === 'test' && (
            <div className="test-panel-sf">
              <div style={{ fontSize: 13, color: 'var(--fg-2)', marginBottom: 8 }}>
                Quick-test this agent with a one-shot prompt. No session state, no hooks, no peers.
              </div>
              <textarea value={testInput} onChange={e => setTestInput(e.target.value)} placeholder="Ask this agent something…" />
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 10 }}>
                <button className="btn-primary-sf" onClick={runTest} disabled={testing}>
                  {PLAY_ICON} {testing ? 'Running…' : 'Run test'}
                </button>
              </div>
              {(testOut !== null || testing) && (
                <div className="test-out-sf">
                  <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontFamily: 'inherit' }}>{testOut}</pre>
                  {testing && <span className="typing" />}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </>
  );
};

export default AgentDrawer;
