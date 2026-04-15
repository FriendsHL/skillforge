import React from 'react';
import { Segmented, Button, Typography } from 'antd';
import { HistoryOutlined, MessageOutlined } from '@ant-design/icons';
import type { ExecutionMode } from '../hooks/useChatSession';

const { Text } = Typography;

interface SessionToolbarProps {
  lightCompactCount: number;
  fullCompactCount: number;
  totalTokensReclaimed: number;
  viewMode: 'chat' | 'replay';
  onViewModeChange: (v: 'chat' | 'replay') => void;
  compacting: boolean;
  runtimeRunning: boolean;
  executionMode: ExecutionMode;
  onExecutionModeChange: (m: ExecutionMode) => void;
  onCompactClick: () => void;
  onOpenCompactModal: () => void;
}

const SessionToolbar: React.FC<SessionToolbarProps> = ({
  lightCompactCount,
  fullCompactCount,
  totalTokensReclaimed,
  viewMode,
  onViewModeChange,
  compacting,
  runtimeRunning,
  executionMode,
  onExecutionModeChange,
  onCompactClick,
  onOpenCompactModal,
}) => (
  <div
    style={{
      padding: '8px 12px',
      borderBottom: '1px solid var(--border-subtle)',
      display: 'flex',
      justifyContent: 'flex-end',
      alignItems: 'center',
      gap: 8,
    }}
  >
    {(lightCompactCount > 0 || fullCompactCount > 0 || totalTokensReclaimed > 0) && (
      <a
        onClick={onOpenCompactModal}
        title="查看压缩历史"
        style={{ fontSize: 12, color: '#888', marginRight: 8 }}
      >
        🗜 {lightCompactCount} light · {fullCompactCount} full · -{totalTokensReclaimed} tok
      </a>
    )}
    <Segmented
      size="small"
      value={viewMode}
      options={[
        { label: <span><MessageOutlined /> Chat</span>, value: 'chat' },
        { label: <span><HistoryOutlined /> Replay</span>, value: 'replay' },
      ]}
      onChange={(v) => onViewModeChange(v as 'chat' | 'replay')}
    />
    <Button
      size="small"
      disabled={runtimeRunning || compacting}
      loading={compacting}
      onClick={onCompactClick}
      title="立即对老历史做一次 LLM 总结压缩"
    >
      🗜 Compact (full)
    </Button>
    <Text type="secondary" style={{ fontSize: 12 }}>模式:</Text>
    <Segmented
      size="small"
      value={executionMode}
      options={[
        { label: 'ask', value: 'ask' },
        { label: 'auto', value: 'auto' },
      ]}
      onChange={(v) => onExecutionModeChange(v as ExecutionMode)}
    />
  </div>
);

export default SessionToolbar;
