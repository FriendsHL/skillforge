import React from 'react';
import { Table, Tag } from 'antd';

interface CompactionHistoryModalProps {
  open: boolean;
  onClose: () => void;
  events: any[];
}

const CompactionHistoryModal: React.FC<CompactionHistoryModalProps> = ({ open, onClose, events }) => {
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
        width: 900,
        maxHeight: '80vh',
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
            <span style={{ fontSize: 16 }}>📊</span>
            <span style={{ fontWeight: 600, fontSize: 15, color: 'var(--fg-1)' }}>Compaction History</span>
          </div>
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
        
        {/* Body */}
        <div style={{ padding: 16, overflow: 'auto' }}>
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
                width: 80,
                render: (v: string) => (
                  <Tag color={v === 'full' ? 'volcano' : 'blue'} style={{ margin: 0 }}>
                    {v}
                  </Tag>
                ),
              },
              { title: 'Source', dataIndex: 'source', width: 110 },
              { title: 'Reason', dataIndex: 'reason', ellipsis: true },
              {
                title: 'Reclaimed',
                dataIndex: 'tokensReclaimed',
                width: 100,
                render: (v: number) => (
                  <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{v} tok</span>
                ),
              },
              {
                title: 'Strategies',
                dataIndex: 'strategiesApplied',
                width: 200,
                ellipsis: true,
              },
            ]}
          />
        </div>
      </div>
    </div>
  );
};

export default CompactionHistoryModal;
