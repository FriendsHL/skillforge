import React, { useEffect, useState } from 'react';
import { Table, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { getSessions } from '../api';

const SessionList: React.FC = () => {
  const [sessions, setSessions] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    setLoading(true);
    getSessions(1)
      .then((res) => setSessions(Array.isArray(res.data) ? res.data : res.data?.data ?? []))
      .catch(() => message.error('Failed to load sessions'))
      .finally(() => setLoading(false));
  }, []);

  const columns = [
    {
      title: 'Title',
      dataIndex: 'title',
      key: 'title',
      render: (v: string, r: any) =>
        v && v !== 'New Session' ? v : `Session ${String(r.id ?? '').slice(0, 8)}`,
    },
    { title: 'Agent', dataIndex: 'agentName', key: 'agentName', render: (v: string, r: any) => v || r.agentId },
    { title: 'Messages', dataIndex: 'messageCount', key: 'messageCount' },
    { title: 'Tokens', dataIndex: 'totalTokens', key: 'totalTokens' },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (v: string) => (v ? new Date(v).toLocaleString() : '-'),
    },
  ];

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>Sessions</h2>
      <Table
        dataSource={sessions}
        columns={columns}
        rowKey="id"
        loading={loading}
        onRow={(record) => ({
          onClick: () => navigate(`/chat/${record.id}`),
          style: { cursor: 'pointer' },
        })}
      />
    </div>
  );
};

export default SessionList;
