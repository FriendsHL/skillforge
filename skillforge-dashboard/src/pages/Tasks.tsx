import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Typography, Table, Tag, Tooltip, Select, Space, Button } from 'antd';
import { useQuery } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import { listTaskRuns, type TaskRunItem, type TaskRunSource } from '../api/tasks';
import SchedulesPage from './Schedules';
import TabBar from '../components/TabBar';

const { Text } = Typography;

/**
 * Unified Tasks page — shows runs across all subsystems (scheduled tasks,
 * sub-agents, skill evolution, A/B evals, multi-agent collab) so operators
 * have a single feed for "what has been running".
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

/* ─── Stats bar (mirrors Schedules stats pattern) ─── */

const statsBar: React.CSSProperties = {
  display: 'flex',
  gap: 12,
  marginBottom: 20,
};

const statItem: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '8px 14px',
  background: 'var(--bg-surface)',
  border: '1px solid var(--border-1)',
  borderRadius: 'var(--radius-md)',
  fontSize: 'var(--font-size-sm)',
};

const statCount: React.CSSProperties = {
  fontFamily: 'var(--font-mono)',
  fontSize: 16,
  fontWeight: 500,
  color: 'var(--fg-1)',
};

const statLabel: React.CSSProperties = {
  color: 'var(--fg-3)',
  fontSize: 12,
};

/* ─── Empty state ─── */

const emptyWrap: React.CSSProperties = {
  padding: '48px 24px',
  textAlign: 'center',
};

const emptyIcon: React.CSSProperties = {
  width: 56,
  height: 56,
  margin: '0 auto 16px',
  background: 'var(--bg-hover)',
  borderRadius: 'var(--radius-lg)',
  display: 'grid',
  placeItems: 'center',
  color: 'var(--fg-4)',
};

const emptyTitle: React.CSSProperties = {
  fontSize: 15,
  fontWeight: 500,
  color: 'var(--fg-2)',
  margin: '0 0 6px',
};

const emptyDesc: React.CSSProperties = {
  fontSize: 'var(--font-size-sm)',
  color: 'var(--fg-4)',
  margin: 0,
};

/* ─── Main component ─── */

const Tasks: React.FC = () => {
  const [activeTab, setActiveTab] = useState('runs');
  const [source, setSource] = useState<TaskRunSource | undefined>(undefined);
  const [limit] = useState(50);

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['task-runs', source, limit],
    queryFn: () => listTaskRuns({ source, limit }).then((r) => r.data ?? []),
    refetchInterval: 30_000,
  });

  const rows: TaskRunItem[] = data ?? [];

  /* Stats computed from all rows (unfiltered) */
  const { data: allRows = [] } = useQuery({
    queryKey: ['task-runs', undefined, 200],
    queryFn: () => listTaskRuns({ limit: 200 }).then((r) => r.data ?? []),
    staleTime: 30_000,
  });

  const stats = useMemo(() => {
    const running = allRows.filter((r) => {
      const s = (r.status ?? '').toLowerCase();
      return s === 'running' || s === 'pending' || s === 'in_progress';
    }).length;
    const failed = allRows.filter((r) => {
      const s = (r.status ?? '').toLowerCase();
      return s === 'failed' || s === 'error' || s === 'timeout';
    }).length;
    const succeeded = allRows.filter((r) => {
      const s = (r.status ?? '').toLowerCase();
      return s === 'success' || s === 'completed' || s === 'promoted' || s === 'ok';
    }).length;
    return { total: allRows.length, running, failed, succeeded };
  }, [allRows]);

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
          // V3 dogfood 2026-05-15: link to /chat/<sid> so paused (ask_user)
          // sessions can be resumed in-context. /traces still searchable from
          // chat top-bar if user wants raw trace.
          <Link
            to={`/chat/${sid}`}
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
            <Text ellipsis style={{ fontSize: 12, maxWidth: 320, display: 'inline-block' }}>
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
            <Text type="danger" ellipsis style={{ fontSize: 12, maxWidth: 200, display: 'inline-block' }}>
              {msg}
            </Text>
          </Tooltip>
        ) : null,
    },
  ];

  const TAB_ITEMS = [
    { key: 'runs', label: 'Runs' },
    { key: 'schedules', label: 'Schedules' },
  ];

  const contentMaxWidth: React.CSSProperties = { maxWidth: 1400, margin: '0 auto' };

  if (activeTab === 'schedules') {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
        <TabBar tabs={TAB_ITEMS} activeTab={activeTab} onSwitch={setActiveTab} />
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable' }}>
          <SchedulesPage />
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - var(--header-height, 44px))' }}>
      <TabBar tabs={TAB_ITEMS} activeTab={activeTab} onSwitch={setActiveTab} />
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto', scrollbarGutter: 'stable', padding: 'var(--sp-6, 24px) var(--sp-8, 32px)', ...contentMaxWidth }}>
        {/* Header */}
        <div style={{ marginBottom: 'var(--sp-6, 24px)' }}>
          <h1 style={{ fontFamily: 'var(--font-serif)', fontSize: 28, fontWeight: 500, letterSpacing: '-0.02em', color: 'var(--fg-1)', margin: '0 0 4px', lineHeight: 1.2 }}>
            Task Runs
          </h1>
          <p style={{ color: 'var(--fg-3)', fontSize: 'var(--font-size-sm)', margin: 0 }}>
            Unified feed across all subsystems — scheduled tasks, sub-agents, skill evolution, A/B evals, and collab runs.
          </p>
        </div>

        {/* Stats */}
        <div style={statsBar}>
          <div style={statItem}>
            <span style={statCount}>{stats.total}</span>
            <span style={statLabel}>total</span>
          </div>
          <div style={statItem}>
            <span style={{ ...statCount, color: 'var(--color-warn, #d49a3a)' }}>{stats.running}</span>
            <span style={statLabel}>running</span>
          </div>
          <div style={statItem}>
            <span style={{ ...statCount, color: 'var(--color-err, #b8412f)' }}>{stats.failed}</span>
            <span style={statLabel}>failed</span>
          </div>
          <div style={statItem}>
            <span style={{ ...statCount, color: 'var(--color-ok, #5c8a4a)' }}>{stats.succeeded}</span>
            <span style={statLabel}>succeeded</span>
          </div>
        </div>

        {/* Toolbar */}
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

        {/* Table or empty state */}
        {!isLoading && rows.length === 0 ? (
          <div style={emptyWrap}>
            <div style={emptyIcon}>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" />
              </svg>
            </div>
            <p style={emptyTitle}>No task runs yet</p>
            <p style={emptyDesc}>Runs from scheduled tasks, sub-agents, skill evolution, and A/B evals will appear here.</p>
          </div>
        ) : (
          <Table<TaskRunItem>
            rowKey="runId"
            columns={columns}
            dataSource={rows}
            loading={isLoading}
            pagination={{ pageSize: 25, showSizeChanger: false }}
            size="small"
          />
        )}
      </div>
    </div>
  );
};

export default Tasks;
