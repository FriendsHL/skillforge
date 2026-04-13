import React, { useEffect, useState } from 'react';
import { Table, Button, Upload, Popconfirm, message, Tag, Card, Divider, Switch, Modal, Tabs, Typography } from 'antd';
import { DeleteOutlined, InboxOutlined, ThunderboltOutlined, ToolOutlined, EyeOutlined, CodeOutlined, FileTextOutlined, LockOutlined } from '@ant-design/icons';
import { getSkills, getBuiltinSkills, uploadSkill, deleteSkill, getSkillDetail, toggleSkill } from '../api';

const { Dragger } = Upload;
const { Text, Paragraph } = Typography;

const SkillList: React.FC = () => {
  const [skills, setSkills] = useState<any[]>([]);
  const [builtinSkills, setBuiltinSkills] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [detailVisible, setDetailVisible] = useState(false);
  const [detail, setDetail] = useState<any>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const fetchSkills = () => {
    setLoading(true);
    Promise.all([getSkills(), getBuiltinSkills()])
      .then(([skillsRes, builtinRes]) => {
        setSkills(Array.isArray(skillsRes.data) ? skillsRes.data : skillsRes.data?.data ?? []);
        setBuiltinSkills(Array.isArray(builtinRes.data) ? builtinRes.data : []);
      })
      .catch(() => message.error('Failed to load skills'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchSkills(); }, []);

  const handleUpload = async (file: File) => {
    try {
      await uploadSkill(file, 1);
      message.success('Skill uploaded');
      fetchSkills();
    } catch (err: any) {
      const errMsg = err?.response?.data?.error || 'Upload failed';
      message.error(errMsg);
    }
    return false;
  };

  const handleDelete = async (id: number | string) => {
    await deleteSkill(id as number);
    message.success('Skill deleted');
    fetchSkills();
  };

  const handleToggle = async (id: number, enabled: boolean) => {
    try {
      await toggleSkill(id, enabled);
      message.success(enabled ? 'Skill enabled' : 'Skill disabled');
      fetchSkills();
    } catch {
      message.error('Failed to toggle skill');
    }
  };

  const showDetail = async (id: number | string) => {
    setDetailVisible(true);
    setDetailLoading(true);
    try {
      const res = await getSkillDetail(id);
      setDetail(res.data);
    } catch {
      message.error('Failed to load skill detail');
    } finally {
      setDetailLoading(false);
    }
  };

  const [builtinDetailVisible, setBuiltinDetailVisible] = useState(false);
  const [builtinDetail, setBuiltinDetail] = useState<any>(null);

  const showBuiltinDetail = (record: any) => {
    setBuiltinDetail(record);
    setBuiltinDetailVisible(true);
  };

  const builtinColumns = [
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
      width: 150,
      render: (name: string) => (
        <span>
          <ThunderboltOutlined style={{ marginRight: 6, color: '#1677ff' }} />
          {name}
        </span>
      ),
    },
    { title: 'Description', dataIndex: 'description', key: 'description', ellipsis: true },
    {
      title: 'Type',
      key: 'type',
      width: 100,
      render: () => <Tag color="blue">Tool</Tag>,
    },
    {
      title: 'Read-Only',
      dataIndex: 'readOnly',
      key: 'readOnly',
      width: 90,
      render: (v: boolean) => v ? <Tag color="green">Yes</Tag> : <Tag color="orange">No</Tag>,
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 80,
      render: (_: any, record: any) => (
        <Button icon={<EyeOutlined />} size="small" onClick={() => showBuiltinDetail(record)}>
          Detail
        </Button>
      ),
    },
  ];

  const skillColumns = [
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
      width: 200,
      render: (name: string, record: any) => (
        <span>
          {record.system
            ? <LockOutlined style={{ marginRight: 6, color: '#1677ff' }} />
            : <ToolOutlined style={{ marginRight: 6, color: '#52c41a' }} />
          }
          {name}
          {record.system && <Tag color="blue" style={{ marginLeft: 8 }}>System</Tag>}
        </span>
      ),
    },
    {
      title: 'Description',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
    {
      title: 'Tools',
      dataIndex: 'requiredTools',
      key: 'requiredTools',
      width: 180,
      render: (tools: string) =>
        tools
          ? tools.split(',').map((t: string) => <Tag key={t} style={{ marginBottom: 2 }}>{t.trim()}</Tag>)
          : <Text type="secondary">-</Text>,
    },
    {
      title: 'Enabled',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (enabled: boolean, record: any) =>
        record.system ? (
          <Tag color="green">Always</Tag>
        ) : (
          <Switch
            size="small"
            checked={enabled}
            onChange={(checked) => handleToggle(record.id, checked)}
          />
        ),
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 140,
      render: (_: any, record: any) => (
        <span style={{ display: 'flex', gap: 4 }}>
          <Button icon={<EyeOutlined />} size="small" onClick={() => showDetail(record.id)}>
            Detail
          </Button>
          {!record.system && (
            <Popconfirm title="Delete this skill?" onConfirm={() => handleDelete(record.id)}>
              <Button icon={<DeleteOutlined />} size="small" danger />
            </Popconfirm>
          )}
        </span>
      ),
    },
  ];

  const renderReferences = () => {
    if (!detail?.references || Object.keys(detail.references).length === 0) {
      return <Text type="secondary">No reference files</Text>;
    }
    return Object.entries(detail.references).map(([name, content]) => (
      <Card key={name} size="small" title={<span><FileTextOutlined style={{ marginRight: 6 }} />{name}</span>} style={{ marginBottom: 8 }}>
        <pre style={{ maxHeight: 300, overflow: 'auto', fontSize: 13, whiteSpace: 'pre-wrap', margin: 0 }}>
          {content as string}
        </pre>
      </Card>
    ));
  };

  const renderScripts = () => {
    if (!detail?.scripts || detail.scripts.length === 0) {
      return <Text type="secondary">No scripts</Text>;
    }
    return detail.scripts.map((script: any) => (
      <Card key={script.name} size="small" title={<span><CodeOutlined style={{ marginRight: 6 }} />{script.name}</span>} style={{ marginBottom: 8 }}>
        <pre style={{ maxHeight: 300, overflow: 'auto', fontSize: 13, background: '#1e1e1e', color: '#d4d4d4', padding: 12, borderRadius: 6, margin: 0 }}>
          {script.content}
        </pre>
      </Card>
    ));
  };

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>Skills & Tools</h2>

      <Card title="System Tools" size="small" style={{ marginBottom: 24 }}>
        <Table
          dataSource={builtinSkills}
          columns={builtinColumns}
          rowKey="name"
          loading={loading}
          pagination={false}
          size="small"
        />
      </Card>

      <Divider />

      <Card title="Skills" size="small" style={{ marginBottom: 16 }}>
        <Dragger
          accept=".zip"
          showUploadList={false}
          beforeUpload={(file) => handleUpload(file as File)}
          style={{ marginBottom: 16 }}
        >
          <p className="ant-upload-drag-icon"><InboxOutlined /></p>
          <p className="ant-upload-text">Click or drag a .zip file to upload a Skill</p>
          <p className="ant-upload-hint" style={{ color: '#888', fontSize: 12 }}>
            zip 包须包含 SKILL.md（支持 YAML frontmatter），可选 reference.md、scripts/ 等
          </p>
        </Dragger>
        <Table
          dataSource={skills}
          columns={skillColumns}
          rowKey={(record) => record.id}
          loading={loading}
          size="small"
          locale={{ emptyText: 'No skills available' }}
        />
      </Card>

      <Modal
        title={detail ? `Skill: ${detail.name}` : 'Skill Detail'}
        open={detailVisible}
        onCancel={() => { setDetailVisible(false); setDetail(null); }}
        footer={null}
        width={800}
      >
        {detailLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}>Loading...</div>
        ) : detail ? (
          <Tabs
            items={[
              {
                key: 'skill-md',
                label: 'SKILL.md',
                children: (
                  <pre style={{ maxHeight: 500, overflow: 'auto', fontSize: 13, whiteSpace: 'pre-wrap', background: '#fafafa', padding: 16, borderRadius: 6 }}>
                    {detail.skillMd || 'No content'}
                  </pre>
                ),
              },
              {
                key: 'references',
                label: `References (${detail.references ? Object.keys(detail.references).length : 0})`,
                children: renderReferences(),
              },
              {
                key: 'scripts',
                label: `Scripts (${detail.scripts ? detail.scripts.length : 0})`,
                children: renderScripts(),
              },
              {
                key: 'meta',
                label: 'Metadata',
                children: (
                  <div>
                    <Paragraph><strong>Name:</strong> {detail.name}</Paragraph>
                    <Paragraph><strong>Description:</strong> {detail.description || '-'}</Paragraph>
                    <Paragraph><strong>Required Tools:</strong> {detail.requiredTools || '-'}</Paragraph>
                    <Paragraph><strong>Enabled:</strong> {detail.enabled ? 'Yes' : 'No'}</Paragraph>
                    <Paragraph><strong>Created:</strong> {detail.createdAt}</Paragraph>
                  </div>
                ),
              },
            ]}
          />
        ) : null}
      </Modal>

      <Modal
        title={builtinDetail ? `System Tool: ${builtinDetail.name}` : 'System Tool Detail'}
        open={builtinDetailVisible}
        onCancel={() => { setBuiltinDetailVisible(false); setBuiltinDetail(null); }}
        footer={null}
        width={700}
      >
        {builtinDetail ? (
          <div>
            <Paragraph><strong>Name:</strong> {builtinDetail.name}</Paragraph>
            <Paragraph><strong>Description:</strong> {builtinDetail.description}</Paragraph>
            <Paragraph><strong>Read-Only:</strong> {builtinDetail.readOnly ? 'Yes' : 'No'}</Paragraph>
            {builtinDetail.toolSchema && (
              <>
                <Divider titlePlacement="left" plain>Tool Schema (Input Parameters)</Divider>
                <pre style={{ maxHeight: 400, overflow: 'auto', fontSize: 13, background: '#f5f5f5', padding: 12, borderRadius: 6, margin: 0 }}>
                  {JSON.stringify(builtinDetail.toolSchema, null, 2)}
                </pre>
              </>
            )}
          </div>
        ) : null}
      </Modal>
    </div>
  );
};

export default SkillList;
