import React, { useState, useEffect } from 'react';
import { Table, Button, Upload, Popconfirm, message, Tag, Card, Divider, Switch, Modal, Tabs, Typography } from 'antd';
import { DeleteOutlined, InboxOutlined, ThunderboltOutlined, ToolOutlined, EyeOutlined, CodeOutlined, FileTextOutlined, LockOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getSkills, getBuiltinSkills, uploadSkill, deleteSkill, getSkillDetail, toggleSkill, extractList } from '../api';
import MarkdownRenderer from '../components/MarkdownRenderer';

const { Dragger } = Upload;
const { Text, Paragraph } = Typography;

const SkillList: React.FC = () => {
  const queryClient = useQueryClient();
  const [detailVisible, setDetailVisible] = useState(false);
  const [selectedDetailId, setSelectedDetailId] = useState<number | null>(null);

  const { data: skills = [], isLoading: skillsLoading, isError: skillsError } = useQuery({
    queryKey: ['skills'],
    queryFn: () =>
      getSkills().then((res) => extractList<any>(res)),
  });
  useEffect(() => {
    if (skillsError) message.error('Failed to load skills');
  }, [skillsError]);

  const { data: detail, isLoading: detailLoading } = useQuery({
    queryKey: ['skill-detail', selectedDetailId],
    queryFn: () => getSkillDetail(selectedDetailId!).then((res) => res.data),
    enabled: !!selectedDetailId,
  });
  const { data: builtinSkills = [], isLoading: builtinLoading } = useQuery({
    queryKey: ['builtin-skills'],
    queryFn: () => getBuiltinSkills().then((res) => extractList<any>(res)),
  });
  const loading = skillsLoading || builtinLoading;

  const invalidateSkills = () => queryClient.invalidateQueries({ queryKey: ['skills'] });

  const uploadMutation = useMutation({
    mutationFn: (file: File) => uploadSkill(file, 1),
    onSuccess: () => {
      message.success('Skill uploaded');
      invalidateSkills();
    },
    onError: (err: any) => {
      const errMsg = err?.response?.data?.error || 'Upload failed';
      message.error(errMsg);
    },
  });
  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteSkill(id),
    onSuccess: () => {
      message.success('Skill deleted');
      invalidateSkills();
    },
    onError: () => message.error('Failed to delete skill'),
  });
  const toggleMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) => toggleSkill(id, enabled),
    onSuccess: (_d, vars) => {
      message.success(vars.enabled ? 'Skill enabled' : 'Skill disabled');
      invalidateSkills();
    },
    onError: () => message.error('Failed to toggle skill'),
  });

  const handleUpload = (file: File) => {
    uploadMutation.mutate(file);
    return false;
  };

  const handleDelete = (id: number | string) => deleteMutation.mutate(id as number);
  const handleToggle = (id: number, enabled: boolean) => toggleMutation.mutate({ id, enabled });

  const showDetail = (id: number | string) => {
    setSelectedDetailId(id as number);
    setDetailVisible(true);
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
      title: 'R/O',
      dataIndex: 'readOnly',
      key: 'readOnly',
      width: 100,
      render: (v: boolean) => v ? <Tag color="green">Yes</Tag> : <Tag color="orange">No</Tag>,
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 100,
      align: 'center' as const,
      render: (_: any, record: any) => (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 4 }}>
          <Button icon={<EyeOutlined />} size="small" onClick={() => showBuiltinDetail(record)}>
            Detail
          </Button>
        </div>
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
      align: 'center' as const,
      render: (_: any, record: any) => (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 4 }}>
          <Button icon={<EyeOutlined />} size="small" onClick={() => showDetail(record.id)}>
            Detail
          </Button>
          {!record.system && (
            <Popconfirm title="Delete this skill?" onConfirm={() => handleDelete(record.id)}>
              <Button icon={<DeleteOutlined />} size="small" danger />
            </Popconfirm>
          )}
        </div>
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
      <Card title="System Tools" size="small" style={{ marginBottom: 24, borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
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

      <Card title="Skills" size="small" style={{ marginBottom: 16, borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)' }}>
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
        onCancel={() => { setDetailVisible(false); setSelectedDetailId(null); }}
        footer={null}
        width={800}
      >
        {detailLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}>Loading...</div>
        ) : detail ? (
          <div style={{ maxHeight: '70vh', overflowY: 'auto' }}>
          <Tabs
            items={[
              {
                key: 'skill-md',
                label: 'SKILL.md',
                children: (
                  <div style={{ maxHeight: 500, overflow: 'auto', padding: 8 }}>
                    <MarkdownRenderer content={detail.skillMd || detail.promptContent || 'No content'} />
                  </div>
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
          </div>
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
          <div style={{ maxHeight: '70vh', overflowY: 'auto' }}>
          <Tabs items={[
            {
              key: 'description',
              label: 'Description',
              children: (
                <div style={{ maxHeight: 400, overflow: 'auto', padding: 8 }}>
                  <MarkdownRenderer content={builtinDetail.description || 'No description'} />
                </div>
              ),
            },
            {
              key: 'schema',
              label: 'Tool Schema',
              children: builtinDetail.toolSchema ? (
                <pre style={{ background: 'var(--bg-code)', color: '#E8E8E8', padding: 16, borderRadius: 'var(--radius-md)', fontFamily: 'var(--font-mono)', fontSize: 13, overflow: 'auto', maxHeight: 400, margin: 0 }}>
                  {JSON.stringify(builtinDetail.toolSchema, null, 2)}
                </pre>
              ) : <Text type="secondary">No schema</Text>,
            },
            {
              key: 'meta',
              label: 'Metadata',
              children: (
                <div>
                  <Paragraph><strong>Name:</strong> {builtinDetail.name}</Paragraph>
                  <Paragraph><strong>Read-Only:</strong> {builtinDetail.readOnly ? 'Yes' : 'No'}</Paragraph>
                </div>
              ),
            },
          ]} />
          </div>
        ) : null}
      </Modal>
    </div>
  );
};

export default SkillList;
