import React, { useState, useEffect } from 'react';
import { Drawer, Descriptions, Tag, Table, Tooltip, Typography, Empty, Button, Space } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import {
  listPatternMembers,
  type PatternListItem,
  type PatternMemberItem,
} from '../../api/insights';

dayjs.extend(relativeTime);

const { Text, Paragraph } = Typography;

export interface PatternDetailDrawerProps {
  pattern: PatternListItem | null;
  open: boolean;
  onClose: () => void;
}

const OUTCOME_COLORS: Record<string, string> = {
  success: 'green',
  partial_success: 'blue',
  failure: 'red',
  cancelled: 'default',
};

const SURFACE_COLORS: Record<string, string> = {
  skill: 'geekblue',
  prompt: 'purple',
  behavior_rule: 'magenta',
  other: 'orange',
  unclear: 'default',
};

const DEFAULT_LIMIT = 100;
const EXPANDED_LIMIT = 500;

function fmtAbsolute(iso: string | null | undefined): string {
  if (!iso) return '';
  return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
}

function fmtRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  return dayjs(iso).fromNow();
}

const PatternDetailDrawer: React.FC<PatternDetailDrawerProps> = ({
  pattern,
  open,
  onClose,
}) => {
  // Per-drawer-open limit state — reset when the pattern changes so reopening
  // a different cluster starts back at the default cap.
  const [limit, setLimit] = useState<number>(DEFAULT_LIMIT);

  // React Query auto-skips when enabled is false; drawer close → no fetch.
  // queryKey includes (id, limit) so the "+N more" button retriggers fetch.
  const { data: members = [], isLoading } = useQuery({
    queryKey: ['pattern-members', pattern?.id, limit],
    queryFn: () =>
      pattern
        ? listPatternMembers(pattern.id, limit).then((r) => r.data ?? [])
        : Promise.resolve<PatternMemberItem[]>([]),
    enabled: open && pattern !== null,
  });

  // Reset limit whenever a fresh pattern is loaded into the drawer so each
  // newly-opened cluster starts at the default cap.
  useEffect(() => {
    setLimit(DEFAULT_LIMIT);
  }, [pattern?.id]);

  const showLoadMore =
    pattern !== null &&
    pattern.memberCount > limit &&
    limit < EXPANDED_LIMIT &&
    members.length === limit;

  const columns: ColumnsType<PatternMemberItem> = [
    {
      title: 'Session',
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 180,
      render: (sid: string) => (
        // Jump target — Traces page reads `?q=` search box (verified
        // 2026-05-14: it does NOT read `?sessionId` despite the OBS-2 spec).
        // sessionId is UUID-36, substring search is effectively exact match.
        <Link
          to={`/traces?q=${encodeURIComponent(sid)}`}
          style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
        >
          {sid.slice(0, 16)}…
        </Link>
      ),
    },
    {
      title: 'Agent',
      dataIndex: 'agentName',
      key: 'agentName',
      width: 140,
      render: (name: string | null) =>
        name ? (
          <Text style={{ fontSize: 12 }}>{name}</Text>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: 'Completed',
      dataIndex: 'completedAt',
      key: 'completedAt',
      width: 130,
      render: (iso: string | null) =>
        iso ? (
          <Tooltip title={fmtAbsolute(iso)}>
            <Text style={{ fontSize: 12 }} type="secondary">
              {fmtRelative(iso)}
            </Text>
          </Tooltip>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: 'Outcome',
      dataIndex: 'outcome',
      key: 'outcome',
      width: 110,
      render: (outcome: string | null) =>
        outcome ? (
          <Text style={{ fontSize: 12 }}>{outcome}</Text>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: 'Reasoning',
      dataIndex: 'outcomeReasoning',
      key: 'outcomeReasoning',
      render: (msg: string | null) =>
        msg ? (
          <Tooltip title={msg} placement="topLeft">
            <Text
              ellipsis
              style={{ fontSize: 12, maxWidth: 360, display: 'inline-block' }}
            >
              {msg}
            </Text>
          </Tooltip>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
  ];

  if (!pattern) {
    return (
      <Drawer open={open} onClose={onClose} width={900} title="Pattern">
        <Empty description="No pattern selected" />
      </Drawer>
    );
  }

  const headerTitle = (
    <Space size="small" wrap>
      <span>Pattern #{pattern.id}</span>
      <Tag color={OUTCOME_COLORS[pattern.outcome] ?? 'default'}>{pattern.outcome}</Tag>
      <Tag color={SURFACE_COLORS[pattern.suspectSurface] ?? 'default'}>
        {pattern.suspectSurface}
      </Tag>
    </Space>
  );

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={900}
      title={headerTitle}
      destroyOnClose
    >
      {/* Signature — long, monospaced, full width so the user sees the whole tuple. */}
      <Paragraph
        copyable={{ text: pattern.signature }}
        style={{
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
          background: 'var(--bg-2, rgba(0,0,0,0.04))',
          padding: '8px 12px',
          borderRadius: 6,
          marginBottom: 16,
          wordBreak: 'break-all',
        }}
      >
        {pattern.signature}
      </Paragraph>

      <Descriptions
        size="small"
        column={2}
        bordered
        style={{ marginBottom: 20 }}
        labelStyle={{ width: 130, fontSize: 12 }}
        contentStyle={{ fontSize: 12 }}
      >
        <Descriptions.Item label="Top failing tool">
          {pattern.topFailingTool ?? <Text type="secondary">—</Text>}
        </Descriptions.Item>
        <Descriptions.Item label="Agent id">
          {pattern.agentId !== null && pattern.agentId !== undefined ? (
            <Text style={{ fontFamily: 'var(--font-mono)' }}>#{pattern.agentId}</Text>
          ) : (
            <Text type="secondary">—</Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Member count">
          <Text strong>{pattern.memberCount}</Text>
        </Descriptions.Item>
        <Descriptions.Item label="Suggested surface">
          {/* V2 attribution — Phase 1.5 always null. */}
          {pattern.suggestedSurface ?? <Text type="secondary">(V2 only)</Text>}
        </Descriptions.Item>
        <Descriptions.Item label="First seen">
          <Tooltip title={fmtAbsolute(pattern.firstSeenAt)}>
            {fmtRelative(pattern.firstSeenAt)}
          </Tooltip>
        </Descriptions.Item>
        <Descriptions.Item label="Last seen">
          <Tooltip title={fmtAbsolute(pattern.lastSeenAt)}>
            {fmtRelative(pattern.lastSeenAt)}
          </Tooltip>
        </Descriptions.Item>
      </Descriptions>

      <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <Text strong style={{ fontSize: 13 }}>
          Member sessions{' '}
          <Text type="secondary" style={{ fontWeight: 400, fontSize: 12 }}>
            ({members.length} of {pattern.memberCount})
          </Text>
        </Text>
        {showLoadMore && (
          <Button
            size="small"
            type="link"
            onClick={() => setLimit(EXPANDED_LIMIT)}
            loading={isLoading}
          >
            Load up to {EXPANDED_LIMIT}
          </Button>
        )}
      </div>

      <Table<PatternMemberItem>
        rowKey="sessionId"
        columns={columns}
        dataSource={members}
        loading={isLoading}
        size="small"
        pagination={{ pageSize: 20, showSizeChanger: false, hideOnSinglePage: true }}
        locale={{ emptyText: 'No member sessions' }}
      />
    </Drawer>
  );
};

export default PatternDetailDrawer;
