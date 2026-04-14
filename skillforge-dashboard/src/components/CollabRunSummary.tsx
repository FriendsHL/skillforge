import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Card, Row, Col, Statistic, Table, Tag } from 'antd';
import {
  TeamOutlined,
  FieldTimeOutlined,
  ApiOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import { getCollabRunSummary } from '../api';

interface MemberSummary {
  handle: string;
  sessionId: string;
  agentName: string;
  inputTokens: number;
  outputTokens: number;
  toolCalls: number;
  llmCalls: number;
  status: string;
  durationMs: number;
}

interface CollabSummaryData {
  collabRunId: string;
  status: string;
  memberCount: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalToolCalls: number;
  totalLlmCalls: number;
  totalPeerMessages: number;
  durationMs: number;
  members: MemberSummary[];
}

interface Props {
  collabRunId?: string | null;
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const secs = Math.floor(ms / 1000);
  if (secs < 60) return `${secs}s`;
  const mins = Math.floor(secs / 60);
  const remSecs = secs % 60;
  if (mins < 60) return `${mins}m ${remSecs}s`;
  const hrs = Math.floor(mins / 60);
  const remMins = mins % 60;
  return `${hrs}h ${remMins}m`;
}

const statusColor = (status?: string) => {
  switch (status?.toUpperCase()) {
    case 'RUNNING':
      return 'processing';
    case 'COMPLETED':
      return 'success';
    case 'ERROR':
      return 'error';
    case 'CANCELLED':
      return 'default';
    default:
      return 'default';
  }
};

const CollabRunSummary: React.FC<Props> = ({ collabRunId }) => {
  const [data, setData] = useState<CollabSummaryData | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchSummary = useCallback(async () => {
    if (!collabRunId) return;
    try {
      const res = await getCollabRunSummary(collabRunId);
      setData(res.data);
    } catch {
      // non-critical
    }
  }, [collabRunId]);

  useEffect(() => {
    setData(null);
    if (collabRunId) fetchSummary();
  }, [collabRunId, fetchSummary]);

  // Poll while running
  useEffect(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    if (collabRunId && data?.status === 'RUNNING') {
      pollRef.current = setInterval(fetchSummary, 5000);
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [collabRunId, data?.status, fetchSummary]);

  if (!collabRunId || !data) return null;

  const memberColumns = [
    {
      title: 'Handle',
      dataIndex: 'handle',
      key: 'handle',
      width: 120,
    },
    {
      title: 'Tokens (in/out)',
      key: 'tokens',
      width: 130,
      render: (_: unknown, r: MemberSummary) =>
        `${formatTokens(r.inputTokens)} / ${formatTokens(r.outputTokens)}`,
    },
    {
      title: 'Tool calls',
      dataIndex: 'toolCalls',
      key: 'toolCalls',
      width: 90,
      align: 'center' as const,
    },
    {
      title: 'LLM calls',
      dataIndex: 'llmCalls',
      key: 'llmCalls',
      width: 90,
      align: 'center' as const,
    },
    {
      title: 'Duration',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (v: number) => formatDuration(v),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => (
        <Tag color={statusColor(status)} style={{ margin: 0 }}>
          {status || 'unknown'}
        </Tag>
      ),
    },
  ];

  return (
    <Card
      size="small"
      title={
        <span style={{ fontSize: 13 }}>
          <TeamOutlined style={{ marginRight: 6 }} />
          Collaboration Summary
        </span>
      }
      style={{ margin: '8px 12px 0' }}
      styles={{ body: { padding: '12px' } }}
    >
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col span={6}>
          <Statistic
            title="Members"
            value={data.memberCount}
            prefix={<TeamOutlined />}
            valueStyle={{ fontSize: 20 }}
          />
        </Col>
        <Col span={6}>
          <Statistic
            title="Input tokens"
            value={formatTokens(data.totalInputTokens)}
            prefix={<RobotOutlined />}
            valueStyle={{ fontSize: 20 }}
          />
        </Col>
        <Col span={6}>
          <Statistic
            title="Output tokens"
            value={formatTokens(data.totalOutputTokens)}
            prefix={<ApiOutlined />}
            valueStyle={{ fontSize: 20 }}
          />
        </Col>
        <Col span={6}>
          <Statistic
            title="Duration"
            value={formatDuration(data.durationMs)}
            prefix={<FieldTimeOutlined />}
            valueStyle={{ fontSize: 20 }}
          />
        </Col>
      </Row>
      <Table
        size="small"
        dataSource={data.members}
        columns={memberColumns}
        rowKey="sessionId"
        pagination={false}
      />
    </Card>
  );
};

export default CollabRunSummary;
