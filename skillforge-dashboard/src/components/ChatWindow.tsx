import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Spin, Input, Button, Alert } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import ToolCallTimeline from './ToolCallTimeline';
import MarkdownRenderer from './MarkdownRenderer';

/**
 * 节流版 MarkdownRenderer：每 200ms 刷新一次渲染，
 * 避免高频 delta 触发昂贵的 markdown 解析 + Prism 高亮。
 */
const ThrottledMarkdown: React.FC<{ content: string }> = ({ content }) => {
  const [rendered, setRendered] = useState(content);
  const latestRef = useRef(content);
  latestRef.current = content;

  useEffect(() => {
    // 定时刷新：每 200ms 把最新内容同步到渲染状态
    const interval = setInterval(() => {
      setRendered(latestRef.current);
    }, 200);
    return () => clearInterval(interval);
  }, []);

  // content 首次有值时立即渲染（不等 200ms）
  useEffect(() => {
    if (content && !rendered) {
      setRendered(content);
    }
  }, [content, rendered]);

  return <MarkdownRenderer content={rendered} />;
};

/**
 * 输入框作为独立组件:自己持有 input state。
 * 这样打字时只有 ChatInput 自己重渲染,不会触发父级 ChatWindow 重新跑
 * messages.map → MarkdownRenderer,从根本上消除"打字卡顿"。
 */
interface ChatInputProps {
  disabled?: boolean;
  onSend: (text: string) => void;
}
const ChatInput: React.FC<ChatInputProps> = React.memo(({ disabled, onSend }) => {
  const [input, setInput] = useState('');
  const handleSend = () => {
    const text = input.trim();
    if (!text) return;
    setInput('');
    onSend(text);
  };
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Enter 发送，Shift+Enter 换行
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };
  return (
    <div style={{ background: 'var(--bg-primary)', padding: '12px 16px 20px' }}>
      <div
        className="sf-chat-input"
        style={{
          display: 'flex',
          alignItems: 'flex-end',
          padding: '4px 4px 4px 16px',
          gap: 8,
        }}
      >
        <Input.TextArea
          placeholder="Type your message... (Shift+Enter for newline)"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          variant="borderless"
          autoSize={{ minRows: 1, maxRows: 6 }}
          style={{ flex: 1, resize: 'none' }}
        />
        <Button
          className="sf-send-btn"
          type="primary"
          icon={<SendOutlined />}
          onClick={handleSend}
          disabled={disabled || !input.trim()}
          style={{ width: 32, height: 32, minWidth: 32, marginBottom: 4, borderRadius: 'var(--radius-sm)' }}
        />
      </div>
    </div>
  );
});

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  toolCalls?: any[];
  timestamp?: string;
}

interface InflightTool {
  name: string;
  input: any;
  startTs: number;
}

interface ChatWindowProps {
  messages: ChatMessage[];
  loading: boolean;
  onSend: (text: string) => void;
  inputDisabled?: boolean;
  inflightTools?: Record<string, InflightTool>;
  streamingText?: string;
  compactionNotice?: boolean;
  onCompactionDismiss?: () => void;
}

const ChatWindow: React.FC<ChatWindowProps> = ({
  messages,
  loading,
  onSend,
  inputDisabled,
  inflightTools,
  streamingText,
  compactionNotice,
  onCompactionDismiss,
}) => {
  const bottomRef = useRef<HTMLDivElement>(null);

  // 用 ref 透传最新的 onSend / disabled,这样 ChatInput 拿到的 props 引用稳定,
  // React.memo 才能真正生效。
  const onSendRef = useRef(onSend);
  useEffect(() => { onSendRef.current = onSend; }, [onSend]);
  const stableOnSend = useCallback((text: string) => onSendRef.current(text), []);

  // 强制每秒重渲染一次,用于刷新 inflight tool 的"已耗时 N s"
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!inflightTools || Object.keys(inflightTools).length === 0) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [inflightTools]);

  // Smart scroll: only auto-scroll if user is near bottom (within 100px)
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const isNearBottom = useRef(true);
  const handleScroll = useCallback(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    isNearBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
  }, []);
  useEffect(() => {
    if (isNearBottom.current) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages, streamingText]);

  const inflightList = inflightTools ? Object.entries(inflightTools) : [];

  const formatTime = (date: Date) => {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div ref={scrollContainerRef} onScroll={handleScroll} style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
        <div style={{ maxWidth: 740, margin: '0 auto', padding: '0 16px' }}>
          {compactionNotice && (
            <div role="status">
              <Alert
                type="info"
                closable
                message="Context was compacted to save tokens. Earlier messages may be summarized."
                afterClose={onCompactionDismiss}
                style={{ marginBottom: 12 }}
              />
            </div>
          )}
          {messages.map((msg, idx) => (
            <div key={idx} style={{ marginBottom: 16 }}>
              {msg.role === 'assistant' && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
                  <div
                    style={{
                      width: 20,
                      height: 20,
                      borderRadius: '50%',
                      background: 'var(--accent-primary)',
                      color: 'var(--text-on-accent)',
                      fontSize: 9,
                      fontWeight: 700,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    SF
                  </div>
                  <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>Assistant</span>
                </div>
              )}
              <div
                style={{
                  ...(msg.role === 'user'
                    ? {
                        maxWidth: '68%',
                        marginLeft: 'auto',
                        background: 'var(--bg-user-msg)',
                        border: '1px solid var(--border-subtle)',
                        borderRadius: '18px 18px 4px 18px',
                        padding: '10px 14px',
                        color: 'var(--text-primary)',
                        fontSize: 15,
                        whiteSpace: 'pre-wrap' as const,
                        wordBreak: 'break-word' as const,
                      }
                    : {
                        fontSize: 15,
                        lineHeight: 1.7,
                        wordBreak: 'break-word' as const,
                      }),
                }}
              >
                {msg.role === 'assistant' ? (
                  <MarkdownRenderer content={msg.content} />
                ) : (
                  msg.content
                )}
                {msg.role === 'assistant' && msg.toolCalls && msg.toolCalls.length > 0 && (
                  <ToolCallTimeline toolCalls={msg.toolCalls} />
                )}
              </div>
              <div
                style={{
                  fontSize: 11,
                  color: 'var(--text-muted)',
                  marginTop: 3,
                  textAlign: msg.role === 'user' ? 'right' : 'left',
                }}
              >
                {msg.timestamp ? formatTime(new Date(msg.timestamp)) : ''}
              </div>
            </div>
          ))}
          {streamingText && streamingText.length > 0 && (
            <div style={{ marginBottom: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
                <div
                  style={{
                    width: 28,
                    height: 28,
                    borderRadius: '50%',
                    background: 'var(--accent-primary)',
                    color: 'var(--text-on-accent)',
                    fontSize: 11,
                    fontWeight: 700,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  SF
                </div>
                <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>Assistant</span>
              </div>
              <div style={{ fontSize: 15, lineHeight: 1.7, wordBreak: 'break-word' }}>
                <ThrottledMarkdown content={streamingText} />
                <span style={{ color: 'var(--text-muted)', fontSize: 12, marginLeft: 4 }}>▍</span>
              </div>
            </div>
          )}
          {inflightList.map(([toolUseId, info]) => {
            const elapsed = Math.max(0, Math.floor((Date.now() - info.startTs) / 1000));
            let inputPreview = '';
            try {
              inputPreview = typeof info.input === 'string' ? info.input : JSON.stringify(info.input);
            } catch {
              inputPreview = '';
            }
            if (inputPreview.length > 120) inputPreview = inputPreview.slice(0, 120) + '...';
            return (
              <div
                key={toolUseId}
                className="sf-tool-row"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  margin: '0 0 12px 0',
                  padding: '10px 14px',
                  borderRadius: 'var(--radius-md)',
                  background: 'var(--bg-assistant-structured)',
                  border: '1px solid var(--border-subtle)',
                  fontSize: 13,
                  color: 'var(--text-secondary)',
                }}
              >
                <Spin size="small" />
                <span style={{ fontWeight: 500, color: 'var(--text-primary)' }}>{info.name}</span>
                <span style={{ color: 'var(--text-muted)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {inputPreview}
                </span>
                <span style={{ color: 'var(--text-muted)' }}>{elapsed}s</span>
              </div>
            );
          })}
          {loading && !streamingText && inflightList.length === 0 && (
            <div style={{ textAlign: 'center', padding: 16 }}>
              <Spin tip="AI is thinking..." />
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      </div>
      <ChatInput disabled={inputDisabled} onSend={stableOnSend} />
    </div>
  );
};

export default ChatWindow;
