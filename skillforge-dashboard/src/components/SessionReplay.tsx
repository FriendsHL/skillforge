import React, { useEffect, useState } from 'react';
import { Collapse, Tag, Spin, Empty, Typography, Space, Tooltip } from 'antd';
import {
  ClockCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ToolOutlined,
  UserOutlined,
  RobotOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { getSessionReplay } from '../api';
import { useAuth } from '../contexts/AuthContext';

const { Text, Paragraph } = Typography;

interface ReplayToolCall {
  id?: string;
  name: string;
  input?: Record<string, any>;
  output?: string;
  success: boolean;
  durationMs?: number;
  timestamp?: number;
}

interface Iteration {
  iterationIndex: number;
  assistantText?: string;
  toolCalls: ReplayToolCall[];
}

interface Turn {
  turnIndex: number;
  userMessage: string;
  finalResponse?: string;
  iterationCount: number;
  inputTokens: number;
  outputTokens: number;
  modelId?: string;
  durationMs?: number;
  iterations: Iteration[];
}

interface ReplayData {
  sessionId: string;
  status: string;
  runtimeStatus: string;
  turns: Turn[];
}

function formatDuration(ms: number): string {
  if (ms >= 60000) {
    return `${(ms / 60000).toFixed(1)}m`;
  }
  if (ms >= 1000) {
    return `${(ms / 1000).toFixed(1)}s`;
  }
  return `${ms}ms`;
}

function formatTokens(input: number, output: number): string {
  const total = input + output;
  if (total >= 1000) {
    return `${(total / 1000).toFixed(1)}k tok`;
  }
  return `${total} tok`;
}

const ToolCallCard: React.FC<{ tc: ReplayToolCall }> = ({ tc }) => {
  const statusIcon = tc.success ? (
    <CheckCircleOutlined style={{ color: 'var(--color-success)', fontSize: 12 }} />
  ) : (
    <CloseCircleOutlined style={{ color: 'var(--color-error)', fontSize: 12 }} />
  );

  const items = [];
  if (tc.input != null) {
    items.push({
      key: 'input',
      label: <Text type="secondary" style={{ fontSize: 11 }}>Input</Text>,
      children: (
        <pre style={{ fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: 1.4, maxHeight: 200, overflowY: 'auto' }}>
          {typeof tc.input === 'string' ? tc.input : JSON.stringify(tc.input, null, 2)}
        </pre>
      ),
    });
  }
  if (tc.output != null) {
    items.push({
      key: 'output',
      label: <Text type="secondary" style={{ fontSize: 11 }}>Output</Text>,
      children: (
        <div style={{ maxHeight: 200, overflowY: 'auto' }}>
          <pre style={{ fontSize: 11, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: 1.4 }}>
            {tc.output}
          </pre>
        </div>
      ),
    });
  }

  return (
    <div style={{
      border: '1px solid var(--border-subtle)',
      borderRadius: 6,
      padding: '8px 12px',
      marginBottom: 6,
      background: tc.success ? 'var(--bg-hover)' : 'var(--color-error-bg)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: items.length > 0 ? 4 : 0 }}>
        <ToolOutlined style={{ fontSize: 12, color: 'var(--text-muted)' }} />
        <Text strong style={{ fontSize: 12 }}>{tc.name}</Text>
        {statusIcon}
        {tc.durationMs != null && (
          <Tooltip title={`${tc.durationMs}ms`}>
            <Tag
              icon={<ClockCircleOutlined />}
              color={tc.durationMs > 5000 ? 'orange' : 'default'}
              style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}
            >
              {formatDuration(tc.durationMs)}
            </Tag>
          </Tooltip>
        )}
      </div>
      {items.length > 0 && (
        <Collapse size="small" ghost items={items} style={{ marginLeft: -12, marginRight: -12 }} />
      )}
    </div>
  );
};

const IterationCard: React.FC<{ iter: Iteration }> = ({ iter }) => {
  const totalDuration = iter.toolCalls.reduce((sum, tc) => sum + (tc.durationMs ?? 0), 0);
  const allSuccess = iter.toolCalls.every((tc) => tc.success);
  const toolCount = iter.toolCalls.length;

  return (
    <div style={{
      borderLeft: `3px solid ${allSuccess ? 'var(--color-success)' : 'var(--color-error)'}`,
      paddingLeft: 12,
      marginBottom: 12,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <ThunderboltOutlined style={{ fontSize: 12, color: 'var(--color-info)' }} />
        <Text strong style={{ fontSize: 12 }}>
          Iteration {iter.iterationIndex + 1}
        </Text>
        <Tag style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>
          {toolCount} tool{toolCount !== 1 ? 's' : ''}
        </Tag>
        {totalDuration > 0 && (
          <Tag icon={<ClockCircleOutlined />} style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>
            {formatDuration(totalDuration)}
          </Tag>
        )}
        {!allSuccess && <Tag color="error" style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>has errors</Tag>}
      </div>
      {iter.assistantText && (
        <Paragraph
          type="secondary"
          style={{ fontSize: 12, marginBottom: 8, fontStyle: 'italic' }}
          ellipsis={{ rows: 2, expandable: true, symbol: 'more' }}
        >
          {iter.assistantText}
        </Paragraph>
      )}
      {iter.toolCalls.map((tc, i) => (
        <ToolCallCard key={tc.id ?? i} tc={tc} />
      ))}
    </div>
  );
};

const TurnCard: React.FC<{ turn: Turn }> = ({ turn }) => {
  const allSuccess = turn.iterations.every((it) =>
    it.toolCalls.every((tc) => tc.success)
  );

  const header = (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
      <UserOutlined style={{ color: 'var(--color-info)' }} />
      <Text strong>Turn {turn.turnIndex + 1}</Text>
      {turn.iterationCount > 0 && (
        <Tag color="blue" style={{ margin: 0 }}>
          {turn.iterationCount} iteration{turn.iterationCount !== 1 ? 's' : ''}
        </Tag>
      )}
      {turn.durationMs != null && turn.durationMs > 0 && (
        <Tag icon={<ClockCircleOutlined />} color={turn.durationMs > 30000 ? 'orange' : 'default'} style={{ margin: 0 }}>
          {formatDuration(turn.durationMs)}
        </Tag>
      )}
      <Tag style={{ margin: 0 }}>
        {formatTokens(turn.inputTokens, turn.outputTokens)}
      </Tag>
      {!allSuccess && <Tag color="error" style={{ margin: 0 }}>errors</Tag>}
    </div>
  );

  return (
    <Collapse
      defaultActiveKey={[]}
      style={{ marginBottom: 12 }}
      items={[{
        key: String(turn.turnIndex),
        label: header,
        children: (
          <div>
            {/* User message */}
            <div style={{
              background: 'var(--accent-muted)',
              borderRadius: 8,
              padding: '8px 12px',
              marginBottom: 12,
            }}>
              <Space size={4} style={{ marginBottom: 4 }}>
                <UserOutlined style={{ fontSize: 11, color: 'var(--color-info)' }} />
                <Text type="secondary" style={{ fontSize: 11 }}>User</Text>
              </Space>
              <Paragraph style={{ margin: 0, fontSize: 13, whiteSpace: 'pre-wrap' }}>
                {turn.userMessage}
              </Paragraph>
            </div>

            {/* Iterations */}
            {turn.iterations.map((iter) => (
              <IterationCard key={iter.iterationIndex} iter={iter} />
            ))}

            {/* Final response */}
            {turn.finalResponse && (
              <div style={{
                background: 'var(--bg-hover)',
                borderRadius: 8,
                padding: '8px 12px',
                marginTop: 8,
              }}>
                <Space size={4} style={{ marginBottom: 4 }}>
                  <RobotOutlined style={{ fontSize: 11, color: 'var(--color-success)' }} />
                  <Text type="secondary" style={{ fontSize: 11 }}>Final Response</Text>
                </Space>
                <Paragraph
                  style={{ margin: 0, fontSize: 13, whiteSpace: 'pre-wrap' }}
                  ellipsis={{ rows: 6, expandable: true, symbol: 'show more' }}
                >
                  {turn.finalResponse}
                </Paragraph>
              </div>
            )}

            {/* Token summary */}
            {(turn.inputTokens > 0 || turn.outputTokens > 0) && (
              <div style={{ marginTop: 8, fontSize: 11, color: 'var(--text-muted)' }}>
                {turn.modelId && <span>{turn.modelId} &middot; </span>}
                {turn.inputTokens.toLocaleString()} input / {turn.outputTokens.toLocaleString()} output tokens
              </div>
            )}
          </div>
        ),
      }]}
    />
  );
};

export interface SessionReplayProps {
  sessionId: string | undefined;
}

const SessionReplay: React.FC<SessionReplayProps> = ({ sessionId }) => {
  const { userId } = useAuth();
  const [data, setData] = useState<ReplayData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!sessionId) {
      setData(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    getSessionReplay(sessionId, userId)
      .then((res) => {
        if (!cancelled) setData(res.data);
      })
      .catch((e) => {
        if (!cancelled) setError(e?.response?.data?.error ?? 'Failed to load replay');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [sessionId]);

  if (!sessionId) {
    return <Empty description="Select a session to view replay" style={{ marginTop: 60 }} />;
  }

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 200 }}>
        <Spin tip="Loading replay..." />
      </div>
    );
  }

  if (error) {
    return <Empty description={error} style={{ marginTop: 60 }} />;
  }

  if (!data || !data.turns || data.turns.length === 0) {
    return <Empty description="No replay data" style={{ marginTop: 60 }} />;
  }

  // Summary stats
  const totalTurns = data.turns.length;
  const totalIterations = data.turns.reduce((s, t) => s + t.iterationCount, 0);
  const totalTools = data.turns.reduce(
    (s, t) => s + t.iterations.reduce((s2, it) => s2 + it.toolCalls.length, 0),
    0,
  );
  const totalDuration = data.turns.reduce((s, t) => s + (t.durationMs ?? 0), 0);
  const totalInput = data.turns.reduce((s, t) => s + t.inputTokens, 0);
  const totalOutput = data.turns.reduce((s, t) => s + t.outputTokens, 0);

  return (
    <div style={{ padding: '12px 16px', overflowY: 'auto', height: '100%' }}>
      {/* Summary bar */}
      <div style={{
        display: 'flex',
        gap: 12,
        flexWrap: 'wrap',
        marginBottom: 16,
        padding: '8px 12px',
        background: 'var(--bg-primary)',
        borderRadius: 8,
        fontSize: 12,
      }}>
        <span><strong>{totalTurns}</strong> turns</span>
        <span><strong>{totalIterations}</strong> iterations</span>
        <span><strong>{totalTools}</strong> tool calls</span>
        {totalDuration > 0 && (
          <span><ClockCircleOutlined /> <strong>{formatDuration(totalDuration)}</strong> total</span>
        )}
        <span>{formatTokens(totalInput, totalOutput)}</span>
      </div>

      {/* Turns */}
      {data.turns.map((turn) => (
        <TurnCard key={turn.turnIndex} turn={turn} />
      ))}
    </div>
  );
};

export default SessionReplay;
