import React, { useEffect, useState } from 'react';
import { Table, Button, Upload, Popconfirm, message } from 'antd';
import { DeleteOutlined, InboxOutlined } from '@ant-design/icons';
import { getSkills, uploadSkill, deleteSkill } from '../api';

const { Dragger } = Upload;

const SkillList: React.FC = () => {
  const [skills, setSkills] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchSkills = () => {
    setLoading(true);
    getSkills()
      .then((res) => setSkills(Array.isArray(res.data) ? res.data : res.data?.data ?? []))
      .catch(() => message.error('Failed to load skills'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchSkills(); }, []);

  const handleUpload = async (file: File) => {
    try {
      await uploadSkill(file, 1);
      message.success('Skill uploaded');
      fetchSkills();
    } catch {
      message.error('Upload failed');
    }
    return false; // prevent default upload
  };

  const handleDelete = async (id: number) => {
    await deleteSkill(id);
    message.success('Skill deleted');
    fetchSkills();
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: 'Name', dataIndex: 'name', key: 'name' },
    { title: 'Description', dataIndex: 'description', key: 'description' },
    { title: 'Trigger', dataIndex: 'triggerKeyword', key: 'triggerKeyword' },
    {
      title: 'Actions',
      key: 'actions',
      render: (_: any, record: any) => (
        <Popconfirm title="Delete this skill?" onConfirm={() => handleDelete(record.id)}>
          <Button icon={<DeleteOutlined />} size="small" danger>
            Delete
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>Skills</h2>
      <Dragger
        accept=".zip"
        showUploadList={false}
        beforeUpload={(file) => handleUpload(file as File)}
        style={{ marginBottom: 24 }}
      >
        <p className="ant-upload-drag-icon"><InboxOutlined /></p>
        <p className="ant-upload-text">Click or drag a .zip file to upload a Skill</p>
      </Dragger>
      <Table dataSource={skills} columns={columns} rowKey="id" loading={loading} />
    </div>
  );
};

export default SkillList;
