import React from 'react';
import { Button, Descriptions, Empty, Modal, Space, Table, Tag, Typography } from 'antd';
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
}) => (
  <Modal
    title="Checkpoint 历史"
    open={open}
    onCancel={onClose}
    footer={null}
    width={980}
    destroyOnClose
  >
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
            render: (v: string) => <Tag>{v || 'unknown'}</Tag>,
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
            width: 220,
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

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Text type="secondary">
          说明：分支会创建一个新会话；恢复会覆盖当前会话消息历史。
        </Text>
        <Button size="small" onClick={onRefresh} disabled={loading}>
          刷新
        </Button>
      </div>

      {selectedCheckpoint && (
        <Descriptions
          size="small"
          column={2}
          bordered
          title="Checkpoint 详情"
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
  </Modal>
);

export default CheckpointModal;
