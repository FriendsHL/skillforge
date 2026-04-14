import React, { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, Space, Popconfirm, Tag, Tabs, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, FileTextOutlined } from '@ant-design/icons';
import { getAgents, createAgent, updateAgent, deleteAgent, getBuiltinSkills, getClaudeMd, saveClaudeMd } from '../api';

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

const AgentList: React.FC = () => {
  const [agents, setAgents] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [skillOptions, setSkillOptions] = useState<{ label: string; value: string }[]>([]);
  const [form] = Form.useForm();
  const [claudeMdModalOpen, setClaudeMdModalOpen] = useState(false);
  const [claudeMdContent, setClaudeMdContent] = useState('');

  const fetchAgents = () => {
    setLoading(true);
    getAgents()
      .then((res) => setAgents(Array.isArray(res.data) ? res.data : res.data?.data ?? []))
      .catch(() => message.error('Failed to load agents'))
      .finally(() => setLoading(false));
  };

  const fetchSkills = () => {
    getBuiltinSkills()
      .then((res) => {
        const list: any[] = Array.isArray(res.data) ? res.data : (res.data as any)?.data ?? [];
        setSkillOptions(
          list.map((s: any) => ({
            label: s.description ? `${s.name} — ${s.description}` : s.name,
            value: s.name,
          }))
        );
      })
      .catch(() => {
        // 静默失败:即使 skills 拉不到也不阻塞 Agent 管理
      });
  };

  const fetchClaudeMd = () => {
    getClaudeMd(1).then((res) => setClaudeMdContent(res.data?.claudeMd ?? '')).catch(() => {});
  };

  useEffect(() => {
    fetchAgents();
    fetchSkills();
    fetchClaudeMd();
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ executionMode: 'ask', skillIds: [] });
    setModalOpen(true);
  };

  const openEdit = (record: any) => {
    setEditing(record);
    form.setFieldsValue({
      ...record,
      skillIds: parseSkillIds(record.skillIds),
    });
    setModalOpen(true);
  };

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      // 把数组形式的 skillIds 序列化成 JSON 字符串(后端字段是 TEXT)
      const payload = {
        ...values,
        skillIds: JSON.stringify(values.skillIds ?? []),
      };
      if (editing) {
        await updateAgent(editing.id, { ...editing, ...payload });
        message.success('Agent updated');
      } else {
        await createAgent(payload);
        message.success('Agent created');
      }
      setModalOpen(false);
      fetchAgents();
    } catch (e: any) {
      if (e?.errorFields) return; // form 校验
      message.error('Save failed: ' + (e?.message ?? 'unknown'));
    }
  };

  const handleDelete = async (id: number) => {
    await deleteAgent(id);
    message.success('Agent deleted');
    fetchAgents();
  };

  const handleSaveClaudeMd = async () => {
    try {
      await saveClaudeMd(1, claudeMdContent);
      message.success('CLAUDE.md saved');
      setClaudeMdModalOpen(false);
    } catch {
      message.error('Failed to save CLAUDE.md');
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: 'Name', dataIndex: 'name', key: 'name' },
    { title: 'Model', dataIndex: 'modelId', key: 'modelId' },
    { title: 'Mode', dataIndex: 'executionMode', key: 'executionMode', width: 80 },
    {
      title: 'Skills',
      dataIndex: 'skillIds',
      key: 'skillIds',
      render: (v: any) => {
        const arr = parseSkillIds(v);
        if (arr.length === 0) return <span style={{ color: '#999' }}>—</span>;
        const shown = arr.slice(0, 4);
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
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>Agents</h2>
        <Space>
          <Button icon={<FileTextOutlined />} onClick={() => setClaudeMdModalOpen(true)}>
            CLAUDE.md (Global)
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            Create Agent
          </Button>
        </Space>
      </div>
      <Table dataSource={agents} columns={columns} rowKey="id" loading={loading} />

      <Modal
        title={editing ? 'Edit Agent' : 'Create Agent'}
        open={modalOpen}
        onOk={handleOk}
        onCancel={() => setModalOpen(false)}
        width={640}
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
                key: 'memory',
                label: 'MEMORY.md',
                children: (
                  <div style={{ color: '#999', padding: 16 }}>
                    Memory is automatically injected from the Memory system.
                    <br />
                    <a href="/memories">Manage Memories &rarr;</a>
                  </div>
                ),
              },
            ]} />
          </Form.Item>
          <Form.Item
            name="skillIds"
            label="Skills"
            tooltip="选择 Agent 可调用的内置 / Zip 包技能"
          >
            <Select
              mode="multiple"
              placeholder="选择 Skills"
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
        onCancel={() => setClaudeMdModalOpen(false)}
        width={720}
      >
        <TextArea
          rows={15}
          value={claudeMdContent}
          onChange={(e) => setClaudeMdContent(e.target.value)}
          placeholder="# Global Rules&#10;&#10;Rules that apply to all agents..."
        />
      </Modal>
    </div>
  );
};

export default AgentList;
