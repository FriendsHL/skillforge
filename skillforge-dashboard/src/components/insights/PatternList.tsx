import React from 'react';
import { Table, Tag, Tooltip, Typography, Empty } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import type { PatternListItem } from '../../api/insights';

dayjs.extend(relativeTime);

const { Text } = Typography;

export interface PatternListProps {
  /** Already sorted server-side (member_count DESC, last_seen_at DESC). */
  patterns: PatternListItem[];
  loading: boolean;
  onRowClick: (pattern: PatternListItem) => void;
}

/**
 * Color map for outcome chips. Semantic, not decorative (design.md §Required
 * Quality 5): success=green, partial=blue, failure=red, cancelled=neutral.
 * Falls back to default tag for unknown values.
 */
const OUTCOME_COLORS: Record<string, string> = {
  success: 'green',
  partial_success: 'blue',
  failure: 'red',
  cancelled: 'default',
};

/**
 * Color map for suspect-surface chips. Each surface gets a distinct hue so
 * the eye can scan the column without re-reading text.
 */
const SURFACE_COLORS: Record<string, string> = {
  skill: 'geekblue',
  prompt: 'purple',
  behavior_rule: 'magenta',
  other: 'orange',
  unclear: 'default',
};

function fmtRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  return dayjs(iso).fromNow();
}

function fmtAbsolute(iso: string | null | undefined): string {
  if (!iso) return '';
  return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
}

const PatternList: React.FC<PatternListProps> = ({ patterns, loading, onRowClick }) => {
  const columns: ColumnsType<PatternListItem> = [
    {
      title: 'Signature',
      dataIndex: 'signature',
      key: 'signature',
      // VARCHAR 256 on BE — use ellipsis + tooltip rather than wrap.
      width: 320,
      render: (sig: string) => (
        <Tooltip title={sig} placement="topLeft">
          <Text
            style={{
              fontFamily: 'var(--font-mono)',
              fontSize: 12,
              maxWidth: 300,
              display: 'inline-block',
            }}
            ellipsis
          >
            {sig}
          </Text>
        </Tooltip>
      ),
    },
    {
      title: 'Outcome',
      dataIndex: 'outcome',
      key: 'outcome',
      width: 130,
      render: (outcome: string) => (
        <Tag color={OUTCOME_COLORS[outcome] ?? 'default'}>{outcome}</Tag>
      ),
    },
    {
      title: 'Surface',
      dataIndex: 'suspectSurface',
      key: 'suspectSurface',
      width: 130,
      render: (surface: string) => (
        <Tag color={SURFACE_COLORS[surface] ?? 'default'}>{surface}</Tag>
      ),
    },
    {
      title: 'Top failing tool',
      dataIndex: 'topFailingTool',
      key: 'topFailingTool',
      width: 160,
      render: (tool: string | null) =>
        tool ? (
          <Text style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{tool}</Text>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: 'Agent',
      dataIndex: 'agentId',
      key: 'agentId',
      width: 90,
      // Phase 1.5 shows raw id; agent-name join is a V2 polish item.
      render: (id: number | null) =>
        id !== null && id !== undefined ? (
          <Text style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>#{id}</Text>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: 'Members',
      dataIndex: 'memberCount',
      key: 'memberCount',
      width: 100,
      align: 'right' as const,
      render: (count: number) => (
        // Larger clusters get visual weight so the eye sees them first.
        <Tag color={count >= 10 ? 'red' : count >= 5 ? 'orange' : 'blue'}>{count}</Tag>
      ),
    },
    {
      title: 'First seen',
      dataIndex: 'firstSeenAt',
      key: 'firstSeenAt',
      width: 130,
      render: (iso: string) => (
        <Tooltip title={fmtAbsolute(iso)}>
          <Text style={{ fontSize: 12 }} type="secondary">
            {fmtRelative(iso)}
          </Text>
        </Tooltip>
      ),
    },
    {
      title: 'Last seen',
      dataIndex: 'lastSeenAt',
      key: 'lastSeenAt',
      width: 130,
      render: (iso: string) => (
        <Tooltip title={fmtAbsolute(iso)}>
          <Text style={{ fontSize: 12 }}>{fmtRelative(iso)}</Text>
        </Tooltip>
      ),
    },
  ];

  return (
    <Table<PatternListItem>
      rowKey="id"
      columns={columns}
      dataSource={patterns}
      loading={loading}
      size="small"
      pagination={{ pageSize: 50, showSizeChanger: false, hideOnSinglePage: true }}
      onRow={(record) => ({
        onClick: () => onRowClick(record),
        style: { cursor: 'pointer' },
      })}
      locale={{
        emptyText: (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <Text type="secondary">
                No patterns yet — wait for the hourly session-annotator cron to run and cluster ≥ 3 members.
              </Text>
            }
          />
        ),
      }}
    />
  );
};

export default PatternList;
