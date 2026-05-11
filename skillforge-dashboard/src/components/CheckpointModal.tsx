import React from 'react';
import { Button, Descriptions, Empty, Space, Table, Tag, Typography } from 'antd';
import type { SessionCompactionCheckpoint } from '../api';

const { Text } = Typography;

interface CheckpointModalProps {
  open: boolean;
  loading: boolean;
  checkpoints: SessionCompactionCheckpoint[];
  selectedCheckpoint?: SessionCompactionCheckpoint;
  actionLoadingId?: string | null;
  onClose: () => void;
  onSelect: (checkpointId: string) => void;
  onRefresh: () => void;
  onBranch: (checkpointId: string) => void;
  onRestore: (checkpointId: string) => void;
}

const seqText = (v?: number | null) => (v == null ? '-' : String(v));

const CheckpointModal: React.FC<CheckpointModalProps> = ({
  open,
  loading,
  checkpoints,
  selectedCheckpoint,
  actionLoadingId,
  onClose,
  onSelect,
  onRefresh,
  onBranch,
  onRestore,
}) => {
  if (!open) return null;

  return (
    <div style={{
      position: 'fixed',
      inset: 0,
      zIndex: 1000,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
    }}>
      {/* Backdrop */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background: 'rgba(0, 0, 0, 0.6)',
          backdropFilter: 'blur(4px)',
        }}
        onClick={onClose}
      />
      
      {/* Modal */}
      <div style={{
        position: 'relative',
        background: 'var(--bg-surface)',
        border: '1px solid var(--border-1)',
        borderRadius: 12,
        width: 980,
        maxHeight: '85vh',
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column',
        boxShadow: '0 20px 60px rgba(0, 0, 0, 0.5)',
      }}>
        {/* Header */}
        <div style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '16px 20px',
          borderBottom: '1px solid var(--border-1)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span style={{ fontSize: 16 }}>📌</span>
            <span style={{ fontWeight: 600, fontSize: 15, color: 'var(--fg-1)' }}>Checkpoints</span>
            <span style={{
              fontSize: 11,
              fontFamily: 'var(--font-mono)',
              color: 'var(--fg-3)',
              background: 'var(--bg-hover)',
              padding: '2px 6px',
              borderRadius: 4,
            }}>
              {checkpoints.length}
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Button size="small" onClick={onRefresh} disabled={loading}>
              刷新
            </Button>
            <button
              onClick={onClose}
              style={{
                background: 'transparent',
                border: 'none',
                color: 'var(--fg-3)',
                cursor: 'pointer',
                padding: 4,
                fontSize: 18,
                lineHeight: 1,
              }}
            >
              ✕
            </button>
          </div>
        </div>
        
        {/* Body */}
        <div style={{ padding: 16, overflow: 'auto', flex: 1 }}>
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Table<SessionCompactionCheckpoint>
              size="small"
              rowKey="id"
              loading={loading}
              dataSource={checkpoints}
              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 checkpoint" /> }}
              pagination={{ pageSize: 8 }}
              rowSelection={{
                type: 'radio',
                selectedRowKeys: selectedCheckpoint ? [selectedCheckpoint.id] : [],
                onChange: (keys) => {
                  const selected = keys?.[0];
                  if (selected != null) onSelect(String(selected));
                },
              }}
              columns={[
                {
                  title: '时间',
                  dataIndex: 'createdAt',
                  width: 180,
                  render: (v: string) => (v ? new Date(v).toLocaleString() : '-'),
                },
                {
                  title: '原因',
                  dataIndex: 'reason',
                  width: 160,
                  render: (v: string) => <Tag style={{ margin: 0 }}>{v || 'unknown'}</Tag>,
                },
                {
                  title: 'Boundary',
                  dataIndex: 'boundarySeqNo',
                  width: 90,
                  render: (v: number | null | undefined) => seqText(v),
                },
                {
                  title: 'Summary',
                  dataIndex: 'summarySeqNo',
                  width: 90,
                  render: (v: number | null | undefined) => seqText(v),
                },
                {
                  title: 'PostEnd',
                  dataIndex: 'postRangeEndSeqNo',
                  width: 110,
                  render: (v: number | null | undefined) => seqText(v),
                },
                {
                  title: '操作',
                  key: 'actions',
                  width: 180,
                  render: (_: unknown, row) => (
                    <Space size={8}>
                      <Button
                        size="small"
                        onClick={() => onBranch(row.id)}
                        loading={actionLoadingId === `branch:${row.id}`}
                      >
                        分支
                      </Button>
                      <Button
                        size="small"
                        danger
                        onClick={() => onRestore(row.id)}
                        loading={actionLoadingId === `restore:${row.id}`}
                      >
                        恢复
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />

            <div style={{ 
              display: 'flex', 
              justifyContent: 'space-between', 
              alignItems: 'center',
              padding: '8px 12px',
              background: 'var(--bg-hover)',
              borderRadius: 6,
              fontSize: 12,
            }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                分支会创建一个新会话；恢复会覆盖当前会话消息历史。
              </Text>
            </div>

            {selectedCheckpoint && (
              <Descriptions
                size="small"
                column={2}
                bordered
                title={
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontSize: 14 }}>📋</span>
                    <span>Checkpoint 详情</span>
                  </div>
                }
                style={{ marginTop: 8 }}
              >
                <Descriptions.Item label="ID" span={2}>
                  <Text code>{selectedCheckpoint.id}</Text>
                </Descriptions.Item>
                <Descriptions.Item label="SessionId" span={2}>
                  <Text code>{selectedCheckpoint.sessionId}</Text>
                </Descriptions.Item>
                <Descriptions.Item label="Boundary Seq">{seqText(selectedCheckpoint.boundarySeqNo)}</Descriptions.Item>
                <Descriptions.Item label="Summary Seq">{seqText(selectedCheckpoint.summarySeqNo)}</Descriptions.Item>
                <Descriptions.Item label="Pre Range">
                  {seqText(selectedCheckpoint.preRangeStartSeqNo)} ~ {seqText(selectedCheckpoint.preRangeEndSeqNo)}
                </Descriptions.Item>
                <Descriptions.Item label="Post Range">
                  {seqText(selectedCheckpoint.postRangeStartSeqNo)} ~ {seqText(selectedCheckpoint.postRangeEndSeqNo)}
                </Descriptions.Item>
                <Descriptions.Item label="Snapshot Ref" span={2}>
                  {selectedCheckpoint.snapshotRef || '-'}
                </Descriptions.Item>
              </Descriptions>
            )}
          </Space>
        </div>
      </div>
    </div>
  );
};

export default CheckpointModal;
