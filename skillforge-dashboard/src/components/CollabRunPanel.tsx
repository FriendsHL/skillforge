import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Card, Table, Tag, Badge, Segmented, Typography } from 'antd';
import { getCollabRunMembers } from '../api';
import ChildAgentFeed from './ChildAgentFeed';

const { Text } = Typography;

interface CollabMember {
  handle: string;
  sessionId: string;
  runtimeStatus: string;
  agentId: number;
  depth: number;
  title?: string;
  createdAt?: string;
  completedAt?: string;
}

interface CollabRunData {
  collabRunId: string;
  status: string;
  leaderSessionId: string;
  maxDepth: number;
  maxTotalAgents: number;
  members: CollabMember[];
  createdAt?: string;
  completedAt?: string;
}

interface Props {
  collabRunId?: string | null;
  sessionId?: string;
}

const statusColor = (status?: string) => {
  switch (status) {
    case 'running':
      return 'processing';
    case 'RUNNING':
      return 'processing';
    case 'COMPLETED':
      return 'success';
    case 'CANCELLED':
      return 'default';
    case 'idle':
      return 'default';
    case 'error':
      return 'error';
    default:
      return 'default';
  }
};

const statusBorderColor = (status?: string) => {
  switch (status) {
    case 'running':
      return '#52c41a';
    case 'RUNNING':
      return '#52c41a';
    case 'COMPLETED':
      return '#1677ff';
    case 'idle':
      return '#d9d9d9';
    case 'error':
      return '#ff4d4f';
    case 'CANCELLED':
      return '#d9d9d9';
    default:
      return '#d9d9d9';
  }
};

const runStatusBadge = (status?: string) => {
  switch (status) {
    case 'RUNNING':
      return 'processing' as const;
    case 'COMPLETED':
      return 'success' as const;
    case 'CANCELLED':
      return 'default' as const;
    default:
      return 'default' as const;
  }
};

/** Build a tree structure: depth-0 is root, depth-1 are children, depth-2 are grandchildren of depth-1. */
function buildTree(members: CollabMember[], leaderSessionId: string) {
  const leader = members.find((m) => m.sessionId === leaderSessionId) ?? members.find((m) => m.depth === 0);
  const depth1 = members.filter((m) => m.depth === 1);
  const depth2 = members.filter((m) => m.depth === 2);
  return { leader, depth1, depth2 };
}

function formatDuration(startIso?: string, endIso?: string): string {
  if (!startIso) return '-';
  const start = new Date(startIso).getTime();
  if (Number.isNaN(start)) return '-';
  const end = endIso ? new Date(endIso).getTime() : Date.now();
  const diffMs = Math.max(0, end - start);
  const secs = Math.floor(diffMs / 1000);
  if (secs < 60) return `${secs}s`;
  const mins = Math.floor(secs / 60);
  const remSecs = secs % 60;
  if (mins < 60) return `${mins}m ${remSecs}s`;
  const hrs = Math.floor(mins / 60);
  const remMins = mins % 60;
  return `${hrs}h ${remMins}m`;
}

const TreeNode: React.FC<{ member: CollabMember; isCurrentSession: boolean }> = ({ member, isCurrentSession }) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      padding: '6px 12px',
      borderLeft: `3px solid ${statusBorderColor(member.runtimeStatus)}`,
      marginBottom: 4,
      background: isCurrentSession ? '#e6f4ff' : '#fafafa',
      borderRadius: 4,
    }}
  >
    <Text strong style={{ fontSize: 13 }}>{member.handle}</Text>
    <Tag color={statusColor(member.runtimeStatus)} style={{ marginRight: 0 }}>
      {member.runtimeStatus || 'unknown'}
    </Tag>
    <Text type="secondary" style={{ fontSize: 12 }}>
      {member.title || `Agent #${member.agentId}`}
    </Text>
  </div>
);

const CollabRunPanel: React.FC<Props> = ({ collabRunId, sessionId }) => {
  const [data, setData] = useState<CollabRunData | null>(null);
  const [viewType, setViewType] = useState<'Table' | 'Tree'>('Table');
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchMembers = useCallback(async () => {
    if (!collabRunId) return;
    try {
      const res = await getCollabRunMembers(collabRunId);
      setData(res.data);
    } catch {
      // non-critical
    }
  }, [collabRunId]);

  useEffect(() => {
    setData(null);
    if (collabRunId) fetchMembers();
  }, [collabRunId, fetchMembers]);

  // Poll every 5s while any member is running
  useEffect(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    const hasRunning = data?.members?.some((m) => m.runtimeStatus === 'running');
    if (collabRunId && (hasRunning || data?.status === 'RUNNING')) {
      pollRef.current = setInterval(fetchMembers, 5000);
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [collabRunId, data, fetchMembers]);

  const summaryStats = useMemo(() => {
    if (!data?.members) return null;
    const total = data.members.length;
    const running = data.members.filter((m) => m.runtimeStatus === 'running').length;
    const completed = data.members.filter((m) =>
      m.runtimeStatus === 'completed' || m.runtimeStatus === 'COMPLETED'
    ).length;
    const failed = data.members.filter((m) =>
      m.runtimeStatus === 'error' || m.runtimeStatus === 'ERROR'
    ).length;
    return { total, running, completed, failed };
  }, [data]);

  if (!collabRunId || !data) return null;

  const columns = [
    {
      title: 'Handle',
      dataIndex: 'handle',
      key: 'handle',
      width: 140,
    },
    {
      title: 'Agent',
      dataIndex: 'title',
      key: 'title',
      ellipsis: true,
      render: (title: string, record: CollabMember) => title || `Agent #${record.agentId}`,
    },
    {
      title: 'Status',
      dataIndex: 'runtimeStatus',
      key: 'runtimeStatus',
      width: 100,
      render: (status: string) => (
        <Tag color={statusColor(status)} style={{ marginRight: 0 }}>
          {status || 'unknown'}
        </Tag>
      ),
    },
    {
      title: 'Depth',
      dataIndex: 'depth',
      key: 'depth',
      width: 60,
    },
  ];

  const tree = buildTree(data.members, data.leaderSessionId);

  const renderTreeView = () => (
    <div style={{ padding: '8px 12px' }}>
      {/* Leader (depth 0) */}
      {tree.leader && (
        <TreeNode member={tree.leader} isCurrentSession={tree.leader.sessionId === sessionId} />
      )}
      {/* Depth 1 children */}
      {tree.depth1.map((child) => (
        <div key={child.sessionId} style={{ marginLeft: 24 }}>
          <TreeNode member={child} isCurrentSession={child.sessionId === sessionId} />
          {/* Depth 2 grandchildren under this depth-1 member */}
          {tree.depth2
            .filter(() => true) /* For Phase 3, depth-2 members are shown under all depth-1 */
            .map((gc) => (
              <div key={gc.sessionId} style={{ marginLeft: 24 }}>
                <TreeNode member={gc} isCurrentSession={gc.sessionId === sessionId} />
              </div>
            ))}
        </div>
      ))}
    </div>
  );

  return (
    <Card
      size="small"
      title={
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span>
            Team members{' '}
            <Badge status={runStatusBadge(data.status)} text={data.status} style={{ fontSize: 12 }} />
          </span>
          <Segmented
            size="small"
            value={viewType}
            options={['Table', 'Tree']}
            onChange={(v) => setViewType(v as 'Table' | 'Tree')}
          />
        </div>
      }
      style={{ margin: '8px 12px 0' }}
      styles={{ body: { padding: viewType === 'Tree' ? 0 : 0 } }}
    >
      {viewType === 'Table' ? (
        <Table
          size="small"
          dataSource={data.members}
          columns={columns}
          rowKey="sessionId"
          pagination={false}
          rowClassName={(record) => (record.sessionId === sessionId ? 'ant-table-row-selected' : '')}
          expandable={{
            expandedRowRender: (record: CollabMember) => (
              <ChildAgentFeed
                sessionId={record.sessionId}
                isRunning={record.runtimeStatus === 'running'}
              />
            ),
            rowExpandable: () => true,
          }}
        />
      ) : (
        renderTreeView()
      )}
      {/* Summary section */}
      {summaryStats && (
        <div
          style={{
            padding: '6px 12px',
            borderTop: '1px solid #f0f0f0',
            display: 'flex',
            gap: 16,
            fontSize: 12,
            color: '#666',
            flexWrap: 'wrap',
          }}
        >
          <span>Members: <Text strong style={{ fontSize: 12 }}>{summaryStats.total}</Text></span>
          {summaryStats.running > 0 && (
            <span>Running: <Text style={{ fontSize: 12, color: '#52c41a' }}>{summaryStats.running}</Text></span>
          )}
          {summaryStats.completed > 0 && (
            <span>Done: <Text style={{ fontSize: 12, color: '#1677ff' }}>{summaryStats.completed}</Text></span>
          )}
          {summaryStats.failed > 0 && (
            <span>Failed: <Text style={{ fontSize: 12, color: '#ff4d4f' }}>{summaryStats.failed}</Text></span>
          )}
          <span>Duration: {formatDuration(data.createdAt, data.completedAt)}</span>
          <span title={data.collabRunId}>
            ID: {data.collabRunId.length > 12 ? data.collabRunId.slice(0, 12) + '...' : data.collabRunId}
          </span>
        </div>
      )}
    </Card>
  );
};

export default CollabRunPanel;
