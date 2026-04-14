import React, { useEffect, useState } from 'react';
import {
  Button, Card, Modal, Form, Input, Select, Space, Popconfirm, Tag, Tabs, message,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SearchOutlined } from '@ant-design/icons';
import { getMemories, searchMemories, createMemory, updateMemory, deleteMemory } from '../api';

const { TextArea } = Input;

const typeOptions = [
  { label: 'Preference', value: 'preference' },
  { label: 'Knowledge', value: 'knowledge' },
  { label: 'Feedback', value: 'feedback' },
  { label: 'Project', value: 'project' },
  { label: 'Reference', value: 'reference' },
];

const TAG_COLORS = ['blue', 'green', 'orange', 'purple', 'cyan', 'magenta', 'gold'];

const MemoryList: React.FC = () => {
  const [memories, setMemories] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [activeTab, setActiveTab] = useState('all');
  const [searchKeyword, setSearchKeyword] = useState('');
  const [form] = Form.useForm();

  const userId = 1; // default user

  const fetchMemories = (type?: string) => {
    setLoading(true);
    const typeParam = type && type !== 'all' ? type : undefined;
    getMemories(userId, typeParam)
      .then((res) => setMemories(Array.isArray(res.data) ? res.data : []))
      .catch(() => message.error('Failed to load memories'))
      .finally(() => setLoading(false));
  };

  const handleSearch = () => {
    if (!searchKeyword.trim()) {
      fetchMemories(activeTab);
      return;
    }
    setLoading(true);
    searchMemories(userId, searchKeyword)
      .then((res) => setMemories(Array.isArray(res.data) ? res.data : []))
      .catch(() => message.error('Search failed'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchMemories(activeTab);
  }, [activeTab]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (record: any) => {
    setEditing(record);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      if (editing) {
        await updateMemory(editing.id, { ...values, userId });
        message.success('Memory updated');
      } else {
        await createMemory({ ...values, userId });
        message.success('Memory created');
      }
      setModalOpen(false);
      fetchMemories(activeTab);
    } catch {
      // validation error
    }
  };

  const handleDelete = async (id: number) => {
    await deleteMemory(id);
    message.success('Memory deleted');
    fetchMemories(activeTab);
  };

  const filteredMemories =
    activeTab === 'all' ? memories : memories.filter((m) => m.type === activeTab);

  const renderCards = () => {
    if (loading) {
      return <div style={{ textAlign: 'center', padding: 48, color: '#999' }}>Loading...</div>;
    }
    if (filteredMemories.length === 0) {
      return <div style={{ textAlign: 'center', padding: 48, color: '#999' }}>No memories found</div>;
    }
    return (
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 16 }}>
        {filteredMemories.map((m: any) => (
          <Card
            key={m.id}
            title={<span>{m.title} <Tag color={
              m.type === 'preference' ? 'blue' :
              m.type === 'feedback' ? 'orange' :
              m.type === 'project' ? 'purple' :
              m.type === 'reference' ? 'cyan' : 'green'
            } style={{ marginLeft: 6, fontSize: 11 }}>{m.type}</Tag></span>}
            extra={
              <Space>
                <Button icon={<EditOutlined />} size="small" onClick={() => openEdit(m)} />
                <Popconfirm title="Delete this memory?" onConfirm={() => handleDelete(m.id)}>
                  <Button icon={<DeleteOutlined />} size="small" danger />
                </Popconfirm>
              </Space>
            }
          >
            <div
              style={{
                overflow: 'hidden',
                display: '-webkit-box',
                WebkitLineClamp: 3,
                WebkitBoxOrient: 'vertical',
                marginBottom: 8,
              }}
            >
              {m.content}
            </div>
            <div style={{ marginBottom: 8 }}>
              {m.tags &&
                m.tags
                  .split(',')
                  .filter((t: string) => t.trim())
                  .map((tag: string, idx: number) => (
                    <Tag key={tag} color={TAG_COLORS[idx % TAG_COLORS.length]}>
                      {tag.trim()}
                    </Tag>
                  ))}
            </div>
            <div style={{ fontSize: 12, color: '#999' }}>
              {m.createdAt ? new Date(m.createdAt).toLocaleString() : ''}
            </div>
          </Card>
        ))}
      </div>
    );
  };

  const tabItems = [
    { key: 'all', label: 'All' },
    { key: 'preference', label: 'Preferences' },
    { key: 'knowledge', label: 'Knowledge' },
    { key: 'feedback', label: 'Feedback' },
    { key: 'project', label: 'Project' },
    { key: 'reference', label: 'Reference' },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>Memories</h2>
        <Space>
          <Input
            placeholder="Search memories..."
            value={searchKeyword}
            onChange={(e) => setSearchKeyword(e.target.value)}
            onPressEnter={handleSearch}
            suffix={<SearchOutlined onClick={handleSearch} style={{ cursor: 'pointer' }} />}
            style={{ width: 260 }}
          />
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            New Memory
          </Button>
        </Space>
      </div>

      <Tabs activeKey={activeTab} onChange={setActiveTab} items={tabItems} />

      {renderCards()}

      <Modal
        title={editing ? 'Edit Memory' : 'New Memory'}
        open={modalOpen}
        onOk={handleOk}
        onCancel={() => setModalOpen(false)}
        width={560}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="type"
            label="Type"
            rules={[{ required: true, message: 'Please select a type' }]}
          >
            <Select options={typeOptions} />
          </Form.Item>
          <Form.Item
            name="title"
            label="Title"
            rules={[{ required: true, message: 'Please enter a title' }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="content"
            label="Content"
            rules={[{ required: true, message: 'Please enter content' }]}
          >
            <TextArea rows={4} />
          </Form.Item>
          <Form.Item name="tags" label="Tags (comma-separated)">
            <Input placeholder="e.g. java, coding, preference" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default MemoryList;
