import React from 'react';
import { Card, Space, Button, Input } from 'antd';

interface PendingAskOption {
  label: string;
  description?: string;
}
interface PendingAsk {
  askId: string;
  question: string;
  context?: string;
  options: PendingAskOption[];
  allowOther: boolean;
}

interface PendingAskCardProps {
  pendingAsk: PendingAsk;
  otherInput: string;
  onOtherInputChange: (v: string) => void;
  onAnswer: (answer: string) => void;
}

const PendingAskCard: React.FC<PendingAskCardProps> = ({
  pendingAsk,
  otherInput,
  onOtherInputChange,
  onAnswer,
}) => (
  <Card size="small" title="💬 Agent 在问你" style={{ margin: '8px 12px 0', borderColor: 'var(--color-warning)' }}>
    {pendingAsk.context && (
      <div style={{ color: 'var(--text-muted)', fontSize: 12, marginBottom: 8 }}>{pendingAsk.context}</div>
    )}
    <div style={{ fontWeight: 500, marginBottom: 12 }}>{pendingAsk.question}</div>
    <Space direction="vertical" style={{ width: '100%' }}>
      {pendingAsk.options.map((opt, i) => (
        <Button
          key={i}
          block
          style={{ textAlign: 'left', height: 'auto', padding: '8px 12px' }}
          onClick={() => onAnswer(opt.label)}
        >
          <div style={{ fontWeight: 500 }}>{opt.label}</div>
          {opt.description && <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{opt.description}</div>}
        </Button>
      ))}
      {pendingAsk.allowOther && (
        <Space.Compact style={{ width: '100%', marginTop: 4 }}>
          <Input
            placeholder="或自己输入答复..."
            value={otherInput}
            onChange={(e) => onOtherInputChange(e.target.value)}
            onPressEnter={() => otherInput.trim() && onAnswer(otherInput.trim())}
          />
          <Button type="primary" disabled={!otherInput.trim()} onClick={() => onAnswer(otherInput.trim())}>
            发送
          </Button>
        </Space.Compact>
      )}
    </Space>
  </Card>
);

export default PendingAskCard;
