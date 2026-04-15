import React from 'react';
import { Alert, Button } from 'antd';
import type { RuntimeStatus } from '../hooks/useChatSession';

interface RuntimeBannerProps {
  runtimeStatus: RuntimeStatus;
  runtimeStep: string;
  runtimeError: string;
  cancelling: boolean;
  onCancel: () => void;
}

const RuntimeBanner: React.FC<RuntimeBannerProps> = ({
  runtimeStatus,
  runtimeStep,
  runtimeError,
  cancelling,
  onCancel,
}) => {
  if (runtimeStatus === 'idle' && runtimeStep !== 'cancelled') return null;
  if (runtimeStatus === 'running') {
    return (
      <Alert
        type="info"
        showIcon
        message={`Agent 正在运行${runtimeStep ? `:${runtimeStep}` : ''}`}
        action={
          <Button size="small" danger loading={cancelling} onClick={onCancel}>
            ✕ 取消
          </Button>
        }
        style={{ margin: '8px 12px 0' }}
      />
    );
  }
  if (runtimeStatus === 'idle' && runtimeStep === 'cancelled') {
    return <Alert type="warning" showIcon message="已取消" style={{ margin: '8px 12px 0' }} closable />;
  }
  if (runtimeStatus === 'waiting_user') {
    return <Alert type="warning" showIcon message="Agent 正在等你回答" style={{ margin: '8px 12px 0' }} />;
  }
  if (runtimeStatus === 'error') {
    return (
      <Alert
        type="error"
        showIcon
        message={`出错了${runtimeError ? `:${runtimeError}` : ''}`}
        style={{ margin: '8px 12px 0' }}
      />
    );
  }
  return null;
};

export default RuntimeBanner;
