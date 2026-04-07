import React, { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, Space, Popconfirm, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { getAgents, createAgent, updateAgent, deleteAgent } from '../api';

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

const AgentList: React.FC = () => {
  const [agents, setAgents] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();

  const fetchAgents = () => {
    setLoading(true);
    getAgents()
      .then((res) => setAgents(Array.isArray(res.data) ? res.data : res.data?.data ?? []))
      .catch(() => message.error('Failed to load agents'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchAgents(); }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (record: any) => {
    setEditing(record);
    form.setFieldsValue({
      ...record,
      skills: record.skills?.map((s: any) => (typeof s === 'string' ? s : s.name)) ?? [],
    });
    setModalOpen(true);
  };

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      if (editing) {
        await updateAgent(editing.id, values);
        message.success('Agent updated');
      } else {
        await createAgent(values);
        message.success('Agent created');
      }
      setModalOpen(false);
      fetchAgents();
    } catch {
      // validation error
    }
  };

  const handleDelete = async (id: number) => {
    await deleteAgent(id);
    message.success('Agent deleted');
    fetchAgents();
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: 'Name', dataIndex: 'name', key: 'name' },
    { title: 'Model', dataIndex: 'modelId', key: 'modelId' },
    { title: 'Mode', dataIndex: 'executionMode', key: 'executionMode', width: 80 },
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
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          Create Agent
        </Button>
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
          <Form.Item name="systemPrompt" label="System Prompt">
            <TextArea rows={6} />
          </Form.Item>
          <Form.Item name="skills" label="Skills (names)">
            <Select mode="tags" placeholder="Type skill names and press Enter" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AgentList;
