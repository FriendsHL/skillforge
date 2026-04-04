import React from 'react';
import { Timeline, Tag, Collapse } from 'antd';
import { ToolOutlined } from '@ant-design/icons';

export interface ToolCall {
  name: string;
  input?: Record<string, any>;
  output?: string;
  duration?: number; // 毫秒
  status?: 'success' | 'error';
  id?: string;
}

export interface ToolCallTimelineProps {
  toolCalls: ToolCall[];
}

function formatDuration(ms: number): string {
  if (ms >= 1000) {
    return `${(ms / 1000).toFixed(1)}s`;
  }
  return `${ms}ms`;
}

const ToolCallTimeline: React.FC<ToolCallTimelineProps> = ({ toolCalls }) => {
  if (!toolCalls || toolCalls.length === 0) {
    return null;
  }

  return (
    <Timeline
      style={{ marginTop: 12, marginBottom: 0 }}
      items={toolCalls.map((tc) => {
        const collapseItems = [];
        if (tc.input != null) {
          collapseItems.push({
            key: 'input',
            label: 'Input',
            children: (
              <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                {JSON.stringify(tc.input, null, 2)}
              </pre>
            ),
          });
        }
        if (tc.output != null) {
          collapseItems.push({
            key: 'output',
            label: 'Output',
            children: (
              <div style={{ maxHeight: 200, overflowY: 'auto' }}>
                <pre style={{ fontSize: 12, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                  {tc.output}
                </pre>
              </div>
            ),
          });
        }

        return {
          key: tc.id ?? tc.name,
          color: tc.status === 'error' ? 'red' : 'green',
          children: (
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <span>
                  <ToolOutlined style={{ marginRight: 4 }} />
                  {tc.name}
                </span>
                {tc.duration != null && (
                  <Tag color="blue">{formatDuration(tc.duration)}</Tag>
                )}
              </div>
              {collapseItems.length > 0 && (
                <Collapse
                  size="small"
                  style={{ marginTop: 6 }}
                  items={collapseItems}
                />
              )}
            </div>
          ),
        };
      })}
    />
  );
};

export default ToolCallTimeline;
