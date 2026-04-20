import React from 'react';
import { Link } from 'react-router-dom';
import { Table, type TableColumnsType } from 'antd';
import type { ChannelConversation } from '../../types/channel';
import './channels.css';

interface ChannelConversationListProps {
  conversations: ChannelConversation[];
  loading: boolean;
}

function fmtTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const diff = Date.now() - d.getTime();
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return d.toLocaleDateString();
}

function getPlatformLabel(platform: string): React.ReactNode {
  const dotClass = `platform-dot platform-dot--${platform.toLowerCase()}`;
  return (
    <span className="platform-label">
      <span className={dotClass} />
      {platform}
    </span>
  );
}

const columns: TableColumnsType<ChannelConversation> = [
  {
    title: 'Platform',
    dataIndex: 'platform',
    key: 'platform',
    width: 120,
    render: (platform: string) => getPlatformLabel(platform),
  },
  {
    title: 'Conversation ID',
    dataIndex: 'conversationId',
    key: 'conversationId',
    ellipsis: true,
    render: (id: string) => (
      <span style={{
        fontFamily: "'JetBrains Mono', 'Fira Code', ui-monospace, monospace",
        fontSize: 11,
        opacity: 0.7,
      }}>
        {id}
      </span>
    ),
  },
  {
    title: 'Session',
    dataIndex: 'sessionId',
    key: 'sessionId',
    width: 140,
    render: (sessionId: string) => (
      <Link to={`/chat/${sessionId}`} className="session-link">
        {sessionId.slice(0, 8)}…
      </Link>
    ),
  },
  {
    title: 'Status',
    dataIndex: 'closedAt',
    key: 'status',
    width: 100,
    render: (closedAt: string | null) => (
      <span className={`ch-status-badge ch-status-badge--${closedAt ? 'inactive' : 'active'}`}>
        {closedAt ? 'Closed' : 'Active'}
      </span>
    ),
  },
  {
    title: 'Created',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 120,
    render: (v: string) => (
      <span style={{ fontSize: 12, opacity: 0.55 }}>{fmtTime(v)}</span>
    ),
  },
  {
    title: 'Closed',
    dataIndex: 'closedAt',
    key: 'closedAt',
    width: 120,
    render: (v: string | null) => (
      <span style={{ fontSize: 12, opacity: 0.55 }}>{fmtTime(v)}</span>
    ),
  },
];

const ChannelConversationList: React.FC<ChannelConversationListProps> = ({
  conversations,
  loading,
}) => (
  <div className="channels-table-wrap">
    <Table<ChannelConversation>
      dataSource={conversations}
      columns={columns}
      rowKey="id"
      loading={loading}
      size="small"
      pagination={{ pageSize: 20, showTotal: (t) => `${t} conversations` }}
      locale={{ emptyText: 'No conversations yet' }}
    />
  </div>
);

export default ChannelConversationList;
