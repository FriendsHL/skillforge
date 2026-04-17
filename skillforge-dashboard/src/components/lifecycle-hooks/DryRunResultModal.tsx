import React from 'react';
import {
  Modal,
  Space,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons';
import type { DryRunResponse } from '../../api';

interface DryRunResultModalProps {
  open: boolean;
  result: DryRunResponse | null;
  onClose: () => void;
}

const DryRunResultModal: React.FC<DryRunResultModalProps> = ({ open, result, onClose }) => {
  if (!result) return null;
  return (
    <Modal
      title={
        <Space>
          {result.success ? (
            <CheckCircleOutlined style={{ color: 'var(--color-success)' }} />
          ) : (
            <CloseCircleOutlined style={{ color: 'var(--color-error)' }} />
          )}
          <span>Dry-Run Result</span>
        </Space>
      }
      open={open}
      onCancel={onClose}
      footer={null}
      width={560}
      destroyOnClose
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div style={{
          display: 'flex', gap: 16, flexWrap: 'wrap',
          padding: '10px 14px',
          background: 'var(--bg-surface)',
          borderRadius: 'var(--radius-sm)',
          border: '1px solid var(--border-subtle)',
        }}>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Status</Typography.Text>
            <Typography.Text strong style={{ color: result.success ? 'var(--color-success)' : 'var(--color-error)', fontSize: 12 }}>
              {result.success ? 'SUCCESS' : 'FAILED'}
            </Typography.Text>
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Duration</Typography.Text>
            <Typography.Text style={{ fontSize: 12 }}>{result.durationMs}ms</Typography.Text>
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block' }}>Chain Decision</Typography.Text>
            <Typography.Text style={{ fontSize: 12, fontFamily: 'var(--font-mono)' }}>{result.chainDecision}</Typography.Text>
          </div>
        </div>

        {result.output && (
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>Output</Typography.Text>
            <pre style={{
              fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              background: 'var(--bg-code)', color: 'var(--text-on-accent)',
              padding: '10px 12px', borderRadius: 'var(--radius-sm)',
              fontFamily: 'var(--font-mono)', maxHeight: 240, overflowY: 'auto',
            }}>
              {result.output}
            </pre>
          </div>
        )}

        {result.errorMessage && (
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 4 }}>Error</Typography.Text>
            <pre style={{
              fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              background: 'var(--color-error-bg)', color: 'var(--color-error)',
              padding: '10px 12px', borderRadius: 'var(--radius-sm)',
              fontFamily: 'var(--font-mono)', maxHeight: 240, overflowY: 'auto',
              border: '1px solid var(--color-error-border)',
            }}>
              {result.errorMessage}
            </pre>
          </div>
        )}
      </div>
    </Modal>
  );
};

export default DryRunResultModal;
