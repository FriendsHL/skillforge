import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { Typography, Table, Tag, Tooltip, Select, Space, Button } from 'antd';
import { useQuery } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import { listTaskRuns, type TaskRunItem, type TaskRunSource } from '../api/tasks';

const { Title, Paragraph, Text } = Typography;

/**
 * Unified Tasks page — shows runs across all subsystems (scheduled tasks,
 * sub-agents, skill evolution, A/B evals, multi-agent collab) so operators
 * have a single feed for "what has been running".
 *
 * Filter `source` to narrow to one subsystem. Default = combined feed, sort
 * by triggeredAt DESC.
 */

const SOURCE_OPTIONS: { value: TaskRunSource; label: string; color: string }[] = [
  { value: 'scheduled_task', label: 'Scheduled', color: 'blue' },
  { value: 'subagent', label: 'SubAgent', color: 'purple' },
  { value: 'skill_evolution', label: 'Skill Evolution', color: 'gold' },
  { value: 'skill_ab', label: 'Skill A/B', color: 'cyan' },
  { value: 'prompt_ab', label: 'Prompt A/B', color: 'magenta' },
  { value: 'collab', label: 'Collab', color: 'geekblue' },
];
const SOURCE_COLOR: Record<TaskRunSource, string> = SOURCE_OPTIONS.reduce(
  (acc, o) => ({ ...acc, [o.value]: o.color }),
  {} as Record<TaskRunSource, string>,
);
const SOURCE_LABEL: Record<TaskRunSource, string> = SOURCE_OPTIONS.reduce(
  (acc, o) => ({ ...acc, [o.value]: o.label }),
  {} as Record<TaskRunSource, string>,
);

function statusColor(status: string | null): string {
  if (!status) return 'default';
  const s = status.toLowerCase();
  if (['success', 'completed', 'promoted', 'ok'].includes(s)) return 'green';
  if (['failure', 'failed', 'error', 'timeout'].includes(s)) return 'red';
  if (['running', 'pending', 'initialized', 'in_progress'].includes(s)) return 'processing';
  if (['skipped', 'cancelled', 'paused'].includes(s)) return 'default';
  return 'blue';
}

function fmtRelative(iso: string | null): string {
  if (!iso) return '—';
  const t = new Date(iso).getTime();
  const diff = Date.now() - t;
  if (diff < 0) return new Date(iso).toLocaleString();
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour}h ago`;
  const day = Math.floor(hour / 24);
  if (day < 30) return `${day}d ago`;
  return new Date(iso).toLocaleDateString();
}

function fmtDuration(start: string | null, end: string | null): string {
  if (!start || !end) return '—';
  const ms = new Date(end).getTime() - new Date(start).getTime();
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

const Tasks: React.FC = () => {
  const [source, setSource] = useState<TaskRunSource | undefined>(undefined);
  const [limit] = useState(50);

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['task-runs', source, limit],
    queryFn: () => listTaskRuns({ source, limit }).then((r) => r.data ?? []),
    refetchInterval: 30_000, // light auto-refresh — running tasks change fast
  });

  const rows: TaskRunItem[] = data ?? [];

  const columns: ColumnsType<TaskRunItem> = [
    {
      title: 'Source',
      dataIndex: 'source',
      key: 'source',
      width: 140,
      render: (s: TaskRunSource) => (
        <Tag color={SOURCE_COLOR[s]} style={{ fontSize: 12 }}>
          {SOURCE_LABEL[s]}
        </Tag>
      ),
      filters: SOURCE_OPTIONS.map((o) => ({ text: o.label, value: o.value })),
      onFilter: (val, row) => row.source === val,
    },
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
      width: 220,
      render: (name: string) => (
        <Text style={{ fontSize: 12, fontFamily: 'var(--font-mono)' }}>{name}</Text>
      ),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: string) => (
        <Tag color={statusColor(status)} style={{ fontSize: 12 }}>
          {status}
        </Tag>
      ),
    },
    {
      title: 'Triggered',
      dataIndex: 'triggeredAt',
      key: 'triggeredAt',
      width: 130,
      render: (iso: string | null) =>
        iso ? (
          <Tooltip title={new Date(iso).toLocaleString()}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {fmtRelative(iso)}
            </Text>
          </Tooltip>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: 'Duration',
      key: 'duration',
      width: 100,
      render: (_v, row) => (
        <Text style={{ fontSize: 12, fontFamily: 'var(--font-mono)' }}>
          {fmtDuration(row.triggeredAt, row.finishedAt)}
        </Text>
      ),
    },
    {
      title: 'Session',
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 130,
      render: (sid: string | null) =>
        sid ? (
          <Link
            to={`/traces?q=${encodeURIComponent(sid)}`}
            style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
          >
            {sid.slice(0, 8)}…
          </Link>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: 'Detail',
      dataIndex: 'detail',
      key: 'detail',
      render: (msg: string | null) =>
        msg ? (
          <Tooltip title={msg} placement="topLeft">
            <Text
              ellipsis
              style={{ fontSize: 12, maxWidth: 320, display: 'inline-block' }}
            >
              {msg}
            </Text>
          </Tooltip>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: 'Error',
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      width: 220,
      render: (msg: string | null) =>
        msg ? (
          <Tooltip title={msg} placement="topLeft">
            <Text
              type="danger"
              ellipsis
              style={{ fontSize: 12, maxWidth: 200, display: 'inline-block' }}
            >
              {msg}
            </Text>
          </Tooltip>
        ) : null,
    },
  ];

  return (
    <div style={{ padding: '24px 32px' }}>
      <Title level={3} style={{ marginBottom: 4 }}>
        Tasks
      </Title>
      <Paragraph type="secondary" style={{ marginBottom: 20, fontSize: 13 }}>
        Unified view of all task / agent runs across scheduled tasks, sub-agents,
        skill evolution, A/B evals, and multi-agent collabs. Auto-refresh every 30s.
      </Paragraph>

      <Space style={{ marginBottom: 16 }} size="middle">
        <Select<TaskRunSource | undefined>
          allowClear
          placeholder="Filter by source"
          value={source}
          onChange={(v) => setSource(v)}
          options={SOURCE_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
          style={{ width: 200 }}
        />
        <Button onClick={() => refetch()} loading={isFetching}>
          Refresh
        </Button>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {rows.length} run{rows.length === 1 ? '' : 's'}
        </Text>
      </Space>

      <Table<TaskRunItem>
        rowKey="runId"
        columns={columns}
        dataSource={rows}
        loading={isLoading}
        pagination={{ pageSize: 25, showSizeChanger: false }}
        size="small"
      />
    </div>
  );
};

export default Tasks;
