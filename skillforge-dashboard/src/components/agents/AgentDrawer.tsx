import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { InputNumber, Select, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  updateAgent,
  getTools,
  getSkills,
  extractList,
  type UpdateAgentRequest,
  type BehaviorRuleConfig,
  type CustomBehaviorRule,
  type CustomRuleSeverity,
} from '../../api';
import type { AgentDto, ThinkingMode, ReasoningEffort } from '../../api/schemas';
import { initials, guessRole } from './AgentCard';
import BehaviorRulesEditor from '../BehaviorRulesEditor';
import { useBehaviorRules } from '../../hooks/useBehaviorRules';
import LifecycleHooksPanel from '../LifecycleHooksPanel';
import { countHookEntries } from '../../constants/lifecycleHooks';
import { useLlmModels } from '../../hooks/useLlmModels';

const ROLE_OPTIONS = [
  { label: 'leader', value: 'leader' },
  { label: 'reviewer', value: 'reviewer' },
  { label: 'judge', value: 'judge' },
  { label: 'writer', value: 'writer' },
];

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

function parseArr(raw: unknown): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw.map(String);
  if (typeof raw === 'string') {
    try { const a = JSON.parse(raw); return Array.isArray(a) ? a.map(String) : []; } catch { return []; }
  }
  return [];
}

function parseBehaviorRulesConfig(raw: unknown): BehaviorRuleConfig | null {
  if (!raw) return null;
  try {
    const cfg = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!cfg || typeof cfg !== 'object') return null;
    const builtin = (cfg as Record<string, unknown>).builtinRuleIds;
    const custom = (cfg as Record<string, unknown>).customRules;
    if (!Array.isArray(builtin) || !Array.isArray(custom)) return null;
    const builtinIds = builtin.filter((x): x is string => typeof x === 'string');
    const customRules = custom.map(normalizeCustomRule).filter((x): x is CustomBehaviorRule => x !== null);
    return { builtinRuleIds: builtinIds, customRules };
  } catch { return null; }
}

function normalizeCustomRule(raw: unknown): CustomBehaviorRule | null {
  if (typeof raw === 'string') {
    const text = raw.trim();
    return text ? { severity: 'SHOULD', text } : null;
  }
  if (!raw || typeof raw !== 'object') return null;
  const obj = raw as Record<string, unknown>;
  const text = typeof obj.text === 'string' ? obj.text.trim() : '';
  if (!text) return null;
  const rawSeverity = typeof obj.severity === 'string' ? obj.severity.toUpperCase() : 'SHOULD';
  const severity: CustomRuleSeverity =
    rawSeverity === 'MUST' || rawSeverity === 'MAY' ? rawSeverity : 'SHOULD';
  return { severity, text };
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

interface CatalogItem { id: string; desc?: string; tag?: string }

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
  const { options: modelOptions } = useLlmModels();
  const [tab, setTab] = useState('overview');
  const [mdFile, setMdFile] = useState<'AGENT.md' | 'SOUL.md' | 'MEMORY.md'>('AGENT.md');
  const [dirty, setDirty] = useState<Record<string, boolean>>({});
  const [promptDraft, setPromptDraft] = useState({
    'AGENT.md': agent.systemPrompt || '',
    'SOUL.md': agent.soulPrompt || '',
    'MEMORY.md': '',
  });
  const [mode, setMode] = useState(agent.executionMode || 'ask');

  // Rules — use BehaviorRulesEditor via useBehaviorRules hook
  const initialRulesConfig = useMemo(
    () => parseBehaviorRulesConfig(agent.behaviorRules),
    [agent.behaviorRules],
  );
  const rulesCtl = useBehaviorRules(initialRulesConfig, mode);
  // Latch dirty baseline only after useBehaviorRules finishes loading. When
  // initialRulesConfig=null, the hook auto-applies a default preset once builtin
  // rules load — comparing against an empty baseline would always show dirty.
  const rulesBaselineRef = useRef<string | null>(null);
  useEffect(() => {
    if (rulesBaselineRef.current === null && !rulesCtl.isLoading) {
      rulesBaselineRef.current = JSON.stringify(rulesCtl.config);
    }
  }, [rulesCtl.isLoading, rulesCtl.config]);
  const rulesDirty =
    rulesBaselineRef.current !== null &&
    rulesBaselineRef.current !== JSON.stringify(rulesCtl.config);

  const [testInput, setTestInput] = useState('');
  const [testOut, setTestOut] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);

  const inferredRole = guessRole(agent);
  const [roleDraft, setRoleDraft] = useState<string>((agent.role || '').toString());
  const initialSkills = useMemo(() => parseArr(agent.skillIds), [agent.skillIds]);
  const initialTools = useMemo(() => parseArr(agent.toolIds), [agent.toolIds]);
  const [skills, setSkills] = useState<string[]>(initialSkills);
  const [tools, setTools] = useState<string[]>(initialTools);
  const toolsSkillsDirty =
    JSON.stringify(skills) !== JSON.stringify(initialSkills) ||
    JSON.stringify(tools) !== JSON.stringify(initialTools);
  const hooksCount = useMemo(() => countHookEntries(agent.lifecycleHooks), [agent.lifecycleHooks]);

  // Overview — model + maxLoops + thinking edit
  const agentWithExtras = agent as AgentDto & { maxLoops?: number | null };
  const [modelIdDraft, setModelIdDraft] = useState<string>(agent.modelId || '');
  const [publicDraft, setPublicDraft] = useState<boolean>(agent.public ?? false);
  const [maxLoopsDraft, setMaxLoopsDraft] = useState<number | null>(
    typeof agentWithExtras.maxLoops === 'number' ? agentWithExtras.maxLoops : null,
  );
  const initialMaxLoops =
    typeof agentWithExtras.maxLoops === 'number' ? agentWithExtras.maxLoops : null;
  // Thinking Mode v1. Server default is 'auto' — treat null/undefined as such
  // so old agents persisted before V23 migration round-trip cleanly. AgentSchema
  // narrows these to the enum unions, so no type assertion is needed.
  const initialThinkingMode: ThinkingMode = agent.thinkingMode ?? 'auto';
  const initialReasoningEffort: ReasoningEffort | null = agent.reasoningEffort ?? null;
  const [thinkingModeDraft, setThinkingModeDraft] = useState<ThinkingMode>(initialThinkingMode);
  const [reasoningEffortDraft, setReasoningEffortDraft] = useState<ReasoningEffort | null>(
    initialReasoningEffort,
  );
  useEffect(() => {
    setModelIdDraft(agent.modelId || '');
    setPublicDraft(agent.public ?? false);
    setRoleDraft((agent.role || '').toString());
    setMaxLoopsDraft(typeof agentWithExtras.maxLoops === 'number' ? agentWithExtras.maxLoops : null);
    setThinkingModeDraft(agent.thinkingMode ?? 'auto');
    setReasoningEffortDraft(agent.reasoningEffort ?? null);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.id]);

  // Derive capability flags from the currently selected model. When the model
  // has no capability entry (fallback table missing it) we treat both flags as
  // `false` — this keeps the Thinking/Effort controls safely disabled rather
  // than leaking a non-functional UI affordance.
  const selectedModelMeta = useMemo(
    () => modelOptions.find((o) => o.id === modelIdDraft) ?? null,
    [modelOptions, modelIdDraft],
  );
  const supportsThinking = selectedModelMeta?.supportsThinking ?? false;
  const supportsEffort = selectedModelMeta?.supportsReasoningEffort ?? false;

  // Exclude clear-only maxLoops transitions: UpdateAgentRequest.maxLoops is
  // `number | undefined`, so a null draft can't be explicitly sent to clear the
  // server value. Without this guard, Save would fire a false success toast.
  const overviewDirty =
    (agent.modelId || '') !== modelIdDraft ||
    (agent.public ?? false) !== publicDraft ||
    ((agent.role || '') !== roleDraft) ||
    (maxLoopsDraft !== null && initialMaxLoops !== maxLoopsDraft) ||
    initialThinkingMode !== thinkingModeDraft ||
    initialReasoningEffort !== reasoningEffortDraft;

  const { data: skillsCatalog = [] } = useQuery({
    queryKey: ['skills'],
    queryFn: () => getSkills().then(res => extractList<Record<string, unknown>>(res)),
    staleTime: 60_000,
  });
  const skillCatalogItems = useMemo<CatalogItem[]>(
    () => skillsCatalog.map((s) => {
      const requiredTools = s.requiredTools ? String(s.requiredTools) : '';
      const desc = [
        s.description ? String(s.description) : '',
        requiredTools ? `tools: ${requiredTools}` : '',
      ].filter(Boolean).join(' | ');
      return {
        id: String(s.name),
        desc: desc || undefined,
        tag: s.kind ? String(s.kind) : undefined,
      };
    }),
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
  const toolDescMap = useMemo(() => {
    const m = new Map<string, string>();
    toolsCatalog.forEach((t) => {
      const name = String(t.name || '');
      const desc = t.description ? String(t.description) : '';
      if (name) m.set(name, desc);
    });
    return m;
  }, [toolsCatalog]);
  const skillCatalogItemsWithToolDetails = useMemo<CatalogItem[]>(
    () => skillCatalogItems.map((item) => {
      const raw = item.desc || '';
      const marker = 'tools: ';
      const idx = raw.indexOf(marker);
      if (idx < 0) return item;
      const toolsPart = raw.slice(idx + marker.length).split('|')[0].trim();
      const toolIds = toolsPart.split(',').map((x) => x.trim()).filter(Boolean);
      const toolDetails = toolIds
        .map((toolId) => {
          const desc = toolDescMap.get(toolId);
          return desc ? `${toolId}: ${desc}` : toolId;
        })
        .join('; ');
      if (!toolDetails) return item;
      const mergedDesc = `${raw} | tool details: ${toolDetails}`;
      return { ...item, desc: mergedDesc };
    }),
    [skillCatalogItems, toolDescMap],
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
    updateMutation.mutate({ id: agent.id, payload: {
      systemPrompt: promptDraft['AGENT.md'],
      soulPrompt: promptDraft['SOUL.md'],
    }});
    setDirty({});
  };

  const handleModeChange = (newMode: 'ask' | 'auto') => {
    setMode(newMode);
    updateMutation.mutate({ id: agent.id, payload: { executionMode: newMode } });
  };

  const handleSaveRules = () => {
    rulesBaselineRef.current = null; // re-latch baseline after server re-fetch
    updateMutation.mutate({
      id: agent.id,
      payload: { behaviorRules: JSON.stringify(rulesCtl.config) },
    });
  };

  const handleSaveToolsSkills = () => {
    updateMutation.mutate({
      id: agent.id,
      payload: {
        skillIds: JSON.stringify(skills),
        toolIds: JSON.stringify(tools),
      },
    });
  };

  const handleSaveOverview = () => {
    const partial: UpdateAgentRequest = {
      modelId: modelIdDraft || undefined,
      role: roleDraft,
      public: publicDraft,
      thinkingMode: thinkingModeDraft,
    };
    if (maxLoopsDraft !== null && maxLoopsDraft !== undefined) {
      partial.maxLoops = maxLoopsDraft;
    }
    // Always send `reasoningEffort` when present (even if the selected model no
    // longer supports it) — plan §5.3 preserves draft on model switch; server
    // tolerates the field and simply won't forward it for unsupported families.
    if (reasoningEffortDraft !== null) {
      partial.reasoningEffort = reasoningEffortDraft;
    }
    updateMutation.mutate({ id: agent.id, payload: partial });
  };

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

  const rulesBadge = rulesCtl.config.builtinRuleIds.length + rulesCtl.config.customRules.length;
  const tabs = [
    { id: 'overview', label: 'Overview' },
    { id: 'prompts', label: 'Prompts' },
    { id: 'rules', label: 'Rules', badge: rulesBadge },
    { id: 'hooks', label: 'Hooks', badge: hooksCount },
    { id: 'toolsSkills', label: 'Tools & Skills', badge: skills.length + tools.length },
    { id: 'test', label: 'Test' },
  ];

  return (
    <>
      <div className="agent-drawer-backdrop" onClick={onClose} />
      <div className="agent-drawer" role="dialog" aria-label={`${agent.name} details`}>
        <div className="agent-drawer-head">
          <div className="agent-drawer-head-row">
            <div className={`agent-mark ${inferredRole}`} style={{ width: 40, height: 40, fontSize: 18 }}>
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
                <div className="overview-card">
                  <div className="overview-k">Model</div>
                  <Select
                    size="small"
                    value={modelIdDraft || undefined}
                    options={modelOptions.map((o) => ({ label: o.label, value: o.id }))}
                    onChange={(v) => setModelIdDraft(v)}
                    placeholder="default"
                    style={{ width: '100%', marginTop: 4 }}
                    showSearch
                    optionFilterProp="label"
                  />
                </div>
                <div className="overview-card">
                  <div className="overview-k">Max Loops</div>
                  <InputNumber
                    size="small"
                    min={1}
                    max={200}
                    value={maxLoopsDraft ?? undefined}
                    onChange={(v) => setMaxLoopsDraft(typeof v === 'number' ? v : null)}
                    placeholder="default"  /* clearing reverts to default; cannot save explicit null via API */
                    style={{ width: '100%', marginTop: 4 }}
                  />
                </div>
                <div className="overview-card">
                  <div className="overview-k">Thinking</div>
                  <Select<ThinkingMode>
                    size="small"
                    value={thinkingModeDraft}
                    options={[
                      { label: 'Auto (provider default)', value: 'auto' },
                      { label: 'Enabled',  value: 'enabled',  disabled: !supportsThinking },
                      { label: 'Disabled', value: 'disabled', disabled: !supportsThinking },
                    ]}
                    onChange={(v) => setThinkingModeDraft(v)}
                    style={{ width: '100%', marginTop: 4 }}
                    disabled={!supportsThinking}
                    title={
                      supportsThinking
                        ? undefined
                        : 'Selected model does not expose a thinking toggle; value is retained but inactive.'
                    }
                    data-testid="thinking-mode-select"
                  />
                </div>
                <div className="overview-card">
                  <div className="overview-k">Reasoning effort</div>
                  <Select<ReasoningEffort | undefined>
                    size="small"
                    value={reasoningEffortDraft ?? undefined}
                    allowClear
                    options={[
                      { label: 'low',    value: 'low' },
                      { label: 'medium', value: 'medium' },
                      { label: 'high',   value: 'high' },
                      { label: 'max',    value: 'max' },
                    ]}
                    onChange={(v) => setReasoningEffortDraft(v ?? null)}
                    placeholder="default"
                    style={{ width: '100%', marginTop: 4 }}
                    disabled={!supportsEffort}
                    title={
                      supportsEffort
                        ? 'low/medium may be remapped by the provider.'
                        : 'Selected model does not support reasoning effort; value is retained but inactive.'
                    }
                    data-testid="reasoning-effort-select"
                  />
                </div>
                <div className="overview-card"><div className="overview-k">Mode</div><div className="overview-v mono">{mode}</div></div>
                <div className="overview-card">
                  <div className="overview-k">Visibility</div>
                  <Select
                    size="small"
                    value={publicDraft ? 'public' : 'private'}
                    options={[
                      { label: 'private', value: 'private' },
                      { label: 'public', value: 'public' },
                    ]}
                    onChange={(v) => setPublicDraft(v === 'public')}
                    style={{ width: '100%', marginTop: 4 }}
                  />
                </div>
                <div className="overview-card">
                  <div className="overview-k">Role</div>
                  <Select
                    size="small"
                    value={roleDraft || undefined}
                    options={ROLE_OPTIONS}
                    onChange={(v) => setRoleDraft(v ?? '')}
                    placeholder={inferredRole}
                    style={{ width: '100%', marginTop: 4 }}
                    allowClear
                    showSearch
                    optionFilterProp="label"
                  />
                </div>
                <div className="overview-card"><div className="overview-k">ID</div><div className="overview-v mono">{agent.id}</div></div>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
                <button
                  className="btn-primary-sf"
                  onClick={handleSaveOverview}
                  disabled={!overviewDirty || updateMutation.isPending}
                >
                  {overviewDirty ? 'Save' : 'Saved'}
                </button>
              </div>
              <div className="spec-block">
                <div className="spec-h"><h3>Configuration</h3></div>
                <div className="overview-grid" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
                  <div className="overview-card"><div className="overview-k">Rules</div><div className="overview-v">{rulesBadge}</div></div>
                  <div className="overview-card"><div className="overview-k">Hooks</div><div className="overview-v">{hooksCount}</div></div>
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
              <BehaviorRulesEditor
                groupedRules={rulesCtl.groupedRules}
                templateId={rulesCtl.templateId}
                customRules={rulesCtl.config.customRules}
                isLoading={rulesCtl.isLoading}
                onApplyTemplate={rulesCtl.applyTemplate}
                onToggleRule={rulesCtl.toggleRule}
                onAddCustomRule={rulesCtl.addCustomRule}
                onRemoveCustomRule={rulesCtl.removeCustomRule}
                onUpdateCustomRule={rulesCtl.updateCustomRule}
              />
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
                <button
                  className="btn-primary-sf"
                  onClick={handleSaveRules}
                  disabled={!rulesDirty || updateMutation.isPending}
                >
                  {rulesDirty ? 'Save' : 'Saved'}
                </button>
              </div>
            </>
          )}

          {tab === 'hooks' && (
            <LifecycleHooksPanel
              agentId={agent.id}
              fallbackRawJson={agent.lifecycleHooks}
              skills={skillsCatalog.map((s) => ({
                name: String(s.name),
                description: s.description ? String(s.description) : undefined,
              }))}
            />
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
                  <AttachPicker kind="skill" catalog={skillCatalogItemsWithToolDetails} attached={skills} onPick={addSkill} />
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
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
                <button
                  className="btn-primary-sf"
                  onClick={handleSaveToolsSkills}
                  disabled={!toolsSkillsDirty || updateMutation.isPending}
                >
                  {toolsSkillsDirty ? 'Save' : 'Saved'}
                </button>
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
