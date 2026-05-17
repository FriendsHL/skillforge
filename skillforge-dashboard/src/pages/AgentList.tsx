import React, { useEffect, useMemo, useState } from 'react';
import { Form, Input, InputNumber, Select, Modal, Tabs, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { getAgents, createAgent, getTools, getSkills, extractList, type CreateAgentRequest } from '../api';
import { listSchedules } from '../api/schedules';
import { getSystemAgentMonitor } from '../api/systemAgents';
import { AgentSchema, safeParseList, type AgentDto } from '../api/schemas';
import AgentCard, { initials, parseCount, guessRole } from '../components/agents/AgentCard';
import AgentDrawer from '../components/agents/AgentDrawer';
import SystemAgentMonitorCard from '../components/agents/SystemAgentMonitorCard';
import '../components/agents/agents.css';
import { useAuth } from '../contexts/AuthContext';
import { useLlmModels } from '../hooks/useLlmModels';
import { useLocalStorageString } from '../hooks/useLocalStorageString';

const { TextArea } = Input;

const GRID_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4">
    <rect x="2" y="2" width="5" height="5" rx="1" /><rect x="9" y="2" width="5" height="5" rx="1" />
    <rect x="2" y="9" width="5" height="5" rx="1" /><rect x="9" y="9" width="5" height="5" rx="1" />
  </svg>
);
const ROWS_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4">
    <line x1="3" y1="4" x2="13" y2="4" /><line x1="3" y1="8" x2="13" y2="8" /><line x1="3" y1="12" x2="13" y2="12" />
  </svg>
);
const PLUS_ICON = (
  <svg width={13} height={13} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
    <path d="M8 3v10M3 8h10" />
  </svg>
);

const ROLE_OPTIONS = [
  { label: 'leader', value: 'leader' },
  { label: 'reviewer', value: 'reviewer' },
  { label: 'judge', value: 'judge' },
  { label: 'writer', value: 'writer' },
];

interface FilterState {
  model: string | null;
  role: string | null;
}

function AgentsTable({ rows, onOpen }: { rows: AgentDto[]; onOpen: (a: AgentDto) => void }) {
  return (
    <table className="agents-table">
      <thead>
        <tr>
          <th>Agent</th>
          <th>Model</th>
          <th>Mode</th>
          <th>Tools</th>
          <th>Skills</th>
          <th>ID</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(a => {
          const role = guessRole(a);
          return (
            <tr key={a.id} onClick={() => onOpen(a)}>
              <td>
                <div className="t-name">
                  <div className={`agent-mark ${role}`} style={{ width: 24, height: 24, fontSize: 11 }}>
                    {initials(a.name)}
                  </div>
                  <div className="t-name-text">
                    <b>{a.name}</b>
                    <span>{a.description ? a.description.slice(0, 60) : '—'}</span>
                  </div>
                </div>
              </td>
              <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-2)' }}>{a.modelId || '—'}</td>
              <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{a.executionMode || 'ask'}</td>
              <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{parseCount(a.toolIds) === 0 ? 'all' : parseCount(a.toolIds)}</td>
              <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{parseCount(a.skillIds)}</td>
              <td style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-4)' }}>#{a.id}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

const AgentList: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const { options: modelOptionsData } = useLlmModels();
  const [view, setView] = useState<'grid' | 'table'>('grid');
  const [q, setQ] = useState('');
  const [filter, setFilter] = useState<FilterState>({ model: null, role: null });
  const [openAgent, setOpenAgent] = useState<AgentDto | null>(null);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [form] = Form.useForm();
  // MULTIMODAL-MVP — deep link from Chat tooltip ("打开 agent 配置") lands here
  // with `?openAgentId=<id>` and we auto-open the drawer for that agent so
  // the user doesn't have to hunt for the row. The `tab` param is read by
  // AgentDrawer if it ever wires the initial-tab argument; ignored here.
  const [searchParams, setSearchParams] = useSearchParams();
  const openAgentIdParam = searchParams.get('openAgentId');

  // SYSTEM-AGENT-TYPING Phase 2 UX refactor (2026-05-18) — replace the
  // "Show system agents" Switch toggle with top-level Tabs that segment User
  // vs System agents. Persisted in localStorage so the choice survives reload.
  // Default 'user': most operators work with their own (user) agents day-to-day
  // and the 5 cron-managed system agents are surfaced only when explicitly
  // selected via the "System Agents" tab.
  const TAB_KEYS = ['user', 'system'] as const;
  type AgentTabKey = (typeof TAB_KEYS)[number];
  const [activeTab, setActiveTab] = useLocalStorageString<AgentTabKey>(
    'agentlist.active_tab',
    'user',
    TAB_KEYS,
  );
  const showSystemAgents = activeTab === 'system';
  // BE param: 'user' tab → fetch only user agents; 'system' tab → fetch only
  // system agents. (The previous toggle used 'all' for system; narrowing to
  // 'system' here keeps the query payload minimal and matches the visible UI.)
  const agentTypeFilter: 'user' | 'system' = activeTab;

  const { data: agents = [], isLoading: loading, isError: agentsError } = useQuery({
    // queryKey includes the filter so toggling re-fetches instead of
    // serving stale cache; distinct from other pages' `['agents', 'all']`.
    queryKey: ['agents', agentTypeFilter],
    queryFn: () =>
      getAgents(agentTypeFilter).then((res) =>
        safeParseList(AgentSchema, extractList<Record<string, unknown>>(res)),
      ),
  });

  // SYSTEM-AGENT-TYPING Phase 2.2 — pull monitor rows + scheduled tasks for
  // the inline SystemAgentMonitorCard. We only fetch when the toggle is on so
  // pages that hide system agents don't pay the extra round-trip. `enabled`
  // also short-circuits when there are no system agents in the list yet.
  const { data: monitorRows = [] } = useQuery({
    queryKey: ['system-agents', 'monitor'],
    queryFn: () => getSystemAgentMonitor().then((r) => r.data ?? []),
    enabled: showSystemAgents,
    staleTime: 30_000,
  });
  const { data: schedulesRaw = [] } = useQuery({
    queryKey: ['schedules', 'for-system-agents'],
    // listSchedules returns every schedule visible to this user; we filter to
    // agent_id → taskId locally so we don't depend on a per-agent BE endpoint.
    queryFn: () => listSchedules(userId).then((r) => r.data ?? []),
    enabled: showSystemAgents,
    staleTime: 30_000,
  });
  const scheduledTaskIdByAgentId = useMemo<Record<number, number>>(() => {
    const m: Record<number, number> = {};
    for (const s of schedulesRaw) {
      // If multiple schedules exist for the same agent (rare), keep the
      // first — running any one of them produces equivalent output. The UI
      // surface is "Run Manually" which intentionally triggers one schedule.
      if (s.agentId && m[s.agentId] == null) m[s.agentId] = s.id;
    }
    return m;
  }, [schedulesRaw]);
  const monitorByAgentId = useMemo(() => {
    const m = new Map<number, (typeof monitorRows)[number]>();
    monitorRows.forEach((row) => m.set(row.agentId, row));
    return m;
  }, [monitorRows]);

  useEffect(() => {
    if (agentsError) message.error('Failed to load agents');
  }, [agentsError]);

  // Auto-open the drawer when arriving with `?openAgentId=…`. Strip the
  // param after consumption so navigating away + back doesn't keep popping
  // the drawer; honour the deep link only on the first match.
  useEffect(() => {
    if (!openAgentIdParam || agents.length === 0) return;
    const target = agents.find((a: AgentDto) => String(a.id) === openAgentIdParam);
    if (!target) return;
    setOpenAgent(target);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete('openAgentId');
        next.delete('tab');
        return next;
      },
      { replace: true },
    );
  }, [openAgentIdParam, agents, setSearchParams]);

  const { data: tools = [] } = useQuery({
    queryKey: ['tools'],
    queryFn: () => getTools().then((res) => extractList<Record<string, unknown>>(res)),
  });
  const toolOptions = useMemo(
    () => tools.map((t: Record<string, unknown>) => ({
      label: t.description ? `${t.name} — ${t.description}` : String(t.name),
      value: String(t.name),
    })),
    [tools],
  );

  const { data: skills = [] } = useQuery({
    queryKey: ['skills'],
    queryFn: () => getSkills().then((res) => extractList<Record<string, unknown>>(res)),
  });
  const skillOptions = useMemo(
    () => {
      const toolDescMap = new Map<string, string>();
      tools.forEach((t: Record<string, unknown>) => {
        const name = String(t.name || '');
        const desc = t.description ? String(t.description) : '';
        if (name) toolDescMap.set(name, desc);
      });
      return skills.map((s: Record<string, unknown>) => {
        const requiredTools = s.requiredTools ? String(s.requiredTools) : '';
        const toolDetails = requiredTools
          .split(',')
          .map((x) => x.trim())
          .filter(Boolean)
          .map((toolId) => {
            const desc = toolDescMap.get(toolId);
            return desc ? `${toolId}: ${desc}` : toolId;
          })
          .join('; ');
        return {
          label: [
            s.description ? `${s.name} — ${s.description}` : String(s.name),
            requiredTools ? `[tools: ${requiredTools}]` : '',
            toolDetails ? `[tool details: ${toolDetails}]` : '',
          ].filter(Boolean).join(' '),
          value: String(s.name),
        };
      });
    },
    [skills, tools],
  );

  const createMutation = useMutation({
    mutationFn: (payload: CreateAgentRequest) => createAgent(payload),
    onSuccess: () => {
      message.success('Agent created');
      queryClient.invalidateQueries({ queryKey: ['agents'] });
      setCreateModalOpen(false);
      form.resetFields();
    },
  });

  const rows = useMemo(() => {
    return agents.filter((a: AgentDto) => {
      if (filter.model && a.modelId !== filter.model) return false;
      if (filter.role && guessRole(a) !== filter.role) return false;
      if (q) {
        const s = q.toLowerCase();
        const hay = ((a.name || '') + ' ' + (a.description || '') + ' ' + (a.modelId || '')).toLowerCase();
        if (!hay.includes(s)) return false;
      }
      return true;
    });
  }, [q, filter, agents]);

  const models = useMemo(() => {
    const map: Record<string, number> = {};
    agents.forEach((a: AgentDto) => {
      const m = a.modelId || 'default';
      map[m] = (map[m] || 0) + 1;
    });
    return Object.entries(map).map(([id, count]) => ({ id, count }));
  }, [agents]);

  const roles = useMemo(() => {
    const map: Record<string, number> = {};
    agents.forEach((a: AgentDto) => {
      const r = guessRole(a);
      map[r] = (map[r] || 0) + 1;
    });
    return Object.entries(map).map(([id, count]) => ({ id, count }));
  }, [agents]);

  const toggle = (k: keyof FilterState, v: string) => setFilter(f => ({ ...f, [k]: f[k] === v ? null : v }));

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      const { visibility, ...rest } = values;
      const payload: CreateAgentRequest = {
        ...rest,
        role: values.role || undefined,
        public: visibility === 'public',
        skillIds: JSON.stringify(values.skillIds ?? []),
        toolIds: JSON.stringify(values.toolIds ?? []),
      };
      await createMutation.mutateAsync(payload);
    } catch (e: unknown) {
      if (typeof e === 'object' && e !== null && 'errorFields' in e) return;
      const detail = e instanceof Error ? e.message : 'unknown';
      message.error(`Save failed: ${detail}`);
    }
  };

  return (
    <div className="agents-view">
      <aside className="agents-filters">
        <div className="agents-filters-h" style={{ marginTop: 0 }}>Search</div>
        <input className="agents-search" placeholder="name, model, description…" value={q} onChange={e => setQ(e.target.value)} />

        {roles.length > 0 && (
          <>
            <div className="agents-filters-h">Role</div>
            {roles.map(r => (
              <button key={r.id} className={`filter-item ${filter.role === r.id ? 'on' : ''}`} onClick={() => toggle('role', r.id)}>
                <div className={`agent-mark ${r.id}`} style={{ width: 14, height: 14, fontSize: 8, borderRadius: 3 }} />
                {r.id}
                <span className="filter-item-count">{r.count}</span>
              </button>
            ))}
          </>
        )}

        {models.length > 0 && (
          <>
            <div className="agents-filters-h">Model</div>
            {models.map(m => (
              <button key={m.id} className={`filter-item ${filter.model === m.id ? 'on' : ''}`} onClick={() => toggle('model', m.id)}>
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-3)' }}>{m.id}</span>
                <span className="filter-item-count">{m.count}</span>
              </button>
            ))}
          </>
        )}
      </aside>

      <section className="agents-main">
        <header className="agents-head">
          <div>
            <h1 className="agents-head-title">Agents</h1>
            <p className="agents-head-sub">{rows.length} of {agents.length} shown</p>
          </div>
          <div className="agents-head-actions">
            <div className="view-seg">
              <button className={view === 'grid' ? 'on' : ''} onClick={() => setView('grid')}>{GRID_ICON} Grid</button>
              <button className={view === 'table' ? 'on' : ''} onClick={() => setView('table')}>{ROWS_ICON} Table</button>
            </div>
            <button className="btn-primary-sf" onClick={() => setCreateModalOpen(true)}>{PLUS_ICON} New agent</button>
          </div>
        </header>

        {/*
          SYSTEM-AGENT-TYPING Phase 2 UX refactor — top-level Tabs replacing the
          Switch toggle. Items are intentionally declarative (`items=`) per
          AntD docs; the deprecated <TabPane> form is avoided. The activeKey
          + onChange wire-up is persisted via useLocalStorageString so the
          choice survives navigation + reload.
        */}
        <Tabs
          activeKey={activeTab}
          onChange={(k) => setActiveTab(k as AgentTabKey)}
          items={[
            { key: 'user', label: 'User Agents' },
            { key: 'system', label: 'System Agents' },
          ]}
          // data-testid surfaces the Tabs root so tests can scope queries.
          // AntD renders tab buttons with role="tab" + aria-selected, which
          // is what we assert against (not on the wrapper itself).
          data-testid="agent-type-tabs"
          style={{ marginBottom: 8 }}
        />

        <div className="agents-body">
          {loading && (
            <div style={{ textAlign: 'center', color: 'var(--fg-3)', padding: '60px 0', fontSize: 14 }}>
              Loading agents…
            </div>
          )}

          {!loading && rows.length === 0 && (
            <div style={{ textAlign: 'center', color: 'var(--fg-3)', padding: '60px 0', fontSize: 14 }}>
              {agents.length === 0 ? (
                <>
                  No agents yet.
                  <div>
                    <button className="btn-primary-sf" style={{ marginTop: 12 }} onClick={() => setCreateModalOpen(true)}>
                      {PLUS_ICON} Create your first agent
                    </button>
                  </div>
                </>
              ) : (
                <>
                  No agents match these filters.
                  <div>
                    <button className="btn-ghost-sf" style={{ marginTop: 12 }} onClick={() => { setFilter({ model: null, role: null }); setQ(''); }}>
                      Clear filters
                    </button>
                  </div>
                </>
              )}
            </div>
          )}

          {view === 'grid' && !loading && rows.length > 0 && (
            <div className="agents-grid">
              {rows.map((a: AgentDto) => {
                const monitorRow = a.agentType === 'system' ? monitorByAgentId.get(a.id) : undefined;
                const footerSlot = monitorRow ? (
                  <SystemAgentMonitorCard
                    data={monitorRow}
                    scheduledTaskId={scheduledTaskIdByAgentId[a.id] ?? null}
                  />
                ) : undefined;
                return (
                  <AgentCard
                    key={a.id}
                    agent={a}
                    onOpen={setOpenAgent}
                    footerSlot={footerSlot}
                  />
                );
              })}
            </div>
          )}

          {view === 'table' && !loading && rows.length > 0 && (
            <AgentsTable rows={rows} onOpen={setOpenAgent} />
          )}
        </div>
      </section>

      {openAgent && <AgentDrawer agent={openAgent} onClose={() => setOpenAgent(null)} />}

      <Modal
        title="Create Agent"
        open={createModalOpen}
        onOk={handleCreate}
        onCancel={() => setCreateModalOpen(false)}
        width={680}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="Name" rules={[{ required: true, message: 'Please enter agent name' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <TextArea rows={2} />
          </Form.Item>
          <Form.Item name="role" label="Role">
            <Select
              options={ROLE_OPTIONS}
              placeholder="Select role (optional)"
              allowClear
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="modelId" label="Model" rules={[{ required: true, message: 'Please select a model' }]}>
            <Select options={modelOptionsData.map((o) => ({ label: o.label, value: o.id }))} placeholder="Select model" showSearch optionFilterProp="label" />
          </Form.Item>
          <Form.Item name="executionMode" label="Execution Mode" initialValue="ask">
            <Select
              options={[
                { label: 'ask — confirms before acting', value: 'ask' },
                { label: 'auto — autonomous execution', value: 'auto' },
              ]}
            />
          </Form.Item>
          <Form.Item name="visibility" label="Visibility" initialValue="private">
            <Select
              options={[
                { label: 'private — visible only to same owner', value: 'private' },
                { label: 'public — discoverable and callable by other agents', value: 'public' },
              ]}
            />
          </Form.Item>
          <Form.Item name="maxLoops" label="Max Loops" tooltip="Max loop iterations (default: 25, max: 200)">
            <InputNumber min={1} max={200} placeholder="25" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="Prompts">
            <Tabs destroyInactiveTabPane={false} items={[
              {
                key: 'agent',
                label: 'AGENT.md',
                children: (
                  <Form.Item name="systemPrompt" noStyle>
                    <TextArea rows={8} placeholder="# Agent Core Instructions" />
                  </Form.Item>
                ),
              },
              {
                key: 'soul',
                label: 'SOUL.md',
                children: (
                  <Form.Item name="soulPrompt" noStyle>
                    <TextArea rows={8} placeholder="# Persona & Tone" />
                  </Form.Item>
                ),
              },
            ]} />
          </Form.Item>
          <Form.Item name="toolIds" label="Tools" tooltip="Which tools this agent can use. Leave empty for all.">
            <Select mode="multiple" placeholder="All tools (default)" options={toolOptions} showSearch optionFilterProp="label" allowClear />
          </Form.Item>
          <Form.Item name="skillIds" label="Skills" tooltip="Which skills to inject into this agent's prompt">
            <Select mode="multiple" placeholder="Select skills" options={skillOptions} showSearch optionFilterProp="label" allowClear />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AgentList;
