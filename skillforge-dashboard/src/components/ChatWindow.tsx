import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Spin, Input, Button, Tag } from 'antd';
import { SendOutlined, LoadingOutlined } from '@ant-design/icons';
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
  return (
    <div style={{ padding: '12px 16px', borderTop: '1px solid #f0f0f0', display: 'flex', gap: 8 }}>
      <Input
        placeholder="Type your message..."
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onPressEnter={handleSend}
        disabled={disabled}
      />
      <Button
        type="primary"
        icon={<SendOutlined />}
        onClick={handleSend}
        disabled={disabled}
      >
        Send
      </Button>
    </div>
  );
});

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  toolCalls?: any[];
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
}

const ChatWindow: React.FC<ChatWindowProps> = ({
  messages,
  loading,
  onSend,
  inputDisabled,
  inflightTools,
  streamingText,
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

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, loading, streamingText, inflightTools]);

  const inflightList = inflightTools ? Object.entries(inflightTools) : [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
        {messages.map((msg, idx) => (
          <div
            key={idx}
            style={{
              display: 'flex',
              justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start',
              marginBottom: 12,
            }}
          >
            <div
              style={{
                maxWidth: msg.role === 'user' ? '70%' : '85%',
                padding: msg.role === 'user' ? '10px 16px' : '12px 18px',
                borderRadius: 12,
                background: msg.role === 'user' ? '#1677ff' : '#ffffff',
                color: msg.role === 'user' ? '#fff' : '#000',
                border: msg.role === 'assistant' ? '1px solid #e8e8e8' : 'none',
                boxShadow: msg.role === 'assistant' ? '0 1px 2px rgba(0,0,0,0.04)' : 'none',
                whiteSpace: msg.role === 'user' ? 'pre-wrap' : 'normal',
                wordBreak: 'break-word',
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
          </div>
        ))}
        {streamingText && streamingText.length > 0 && (
          <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 12 }}>
            <div
              style={{
                maxWidth: '85%',
                padding: '12px 18px',
                borderRadius: 12,
                background: '#fafafa',
                color: '#000',
                border: '1px dashed #d9d9d9',
                wordBreak: 'break-word',
              }}
            >
              <ThrottledMarkdown content={streamingText} />
              <span style={{ color: '#999', fontSize: 12, marginLeft: 4 }}>▍</span>
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
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                margin: '0 0 12px 0',
                padding: '10px 14px',
                borderRadius: 8,
                background: '#f6ffed',
                border: '1px solid #b7eb8f',
                fontSize: 13,
                color: '#389e0d',
              }}
            >
              <LoadingOutlined spin />
              <Tag color="green" style={{ marginRight: 0 }}>{info.name}</Tag>
              <span style={{ color: '#666', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {inputPreview}
              </span>
              <span style={{ color: '#999' }}>{elapsed}s</span>
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
      <ChatInput disabled={inputDisabled} onSend={stableOnSend} />
    </div>
  );
};

export default ChatWindow;
