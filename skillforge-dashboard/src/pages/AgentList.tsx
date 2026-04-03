import React, { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, Space, Popconfirm, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { getAgents, createAgent, updateAgent, deleteAgent } from '../api';

const { TextArea } = Input;

const modelOptions = [
  { label: 'deepseek-chat', value: 'deepseek-chat' },
  { label: 'gpt-4o', value: 'gpt-4o' },
  { label: 'claude-sonnet', value: 'claude-sonnet' },
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
    { title: 'Model', dataIndex: 'model', key: 'model' },
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
          <Form.Item name="model" label="Model" rules={[{ required: true, message: 'Please select a model' }]}>
            <Select options={modelOptions} />
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
