import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Popconfirm, Tag, Tabs, message, Card, Drawer } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, FileTextOutlined, HistoryOutlined, ExperimentOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getAgents, createAgent, updateAgent, deleteAgent, getTools, getSkills, getClaudeMd, saveClaudeMd, extractList, type BehaviorRuleConfig, type CreateAgentRequest, type UpdateAgentRequest } from '../api';
import { AgentSchema, safeParseList, type AgentDto } from '../api/schemas';
import PromptHistoryPanel from '../components/PromptHistoryPanel';
import HookHistoryPanel from '../components/HookHistoryPanel';
import ScenarioDraftPanel from '../components/ScenarioDraftPanel';
import BehaviorRulesEditor from '../components/BehaviorRulesEditor';
import LifecycleHooksEditor, {
  type LifecycleHooksEditorHandle,
} from '../components/LifecycleHooksEditor';
import { useBehaviorRules } from '../hooks/useBehaviorRules';

const { TextArea } = Input;

// 模型建议列表 —— 支持 "providerName:modelName" 格式覆盖默认 provider。
// 用户也可以自由输入任何模型名。
const modelOptions = [
  { label: 'bailian:qwen3.5-plus', value: 'bailian:qwen3.5-plus' },
  { label: 'bailian:qwen3-max-2026-01-23', value: 'bailian:qwen3-max-2026-01-23' },
  { label: 'bailian:qwen3-coder-next', value: 'bailian:qwen3-coder-next' },
  { label: 'bailian:glm-5', value: 'bailian:glm-5' },
  { label: 'openai:deepseek-chat', value: 'openai:deepseek-chat' },
  { label: 'openai:gpt-4o', value: 'openai:gpt-4o' },
  { label: 'claude:claude-sonnet-4-20250514', value: 'claude:claude-sonnet-4-20250514' },
];

// 把后端的 skillIds (JSON string) 解析成数组
const parseSkillIds = (raw: any): string[] => {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw.map(String);
  if (typeof raw === 'string') {
    try {
      const arr = JSON.parse(raw);
      return Array.isArray(arr) ? arr.map(String) : [];
    } catch {
      return [];
    }
  }
  return [];
};

const parseBehaviorRules = (raw: unknown): BehaviorRuleConfig | null => {
  if (!raw) return null;
  if (typeof raw === 'string') {
    try { return JSON.parse(raw) as BehaviorRuleConfig; } catch { return null; }
  }
  if (typeof raw === 'object' && raw !== null) return raw as BehaviorRuleConfig;
  return null;
};

const AgentList: React.FC = () => {
  const queryClient = useQueryClient();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<AgentDto | null>(null);
  // Bumped on every open so Modal (and the ref-tracked LifecycleHooksEditor inside)
  // remounts on create→create cycles too, not just editing-id changes.
  const [formKey, setFormKey] = useState(0);
  const [form] = Form.useForm();
  const executionMode = Form.useWatch('executionMode', form) ?? 'ask';
  const [claudeMdModalOpen, setClaudeMdModalOpen] = useState(false);
  const [claudeMdDraft, setClaudeMdDraft] = useState<string | null>(null);
  const [promptHistoryAgentId, setPromptHistoryAgentId] = useState<string | null>(null);
  const [hookHistoryAgentId, setHookHistoryAgentId] = useState<string | null>(null);
  const [scenariosAgentId, setScenariosAgentId] = useState<string | null>(null);

  const behaviorRules = useBehaviorRules(
    editing ? parseBehaviorRules(editing.behaviorRules) : null,
    executionMode,
  );

  // N3 lifecycle hooks — raw JSON string round-trips via AgentEntity.lifecycleHooks.
  // The editor owns its state and exposes live rawJson/errors via this ref so we
  // can validate at Save-time without a render round-trip per keystroke.
  const initialLifecycleHooks =
    editing && typeof editing.lifecycleHooks === 'string' ? editing.lifecycleHooks : null;
  const lifecycleHooksRef = useRef<LifecycleHooksEditorHandle>(null);

  const { data: agents = [], isLoading: loading, isError: agentsError } = useQuery({
    queryKey: ['agents'],
    queryFn: () =>
      getAgents().then((res) => safeParseList(AgentSchema, extractList<any>(res))),
  });
  // Fire toast once after all retries exhausted — avoids double-toast from catch+retry.
  useEffect(() => {
    if (agentsError) message.error('Failed to load agents');
  }, [agentsError]);

  const { data: tools = [] } = useQuery({
    queryKey: ['tools'],
    queryFn: () =>
      getTools().then((res) => extractList<any>(res)),
  });
  const toolOptions = useMemo(
    () =>
      tools.map((t: any) => ({
        label: t.description ? `${t.name} — ${t.description}` : t.name,
        value: t.name,
      })),
    [tools],
  );

  const { data: skills = [] } = useQuery({
    queryKey: ['skills'],
    queryFn: () =>
      getSkills().then((res) => extractList<any>(res)),
  });
  const skillOptions = useMemo(
    () =>
      skills.map((s: any) => ({
        label: s.description ? `${s.name} — ${s.description}` : s.name,
        value: s.name,
      })),
    [skills],
  );

  const { data: claudeMdFromServer = '' } = useQuery({
    queryKey: ['claude-md', 1],
    queryFn: () => getClaudeMd(1).then((res) => res.data?.claudeMd ?? ''),
  });
  const claudeMdContent = claudeMdDraft ?? claudeMdFromServer;

  const invalidateAgents = () => queryClient.invalidateQueries({ queryKey: ['agents'] });

  const createMutation = useMutation({
    mutationFn: (payload: CreateAgentRequest) => createAgent(payload),
    onSuccess: () => {
      message.success('Agent created');
      invalidateAgents();
    },
  });
  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: number; payload: UpdateAgentRequest }) => updateAgent(id, payload),
    onSuccess: () => {
      message.success('Agent updated');
      invalidateAgents();
    },
    onError: () => message.error('Failed to update agent'),
  });
  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteAgent(id),
    onSuccess: () => {
      message.success('Agent deleted');
      invalidateAgents();
    },
    onError: () => message.error('Failed to delete agent'),
  });
  const saveClaudeMdMutation = useMutation({
    mutationFn: (content: string) => saveClaudeMd(1, content),
    onSuccess: () => {
      message.success('CLAUDE.md saved');
      setClaudeMdModalOpen(false);
      setClaudeMdDraft(null);
      queryClient.invalidateQueries({ queryKey: ['claude-md', 1] });
    },
    onError: () => message.error('Failed to save CLAUDE.md'),
  });

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ executionMode: 'ask', skillIds: [], toolIds: [] });
    setFormKey((k) => k + 1);
    setModalOpen(true);
  };

  const openEdit = (record: AgentDto) => {
    setEditing(record);
    form.setFieldsValue({
      ...record,
      skillIds: parseSkillIds(record.skillIds),
      toolIds: parseSkillIds(record.toolIds),
    });
    setFormKey((k) => k + 1);
    setModalOpen(true);
  };

  const handleOk = async () => {
    try {
      const values = await form.validateFields();

      // Validate lifecycle hooks before save — block on errors (see doc §5.6).
      const hooksState = lifecycleHooksRef.current;
      const hooksErrors = hooksState?.errors ?? [];
      if (hooksErrors.length > 0) {
        message.error(`Fix lifecycle hooks config before saving: ${hooksErrors[0]}`);
        return;
      }

      const payload = {
        ...values,
        skillIds: JSON.stringify(values.skillIds ?? []),
        toolIds: JSON.stringify(values.toolIds ?? []),
        behaviorRules: JSON.stringify(behaviorRules.config),
        lifecycleHooks: hooksState?.rawJson ?? '',
      };
      if (editing) {
        await updateMutation.mutateAsync({ id: editing.id, payload: { ...editing, ...payload } });
      } else {
        await createMutation.mutateAsync(payload);
      }
      setModalOpen(false);
    } catch (e: unknown) {
      // antd Form.validateFields rejects with { errorFields, ... } — ignore, Form shows the errors inline.
      if (typeof e === 'object' && e !== null && 'errorFields' in e) return;
      const detail = e instanceof Error ? e.message : 'unknown';
      message.error(`Save failed: ${detail}`);
    }
  };

  const handleDelete = (id: number) => deleteMutation.mutate(id);

  const handleSaveClaudeMd = () => saveClaudeMdMutation.mutate(claudeMdContent);

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: 'Name', dataIndex: 'name', key: 'name' },
    { title: 'Model', dataIndex: 'modelId', key: 'modelId' },
    { title: 'Mode', dataIndex: 'executionMode', key: 'executionMode', width: 80 },
    {
      title: 'Tools',
      dataIndex: 'toolIds',
      key: 'toolIds',
      render: (v: any) => {
        const arr = parseSkillIds(v);
        if (arr.length === 0) return <span style={{ color: 'var(--text-muted)' }}>All</span>;
        const shown = arr.slice(0, 3);
        const rest = arr.length - shown.length;
        return (
          <Space size={[4, 4]} wrap>
            {shown.map((n) => (
              <Tag key={n} color="green">
                {n}
              </Tag>
            ))}
            {rest > 0 && <Tag>+{rest}</Tag>}
          </Space>
        );
      },
    },
    {
      title: 'Skills',
      dataIndex: 'skillIds',
      key: 'skillIds',
      render: (v: any) => {
        const arr = parseSkillIds(v);
        if (arr.length === 0) return <span style={{ color: 'var(--text-muted)' }}>—</span>;
        const shown = arr.slice(0, 3);
        const rest = arr.length - shown.length;
        return (
          <Space size={[4, 4]} wrap>
            {shown.map((n) => (
              <Tag key={n} color="blue">
                {n}
              </Tag>
            ))}
            {rest > 0 && <Tag>+{rest}</Tag>}
          </Space>
        );
      },
    },
    { title: 'Status', dataIndex: 'status', key: 'status' },
    {
      title: 'Actions',
      key: 'actions',
      render: (_: any, record: any) => (
        <Space>
          <Button icon={<EditOutlined />} size="small" onClick={() => openEdit(record)}>
            Edit
          </Button>
          <Button
            icon={<HistoryOutlined />}
            size="small"
            onClick={() => setPromptHistoryAgentId(String(record.id))}
          >
            Prompts
          </Button>
          <Button
            icon={<ThunderboltOutlined />}
            size="small"
            onClick={() => setHookHistoryAgentId(String(record.id))}
          >
            Hooks
          </Button>
          <Button
            icon={<ExperimentOutlined />}
            size="small"
            onClick={() => setScenariosAgentId(String(record.id))}
          >
            Scenarios
          </Button>
          <Popconfirm title="Delete this agent?" onConfirm={() => handleDelete(record.id)}>
            <Button icon={<DeleteOutlined />} size="small" danger>
              Delete
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', marginBottom: 16 }}>
        <Space>
          <Button icon={<FileTextOutlined />} onClick={() => setClaudeMdModalOpen(true)}>
            CLAUDE.md (Global)
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            Create Agent
          </Button>
        </Space>
      </div>
      <Card style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
        <Table dataSource={agents} columns={columns} rowKey="id" loading={loading} />
      </Card>

      <Modal
        key={`${editing?.id ?? 'new'}-${formKey}`}
        title={editing ? 'Edit Agent' : 'Create Agent'}
        open={modalOpen}
        onOk={handleOk}
        onCancel={() => setModalOpen(false)}
        width={760}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="Name" rules={[{ required: true, message: 'Please enter agent name' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <TextArea rows={2} />
          </Form.Item>
          <Form.Item
            name="modelId"
            label="Model"
            rules={[{ required: true, message: 'Please select a model' }]}
          >
            <Select
              options={modelOptions}
              placeholder="选择模型"
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item
            name="executionMode"
            label="Execution Mode"
            initialValue="ask"
            tooltip="ask: Agent 遇到失败/歧义时主动问用户;auto: 自主执行"
          >
            <Select
              options={[
                { label: 'ask — 主动确认', value: 'ask' },
                { label: 'auto — 自主执行', value: 'auto' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="maxLoops"
            label="Max Loops"
            tooltip="Maximum loop iterations for this agent (default: 25, max: 200). Increase for research/exploration tasks."
          >
            <InputNumber min={1} max={200} placeholder="25" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="Prompts">
            <Tabs destroyInactiveTabPane={false} items={[
              {
                key: 'agent',
                label: 'AGENT.md',
                children: (
                  <Form.Item name="systemPrompt" noStyle>
                    <TextArea rows={10} placeholder="# Agent Core Instructions&#10;&#10;You are..." />
                  </Form.Item>
                ),
              },
              {
                key: 'soul',
                label: 'SOUL.md',
                children: (
                  <Form.Item name="soulPrompt" noStyle>
                    <TextArea rows={10} placeholder="# Persona & Tone&#10;&#10;(Optional) Define personality..." />
                  </Form.Item>
                ),
              },
              {
                key: 'tools',
                label: 'TOOLS.md',
                children: (
                  <Form.Item name="toolsPrompt" noStyle>
                    <TextArea rows={10} placeholder="# Tool Usage Rules&#10;&#10;(Optional) Custom tool rules..." />
                  </Form.Item>
                ),
              },
              {
                key: 'rules',
                label: 'RULES.md',
                children: (
                  <BehaviorRulesEditor
                    groupedRules={behaviorRules.groupedRules}
                    templateId={behaviorRules.templateId}
                    customRules={behaviorRules.config.customRules}
                    isLoading={behaviorRules.isLoading}
                    onApplyTemplate={behaviorRules.applyTemplate}
                    onToggleRule={behaviorRules.toggleRule}
                    onAddCustomRule={behaviorRules.addCustomRule}
                    onRemoveCustomRule={behaviorRules.removeCustomRule}
                    onUpdateCustomRule={behaviorRules.updateCustomRule}
                  />
                ),
              },
              {
                key: 'memory',
                label: 'MEMORY.md',
                children: (
                  <div style={{ color: 'var(--text-muted)', padding: 16 }}>
                    Memory is automatically injected from the Memory system.
                    <br />
                    <a href="/memories">Manage Memories &rarr;</a>
                  </div>
                ),
              },
              {
                key: 'hooks',
                label: 'HOOKS.md',
                children: (
                  <LifecycleHooksEditor
                    ref={lifecycleHooksRef}
                    initialJson={initialLifecycleHooks}
                    agentId={editing ? String(editing.id) : null}
                    skills={skills.map((s: { name: string; description?: string }) => ({
                      name: s.name,
                      description: s.description,
                    }))}
                  />
                ),
              },
            ]} />
          </Form.Item>
          <Form.Item
            name="toolIds"
            label="Tools"
            tooltip="Select which tools (function calling) this agent can use. Leave empty for all tools."
          >
            <Select
              mode="multiple"
              placeholder="All tools (default)"
              options={toolOptions}
              showSearch
              optionFilterProp="label"
              allowClear
            />
          </Form.Item>
          <Form.Item
            name="skillIds"
            label="Skills"
            tooltip="Select which skills (SKILL.md knowledge packs) to inject into this agent's prompt"
          >
            <Select
              mode="multiple"
              placeholder="Select skills"
              options={skillOptions}
              showSearch
              optionFilterProp="label"
              allowClear
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="CLAUDE.md — Global Rules (applies to all agents)"
        open={claudeMdModalOpen}
        onOk={handleSaveClaudeMd}
        onCancel={() => { setClaudeMdModalOpen(false); setClaudeMdDraft(null); }}
        width={720}
      >
        <TextArea
          rows={15}
          value={claudeMdContent}
          onChange={(e) => setClaudeMdDraft(e.target.value)}
          placeholder="# Global Rules&#10;&#10;Rules that apply to all agents..."
        />
      </Modal>

      <Drawer
        title={
          <Space>
            <HistoryOutlined />
            <span>Prompt Version History</span>
          </Space>
        }
        width={640}
        open={!!promptHistoryAgentId}
        onClose={() => setPromptHistoryAgentId(null)}
        destroyOnClose
      >
        {promptHistoryAgentId && (
          <PromptHistoryPanel agentId={promptHistoryAgentId} />
        )}
      </Drawer>

      <Drawer
        title={
          <Space>
            <ThunderboltOutlined />
            <span>Hook Execution History</span>
          </Space>
        }
        width={680}
        open={!!hookHistoryAgentId}
        onClose={() => setHookHistoryAgentId(null)}
        destroyOnClose
      >
        {hookHistoryAgentId && (
          <HookHistoryPanel agentId={hookHistoryAgentId} />
        )}
      </Drawer>

      <Drawer
        title={
          <Space>
            <ExperimentOutlined />
            <span>Scenario Drafts — Agent #{scenariosAgentId}</span>
          </Space>
        }
        width={680}
        open={!!scenariosAgentId}
        onClose={() => setScenariosAgentId(null)}
        destroyOnClose
      >
        {scenariosAgentId && (
          <ScenarioDraftPanel agentId={scenariosAgentId} />
        )}
      </Drawer>
    </div>
  );
};

export default AgentList;
