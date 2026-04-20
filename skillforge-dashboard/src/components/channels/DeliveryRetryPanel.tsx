import React, { useCallback } from 'react';
import { Table, Button, Tooltip, message, type TableColumnsType } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { retryDelivery, dropDelivery } from '../../api/channels';
import type { ChannelDelivery } from '../../types/channel';
import './channels.css';

interface DeliveryRetryPanelProps {
  deliveries: ChannelDelivery[];
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

const DeliveryRetryPanel: React.FC<DeliveryRetryPanelProps> = ({ deliveries, loading }) => {
  const queryClient = useQueryClient();

  const { mutate: triggerRetry, isPending: isRetrying } = useMutation({
    mutationFn: (id: string) => retryDelivery(id),
    onSuccess: () => {
      message.success('Retry queued');
      queryClient.invalidateQueries({ queryKey: ['channel-deliveries'] });
    },
    onError: () => {
      message.error('Failed to retry delivery');
    },
  });

  const { mutate: triggerDrop } = useMutation({
    mutationFn: (id: string) => dropDelivery(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['channel-deliveries'] });
    },
    onError: () => {
      message.error('Failed to ignore delivery');
    },
  });

  const handleIgnore = useCallback((id: string) => {
    triggerDrop(id);
  }, [triggerDrop]);

  const visible = deliveries;

  const columns: TableColumnsType<ChannelDelivery> = [
    {
      title: 'Platform',
      dataIndex: 'platform',
      key: 'platform',
      width: 120,
      render: (platform: string) => getPlatformLabel(platform),
    },
    {
      title: 'Conversation',
      dataIndex: 'conversationId',
      key: 'conversationId',
      ellipsis: true,
      render: (id: string) => (
        <span style={{
          fontFamily: "'JetBrains Mono', 'Fira Code', ui-monospace, monospace",
          fontSize: 11,
          opacity: 0.65,
        }}>
          {id}
        </span>
      ),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: ChannelDelivery['status']) => (
        <span className={`delivery-status delivery-status--${status}`}>{status}</span>
      ),
    },
    {
      title: 'Retries',
      dataIndex: 'retryCount',
      key: 'retryCount',
      width: 70,
      align: 'center' as const,
      render: (count: number) => (
        <span className="retry-count">{count}</span>
      ),
    },
    {
      title: 'Last Error',
      dataIndex: 'lastError',
      key: 'lastError',
      ellipsis: true,
      render: (err: string | null) =>
        err ? (
          <Tooltip title={err}>
            <span className="delivery-error-text">{err}</span>
          </Tooltip>
        ) : (
          <span style={{ opacity: 0.3, fontSize: 12 }}>—</span>
        ),
    },
    {
      title: 'Scheduled',
      dataIndex: 'scheduledAt',
      key: 'scheduledAt',
      width: 110,
      render: (v: string) => (
        <span style={{ fontSize: 12, opacity: 0.5 }}>{fmtTime(v)}</span>
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 140,
      render: (_: unknown, record: ChannelDelivery) => (
        <div style={{ display: 'flex', gap: 6 }}>
          <Button
            size="small"
            type="primary"
            disabled={record.status === 'DELIVERED' || isRetrying}
            onClick={() => triggerRetry(record.id)}
          >
            Retry
          </Button>
          <Button
            size="small"
            onClick={() => handleIgnore(record.id)}
          >
            Ignore
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="channels-table-wrap">
      <Table<ChannelDelivery>
        dataSource={visible}
        columns={columns}
        rowKey="id"
        loading={loading}
        size="small"
        pagination={{ pageSize: 20, showTotal: (t) => `${t} deliveries` }}
        locale={{ emptyText: 'No failed deliveries' }}
      />
    </div>
  );
};

export default DeliveryRetryPanel;
