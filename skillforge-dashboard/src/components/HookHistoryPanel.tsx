import React, { useMemo, useState } from 'react';
import { Table, Tag, Typography, Space, Empty, Modal } from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ClockCircleOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { getHookHistory, type HookHistoryEntry } from '../api';

const { Text } = Typography;

// ── Annotation parser ──────────────────────────────────────────────────────

interface ParsedAnnotation {
  handlerType: string;
  index: string;
  chainDecision: string;
  handlerLabel: string;
  stdoutSize: string;
}

function parseAnnotation(input: string): ParsedAnnotation {
  const result: ParsedAnnotation = {
    handlerType: '-',
    index: '-',
    chainDecision: '-',
    handlerLabel: '-',
    stdoutSize: '-',
  };
  if (!input) return result;

  // Format: "skill:MemorySearch|idx=0|type=skill|chainDecision=CONTINUE|stdoutSize=42"
  const parts = input.split('|');
  if (parts.length > 0) {
    result.handlerLabel = parts[0];
  }
  for (const part of parts) {
    const [key, val] = part.split('=');
    if (key === 'type') result.handlerType = val ?? '-';
    else if (key === 'idx') result.index = val ?? '-';
    else if (key === 'chainDecision') result.chainDecision = val ?? '-';
    else if (key === 'stdoutSize') result.stdoutSize = val ?? '-';
  }
  return result;
}

// ── Time formatting ────────────────────────────────────────────────────────

function formatTime(iso: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function formatDuration(ms: number): string {
  if (ms >= 60000) return `${(ms / 60000).toFixed(1)}m`;
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

// ── Event color map ────────────────────────────────────────────────────────

const EVENT_COLORS: Record<string, string> = {
  SessionStart: 'blue',
  UserPromptSubmit: 'purple',
  PostToolUse: 'green',
  Stop: 'cyan',
  SessionEnd: 'orange',
};

// ── Detail modal ───────────────────────────────────────────────────────────

interface DetailModalProps {
  entry: HookHistoryEntry | null;
  open: boolean;
  onClose: () => void;
}

const DetailModal: React.FC<DetailModalProps> = React.memo(({ entry, open, onClose }) => {
  if (!entry) return null;

  return (
    <Modal
      title={
        <Space>
          <ThunderboltOutlined />
          <span>Hook Execution Detail</span>
          {entry.success ? (
            <Tag color="success" style={{ fontSize: 11 }}>SUCCESS</Tag>
          ) : (
            <Tag color="error" style={{ fontSize: 11 }}>FAILED</Tag>
          )}
        </Space>
      }
      open={open}
      onCancel={onClose}
      footer={null}
      width={620}
      destroyOnClose
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {/* Metadata row */}
        <div style={{
          display: 'flex', gap: 16, flexWrap: 'wrap',
          padding: '10px 14px',
          background: 'var(--bg-surface)',
          borderRadius: 'var(--radius-sm)',
          border: '1px solid var(--border-subtle)',
        }}>
          <div>
            <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Event</Text>
            <Tag color={EVENT_COLORS[entry.name] ?? 'default'} style={{ fontSize: 11, margin: 0 }}>
              {entry.name}
            </Tag>
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Time</Text>
            <Text style={{ fontSize: 12 }}>{formatTime(entry.startTime)}</Text>
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Duration</Text>
            <Text style={{ fontSize: 12 }}>{formatDuration(entry.durationMs)}</Text>
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Session</Text>
            <Text style={{ fontSize: 11, fontFamily: 'var(--font-mono)' }}>{entry.sessionId}</Text>
          </div>
        </div>

        {/* Input annotation */}
        {entry.input && (
          <div>
            <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>
              Input (annotation)
            </Text>
            <pre style={{
              fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              background: 'var(--bg-code)', color: 'var(--text-on-accent)',
              padding: '10px 12px', borderRadius: 'var(--radius-sm)',
              fontFamily: 'var(--font-mono)', maxHeight: 180, overflowY: 'auto',
            }}>
              {entry.input}
            </pre>
          </div>
        )}

        {/* Output */}
        {entry.output && (
          <div>
            <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>
              Output
            </Text>
            <pre style={{
              fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              background: 'var(--bg-surface)',
              padding: '10px 12px', borderRadius: 'var(--radius-sm)',
              fontFamily: 'var(--font-mono)', maxHeight: 240, overflowY: 'auto',
              border: '1px solid var(--border-subtle)',
            }}>
              {entry.output}
            </pre>
          </div>
        )}

        {/* Error */}
        {entry.error && (
          <div>
            <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>
              Error
            </Text>
            <pre style={{
              fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              background: 'var(--color-error-bg)', color: 'var(--color-error)',
              padding: '10px 12px', borderRadius: 'var(--radius-sm)',
              fontFamily: 'var(--font-mono)', maxHeight: 180, overflowY: 'auto',
              border: '1px solid var(--color-error-border)',
            }}>
              {entry.error}
            </pre>
          </div>
        )}
      </div>
    </Modal>
  );
});

// ── Main panel ─────────────────────────────────────────────────────────────

interface HookHistoryPanelProps {
  agentId: string;
}

const HookHistoryPanel: React.FC<HookHistoryPanelProps> = ({ agentId }) => {
  const [selectedEntry, setSelectedEntry] = useState<HookHistoryEntry | null>(null);

  const { data: entries = [], isLoading, isError } = useQuery({
    queryKey: ['hook-history', agentId],
    queryFn: () => getHookHistory(agentId).then((r) => {
      const d = r.data;
      return Array.isArray(d) ? d : [];
    }),
    refetchInterval: 15000,
  });

  const columns = useMemo(() => [
    {
      title: 'Time',
      dataIndex: 'startTime',
      key: 'startTime',
      width: 130,
      render: (v: string) => (
        <Text style={{ fontSize: 11 }}>{formatTime(v)}</Text>
      ),
    },
    {
      title: 'Event',
      dataIndex: 'name',
      key: 'name',
      width: 140,
      render: (v: string) => (
        <Tag color={EVENT_COLORS[v] ?? 'default'} style={{ fontSize: 11, margin: 0 }}>
          {v}
        </Tag>
      ),
    },
    {
      title: 'Handler',
      dataIndex: 'input',
      key: 'handler',
      width: 160,
      ellipsis: true,
      render: (v: string) => {
        const parsed = parseAnnotation(v);
        return (
          <Text style={{ fontSize: 11, fontFamily: 'var(--font-mono)' }}>
            {parsed.handlerLabel}
          </Text>
        );
      },
    },
    {
      title: 'Status',
      dataIndex: 'success',
      key: 'success',
      width: 60,
      align: 'center' as const,
      render: (v: boolean) =>
        v ? (
          <CheckCircleOutlined style={{ color: 'var(--color-success)' }} />
        ) : (
          <CloseCircleOutlined style={{ color: 'var(--color-error)' }} />
        ),
    },
    {
      title: 'Duration',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 80,
      render: (v: number) => (
        <Tag
          icon={<ClockCircleOutlined />}
          color={v > 10000 ? 'orange' : 'default'}
          style={{ margin: 0, fontSize: 10 }}
        >
          {formatDuration(v)}
        </Tag>
      ),
    },
    {
      title: 'Chain',
      dataIndex: 'input',
      key: 'chain',
      width: 100,
      render: (v: string) => {
        const parsed = parseAnnotation(v);
        const color =
          parsed.chainDecision === 'CONTINUE'
            ? 'green'
            : parsed.chainDecision === 'ABORT'
              ? 'red'
              : parsed.chainDecision === 'SKIP_CHAIN'
                ? 'orange'
                : 'default';
        return (
          <Tag color={color} style={{ fontSize: 10, margin: 0 }}>
            {parsed.chainDecision}
          </Tag>
        );
      },
    },
  ], []);

  if (isError) {
    return <Empty description="Failed to load hook execution history" />;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Table
        size="small"
        rowKey="id"
        dataSource={entries}
        columns={columns}
        loading={isLoading}
        pagination={false}
        onRow={(record) => ({
          onClick: () => setSelectedEntry(record),
          style: { cursor: 'pointer' },
        })}
        locale={{
          emptyText: <Empty description="No hook executions yet" />,
        }}
        style={{
          borderRadius: 'var(--radius-sm)',
        }}
      />

      <DetailModal
        entry={selectedEntry}
        open={!!selectedEntry}
        onClose={() => setSelectedEntry(null)}
      />
    </div>
  );
};

export default React.memo(HookHistoryPanel);
