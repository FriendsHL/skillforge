import React from 'react';
import { Timeline, Collapse } from 'antd';
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
      style={{ marginTop: 6, marginBottom: 0, fontSize: 11 }}
      items={toolCalls.map((tc) => {
        const collapseItems = [];
        if (tc.input != null) {
          collapseItems.push({
            key: 'input',
            label: <span style={{ fontSize: 10, color: '#888' }}>Input</span>,
            children: (
              <pre style={{ fontSize: 10, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: 1.35 }}>
                {JSON.stringify(tc.input, null, 2)}
              </pre>
            ),
          });
        }
        if (tc.output != null) {
          collapseItems.push({
            key: 'output',
            label: <span style={{ fontSize: 10, color: '#888' }}>Output</span>,
            children: (
              <div style={{ maxHeight: 120, overflowY: 'auto' }}>
                <pre style={{ fontSize: 10, margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: 1.35 }}>
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
            <div style={{ marginTop: -6, paddingBottom: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4, flexWrap: 'wrap', lineHeight: 1.3 }}>
                <ToolOutlined style={{ fontSize: 10, color: '#999' }} />
                <span style={{ fontSize: 11, fontWeight: 500, color: '#555' }}>{tc.name}</span>
                {tc.duration != null && (
                  <span style={{ fontSize: 9, color: '#999', marginLeft: 2 }}>
                    {formatDuration(tc.duration)}
                  </span>
                )}
              </div>
              {collapseItems.length > 0 && (
                <Collapse
                  size="small"
                  ghost
                  className="sf-tool-collapse"
                  style={{ marginTop: 0, marginLeft: -16 }}
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
