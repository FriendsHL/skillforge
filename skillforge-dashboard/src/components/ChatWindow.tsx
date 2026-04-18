import React, { useCallback, useEffect, useRef, useState } from 'react';
import { type ToolCall } from './ToolCallTimeline';
import MarkdownRenderer from './MarkdownRenderer';
import { RoleAvatar } from './chat/primitives';
import {
  IconAttach,
  IconCheck,
  IconCompact,
  IconMic,
  IconSend,
  IconTool,
  IconX,
} from './chat/ChatIcons';

const formatTime = (date: Date): string =>
  date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

const ThrottledMarkdown: React.FC<{ content: string }> = ({ content }) => {
  const [rendered, setRendered] = useState(content);
  const latestRef = useRef(content);
  latestRef.current = content;

  useEffect(() => {
    const interval = setInterval(() => setRendered(latestRef.current), 200);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (content && !rendered) setRendered(content);
  }, [content, rendered]);

  return <MarkdownRenderer content={rendered} />;
};

interface ChatInputProps {
  disabled?: boolean;
  onSend: (text: string) => void;
}
const ChatInput: React.FC<ChatInputProps> = React.memo(({ disabled, onSend }) => {
  const [input, setInput] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const autosize = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, []);

  useEffect(() => {
    autosize();
  }, [input, autosize]);

  const handleSend = () => {
    const text = input.trim();
    if (!text) return;
    setInput('');
    onSend(text);
  };
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="composer-wrap">
      <div className="composer">
        <div className="comp-left-tools">
          <button type="button" className="comp-btn" title="Attach" aria-label="Attach">
            <IconAttach s={14} />
          </button>
        </div>
        <textarea
          ref={textareaRef}
          placeholder="Message the agent… (Shift+Enter for newline)"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          rows={1}
        />
        <button type="button" className="comp-btn" title="Voice" aria-label="Voice">
          <IconMic s={14} />
        </button>
        <button
          type="button"
          className="send-btn"
          onClick={handleSend}
          disabled={disabled || !input.trim()}
          aria-label="Send"
        >
          <IconSend s={15} />
        </button>
      </div>
      <div className="comp-foot">
        <div className="tokens">
          <span>⌘ SkillForge</span>
        </div>
        <div>
          <span className="kbd">↵</span> send &nbsp;
          <span className="kbd">⇧↵</span> newline
        </div>
      </div>
    </div>
  );
});
ChatInput.displayName = 'ChatInput';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  toolCalls?: ToolCall[];
  timestamp?: string;
  id?: string;
}

interface InflightTool {
  name: string;
  input: unknown;
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
  runtimeStatus?: string;
  agentName?: string;
}

interface ToolCallRowProps {
  tc: ToolCall;
}
const ToolCallRow: React.FC<ToolCallRowProps> = ({ tc }) => {
  const [open, setOpen] = useState(false);
  const ok = tc.status !== 'error';
  const inputText =
    typeof tc.input === 'string' ? tc.input : JSON.stringify(tc.input ?? {});
  const outputText = tc.output;
  const durMs = tc.duration;
  const durStr =
    typeof durMs === 'number'
      ? durMs >= 1000
        ? `${(durMs / 1000).toFixed(1)}s`
        : `${durMs}ms`
      : '';

  return (
    <>
      <button
        type="button"
        className="tool-row"
        onClick={() => setOpen((o) => !o)}
        style={{ border: 0, background: 'transparent', font: 'inherit', color: 'inherit', width: '100%' }}
      >
        <span className="tool-icon">
          <IconTool s={11} />
        </span>
        <span className="tool-name">{tc.name}</span>
        <span className="tool-input">{inputText}</span>
        {durStr && <span className="tool-dur">{durStr}</span>}
        {ok ? (
          <span className="tool-check"><IconCheck s={12} /></span>
        ) : (
          <span className="tool-err"><IconX s={12} /></span>
        )}
      </button>
      {open && (
        <div className="tool-expanded">
          <div className="tool-section">
            <div className="tool-section-label">input</div>
            <pre>{inputText}</pre>
          </div>
          {outputText && (
            <div className="tool-section">
              <div className="tool-section-label">
                output · {outputText.split('\n').length} lines
              </div>
              <pre>{outputText}</pre>
            </div>
          )}
        </div>
      )}
    </>
  );
};

const ChatWindow: React.FC<ChatWindowProps> = ({
  messages,
  loading,
  onSend,
  inputDisabled,
  inflightTools,
  streamingText,
  compactionNotice,
  onCompactionDismiss,
  agentName,
}) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const onSendRef = useRef(onSend);
  useEffect(() => { onSendRef.current = onSend; }, [onSend]);
  const stableOnSend = useCallback((text: string) => onSendRef.current(text), []);

  const [, setTick] = useState(0);
  useEffect(() => {
    if (!inflightTools || Object.keys(inflightTools).length === 0) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [inflightTools]);

  const isNearBottom = useRef(true);
  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    isNearBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
  }, []);
  useEffect(() => {
    if (isNearBottom.current) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages, streamingText]);

  const inflightList = inflightTools ? Object.entries(inflightTools) : [];
  const assistantName = agentName ?? 'Assistant';

  return (
    <>
      <div className="scroll" ref={scrollRef} onScroll={handleScroll}>
        <div className="thread">
          {compactionNotice && (
            <div className="compact-notice" role="status">
              <IconCompact s={12} />
              Context was compacted to save tokens. Earlier messages may be summarized.
              {onCompactionDismiss && (
                <button
                  type="button"
                  className="x-btn"
                  onClick={onCompactionDismiss}
                  aria-label="Dismiss"
                >
                  <IconX s={12} />
                </button>
              )}
            </div>
          )}
          {messages.map((msg, idx) => {
            const isUser = msg.role === 'user';
            return (
              <div key={msg.id ?? `${msg.role}-${idx}`} className={`msg ${msg.role}`}>
                <div className="msg-head">
                  <RoleAvatar role={isUser ? 'user' : 'assistant'} />
                  <span className="msg-name">{isUser ? 'You' : assistantName}</span>
                  <span className="msg-time">
                    {msg.timestamp ? formatTime(new Date(msg.timestamp)) : ''}
                  </span>
                </div>
                <div className="msg-body">
                  {isUser ? msg.content : <MarkdownRenderer content={msg.content} />}
                  {!isUser && msg.toolCalls && msg.toolCalls.length > 0 && (
                    <div className="tool-calls">
                      {msg.toolCalls.map((tc, i) => (
                        <ToolCallRow key={tc.id ?? i} tc={tc} />
                      ))}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
          {streamingText && streamingText.length > 0 && (
            <div className="msg assistant">
              <div className="msg-head">
                <RoleAvatar role="assistant" />
                <span className="msg-name">{assistantName}</span>
              </div>
              <div className="msg-body">
                <ThrottledMarkdown content={streamingText} />
                <span style={{ color: 'var(--fg-4)', fontSize: 12, marginLeft: 4 }}>▍</span>
              </div>
            </div>
          )}
          {inflightList.map(([toolUseId, info]) => {
            const elapsed = Math.max(0, Math.floor((Date.now() - info.startTs) / 1000));
            let inputPreview = '';
            try {
              inputPreview =
                typeof info.input === 'string' ? info.input : JSON.stringify(info.input);
            } catch {
              inputPreview = '';
            }
            if (inputPreview.length > 120) inputPreview = inputPreview.slice(0, 120) + '…';
            return (
              <div key={toolUseId} className="inflight">
                <span className="spinner" />
                <span className="inflight-name">{info.name}</span>
                <span className="inflight-input">{inputPreview}</span>
                <span className="inflight-time">{elapsed}s</span>
              </div>
            );
          })}
          {loading && !streamingText && inflightList.length === 0 && (
            <div style={{ textAlign: 'center', padding: 16, color: 'var(--fg-3)', fontSize: 13 }}>
              Agent is thinking…
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      </div>
      <ChatInput disabled={inputDisabled} onSend={stableOnSend} />
    </>
  );
};

export default ChatWindow;
