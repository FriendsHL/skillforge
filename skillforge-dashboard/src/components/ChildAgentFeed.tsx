import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Tag, Typography, Button, Empty, Spin } from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExportOutlined,
  LoadingOutlined,
  UserOutlined,
  RobotOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import { getSessionMessages, extractList } from '../api';

const { Text } = Typography;

interface Props {
  sessionId: string;
  isRunning: boolean;
}

interface ParsedMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  toolCalls?: {
    name: string;
    status: 'success' | 'error' | 'running';
    output?: string;
  }[];
}

/** Parse raw API messages into a compact display format. */
function parseMessages(rawMessages: any[]): ParsedMessage[] {
  const result: ParsedMessage[] = [];
  for (let i = 0; i < rawMessages.length; i++) {
    const msg = rawMessages[i];
    const role: 'user' | 'assistant' = msg.role === 'user' ? 'user' : 'assistant';

    // Extract text content
    let text = '';
    if (typeof msg.content === 'string') {
      text = msg.content;
    } else if (Array.isArray(msg.content)) {
      const textParts = msg.content.filter((b: any) => b.type === 'text');
      text = textParts.map((b: any) => b.text ?? '').join('\n');
    }

    // Extract tool calls from assistant messages
    const toolCalls: ParsedMessage['toolCalls'] = [];
    if (role === 'assistant' && Array.isArray(msg.content)) {
      const toolUseBlocks = msg.content.filter((b: any) => b.type === 'tool_use');
      for (const tu of toolUseBlocks) {
        // Look ahead for matching tool_result
        let status: 'success' | 'error' | 'running' = 'running';
        let output = '';
        for (let j = i + 1; j < rawMessages.length; j++) {
          const nextMsg = rawMessages[j];
          if (Array.isArray(nextMsg?.content)) {
            const result = nextMsg.content.find(
              (b: any) => b.type === 'tool_result' && b.tool_use_id === tu.id
            );
            if (result) {
              status = result.is_error ? 'error' : 'success';
              output = typeof result.content === 'string'
                ? result.content
                : JSON.stringify(result.content ?? '');
              break;
            }
          }
        }
        toolCalls.push({ name: tu.name, status, output });
      }
    }

    // Also handle toolCalls array style (from ChatWindow's ChatMessage format)
    if (role === 'assistant' && Array.isArray(msg.toolCalls)) {
      for (const tc of msg.toolCalls) {
        toolCalls.push({
          name: tc.name ?? tc.toolName ?? 'unknown',
          status: tc.error ? 'error' : 'success',
          output: tc.output ?? tc.result ?? '',
        });
      }
    }

    // Skip empty assistant messages with no tool calls
    if (!text && toolCalls.length === 0) continue;

    result.push({
      id: msg.id ?? `msg-${i}`,
      role,
      text,
      toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
    });
  }
  return result;
}

function truncate(s: string, max: number): string {
  if (!s) return '';
  const clean = s.replace(/\n/g, ' ').trim();
  return clean.length > max ? clean.slice(0, max) + '...' : clean;
}

const statusIcon = (status: 'success' | 'error' | 'running') => {
  switch (status) {
    case 'success':
      return <CheckCircleOutlined style={{ color: 'var(--color-success)', fontSize: 12 }} />;
    case 'error':
      return <CloseCircleOutlined style={{ color: 'var(--color-error)', fontSize: 12 }} />;
    case 'running':
      return <LoadingOutlined style={{ color: 'var(--color-info)', fontSize: 12 }} spin />;
  }
};

const ChildAgentFeed: React.FC<Props> = ({ sessionId, isRunning }) => {
  const [messages, setMessages] = useState<ParsedMessage[]>([]);
  const [loading, setLoading] = useState(true);
  const scrollRef = useRef<HTMLDivElement>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchMessages = useCallback(async () => {
    try {
      const res = await getSessionMessages(sessionId, 1);
      const list = extractList<any>(res);
      const raw: any[] = list.length > 0 ? list : (res.data as any)?.messages ?? [];
      const parsed = parseMessages(raw);
      // Keep last 8 messages
      setMessages(parsed.slice(-8));
    } catch {
      // non-critical
    } finally {
      setLoading(false);
    }
  }, [sessionId]);

  // Initial fetch
  useEffect(() => {
    setLoading(true);
    setMessages([]);
    fetchMessages();
  }, [fetchMessages]);

  // Poll every 3s while running
  useEffect(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    if (isRunning) {
      pollRef.current = setInterval(fetchMessages, 3000);
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [isRunning, fetchMessages]);

  // Auto-scroll to bottom
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages]);

  const openInNewTab = () => {
    window.open(`/chat/${sessionId}`, '_blank', 'noopener,noreferrer');
  };

  if (loading) {
    return (
      <div style={{ padding: 16, textAlign: 'center' }}>
        <Spin size="small" />
      </div>
    );
  }

  return (
    <div style={{ padding: '8px 12px' }}>
      {/* Header with "Open in new tab" */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 8,
        }}
      >
        <Text type="secondary" style={{ fontSize: 11 }}>
          Recent activity ({messages.length} messages)
        </Text>
        <Button
          type="link"
          size="small"
          icon={<ExportOutlined />}
          onClick={openInNewTab}
          style={{ fontSize: 12, padding: 0, height: 'auto' }}
        >
          Open full session
        </Button>
      </div>

      {/* Messages feed */}
      <div
        ref={scrollRef}
        style={{
          maxHeight: 300,
          overflowY: 'auto',
          border: '1px solid var(--border-subtle)',
          borderRadius: 6,
          background: 'var(--bg-hover)',
        }}
      >
        {messages.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="No messages yet"
            style={{ padding: 16 }}
          />
        ) : (
          messages.map((msg) => (
            <div
              key={msg.id}
              style={{
                padding: '6px 10px',
                borderBottom: '1px solid var(--border-subtle)',
                fontSize: 12,
              }}
            >
              {/* Role indicator + text */}
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6 }}>
                {msg.role === 'user' ? (
                  <UserOutlined style={{ color: 'var(--color-info)', marginTop: 2, flexShrink: 0 }} />
                ) : (
                  <RobotOutlined style={{ color: 'var(--accent-primary)', marginTop: 2, flexShrink: 0 }} />
                )}
                <div style={{ flex: 1, minWidth: 0 }}>
                  {msg.text && (
                    <div
                      style={{
                        background: msg.role === 'user' ? 'var(--accent-muted)' : 'var(--bg-surface)',
                        padding: '4px 8px',
                        borderRadius: 4,
                        color: 'var(--text-primary)',
                        lineHeight: 1.5,
                        wordBreak: 'break-word',
                      }}
                    >
                      {truncate(msg.text, msg.role === 'user' ? 150 : 200)}
                    </div>
                  )}
                  {/* Tool calls */}
                  {msg.toolCalls?.map((tc, tcIdx) => (
                    <div
                      key={tcIdx}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 6,
                        marginTop: 4,
                        padding: '3px 8px',
                        background: 'var(--bg-hover)',
                        border: '1px solid var(--border-subtle)',
                        borderRadius: 4,
                      }}
                    >
                      <ToolOutlined style={{ color: 'var(--text-secondary)', fontSize: 11 }} />
                      <Tag
                        color="green"
                        style={{ margin: 0, fontSize: 11, lineHeight: '18px' }}
                      >
                        {tc.name}
                      </Tag>
                      {statusIcon(tc.status)}
                      {tc.output && (
                        <Text
                          type="secondary"
                          style={{
                            fontSize: 11,
                            flex: 1,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                          }}
                        >
                          {truncate(tc.output, 100)}
                        </Text>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};

export default ChildAgentFeed;
