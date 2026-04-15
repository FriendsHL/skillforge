import React from 'react';
import { Modal, Table, Tag } from 'antd';

interface CompactionHistoryModalProps {
  open: boolean;
  onClose: () => void;
  events: any[];
}

const CompactionHistoryModal: React.FC<CompactionHistoryModalProps> = ({ open, onClose, events }) => (
  <Modal title="压缩历史" open={open} onCancel={onClose} footer={null} width={900}>
    <Table
      size="small"
      rowKey="id"
      dataSource={events}
      pagination={{ pageSize: 10 }}
      columns={[
        {
          title: 'Time',
          dataIndex: 'triggeredAt',
          width: 160,
          render: (v: string) => (v ? new Date(v).toLocaleString() : '-'),
        },
        {
          title: 'Level',
          dataIndex: 'level',
          width: 70,
          render: (v: string) => <Tag color={v === 'full' ? 'volcano' : 'blue'}>{v}</Tag>,
        },
        { title: 'Source', dataIndex: 'source', width: 110 },
        { title: 'Reason', dataIndex: 'reason', ellipsis: true },
        {
          title: 'Reclaimed',
          dataIndex: 'tokensReclaimed',
          width: 100,
          render: (v: number) => `${v} tok`,
        },
        {
          title: 'Strategies',
          dataIndex: 'strategiesApplied',
          width: 200,
          ellipsis: true,
        },
      ]}
    />
  </Modal>
);

export default CompactionHistoryModal;
