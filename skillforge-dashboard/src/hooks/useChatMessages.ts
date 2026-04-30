import { useEffect, useMemo, useState } from 'react';
import type { ChatMessage } from '../components/ChatWindow';

export interface InflightTool {
  name: string;
  input: unknown;
  startTs: number;
}

export interface StreamingToolInput {
  name: string;
  jsonBuffer: string;
  startTs: number;
}

/**
 * 归一化后端返回的消息列表:
 * Agent Loop 产生的历史消息里 tool_result 以 role=user 发回,会变成空文本的用户气泡。
 * 这里把这类消息过滤掉,并把 tool_result 按 tool_use_id 合并到上一条 assistant 的 toolCalls。
 */
export function normalizeMessages(list: any[]): ChatMessage[] {
  const result: ChatMessage[] = [];
  const extractBlocks = (content: any) => {
    let text = '';
    const toolUseBlocks: any[] = [];
    const toolResultBlocks: any[] = [];
    if (typeof content === 'string') {
      text = content;
    } else if (Array.isArray(content)) {
      for (const b of content) {
        if (!b || typeof b !== 'object') continue;
        if (b.type === 'text' && b.text) {
          text += (text ? '\n' : '') + b.text;
        } else if (b.type === 'tool_use') {
          toolUseBlocks.push(b);
        } else if (b.type === 'tool_result') {
          toolResultBlocks.push(b);
        }
      }
    }
    return { text, toolUseBlocks, toolResultBlocks };
  };

  for (const m of list) {
    const msgType = typeof m.msgType === 'string' ? m.msgType.toUpperCase() : '';
    const messageType = typeof m.messageType === 'string' ? m.messageType : 'normal';
    const { text, toolUseBlocks, toolResultBlocks } = extractBlocks(m.content);

    if (msgType === 'COMPACT_BOUNDARY') {
      // Boundary is a structural marker for context slicing, not a user-visible card.
      continue;
    }

    if (messageType === 'ask_user' || messageType === 'confirmation') {
      const metadata = m.metadata && typeof m.metadata === 'object'
        ? (m.metadata as Record<string, unknown>)
        : {};
      result.push({
        role: 'assistant',
        content: text,
        messageType,
        controlId: typeof m.controlId === 'string' ? m.controlId : undefined,
        answeredAt: typeof m.answeredAt === 'string' ? m.answeredAt : undefined,
        metadata,
      });
      continue;
    }

    if (msgType === 'SUMMARY') {
      const summaryText = text.trim();
      if (summaryText) {
        const compactedCount =
          m.metadata && typeof m.metadata === 'object' && 'compacted_message_count' in m.metadata
            ? Number((m.metadata as Record<string, unknown>).compacted_message_count)
            : undefined;
        const header = Number.isFinite(compactedCount)
          ? `**${compactedCount} earlier messages were compacted**\n\n`
          : '';
        result.push({ role: 'summary', content: `${header}${summaryText}`.trim() });
      }
      continue;
    }

    if (m.role === 'user') {
      if (toolResultBlocks.length > 0 && result.length > 0) {
        const prev = result[result.length - 1];
        if (prev.role === 'assistant' && Array.isArray(prev.toolCalls)) {
          for (const tr of toolResultBlocks) {
            const id = tr.tool_use_id ?? tr.toolUseId ?? tr.id;
            const match = prev.toolCalls.find((tc: any) => tc.id === id) as any;
            const outputText =
              typeof tr.content === 'string'
                ? tr.content
                : Array.isArray(tr.content)
                  ? tr.content.map((c: any) => c?.text ?? JSON.stringify(c)).join('\n')
                  : JSON.stringify(tr.content ?? '');
            if (match) {
              // Immutable update — avoids mutating objects that may be shared
              // with rawMessages if the fallback `m.toolCalls` path was used.
              const idx = prev.toolCalls.indexOf(match);
              prev.toolCalls = [
                ...prev.toolCalls.slice(0, idx),
                { ...match, output: outputText, status: tr.is_error || tr.isError ? 'error' : 'success' },
                ...prev.toolCalls.slice(idx + 1),
              ];
            } else {
              prev.toolCalls.push({
                id,
                name: 'tool',
                output: outputText,
                status: tr.is_error || tr.isError ? 'error' : 'success',
              });
            }
          }
        }
      }
      if (!text.trim()) continue;

      // Compaction 压缩摘要注入为 user 消息：
      //   独立形态: "[Context summary from N messages...]\n...summary..." — 跳过不显示
      //   合并形态: "[Context summary from N messages...]\n\n---\n\noriginal user text" — 只显示原始用户文本
      let displayText = text;
      if (text.startsWith('[Context summary from ')) {
        const sep = '\n\n---\n\n';
        const sepIdx = text.indexOf(sep);
        if (sepIdx === -1) {
          // 独立摘要消息：提取压缩条数 + 摘要内容
          const countMatch = text.match(/^\[Context summary from (\d+) messages/);
          const compactedCount = countMatch ? parseInt(countMatch[1], 10) : null;
          const firstNewline = text.indexOf('\n');
          const summaryContent = firstNewline !== -1 ? text.slice(firstNewline + 1).trim() : '';
          const displayContent = compactedCount != null
            ? `**${compactedCount} earlier messages were compacted**\n\n${summaryContent}`
            : summaryContent;
          if (displayContent.trim()) {
            result.push({ role: 'summary', content: displayContent });
          }
          continue;
        }
        // 合并消息，取分隔符之后的原始用户文本
        displayText = text.slice(sepIdx + sep.length).trim();
        if (!displayText) continue;
      }

      result.push({ role: 'user', content: displayText });
    } else if (m.role === 'assistant') {
      const toolCalls = toolUseBlocks.map((b: any) => ({
        id: b.id,
        name: b.name,
        input: b.input,
      }));
      if (!text.trim() && toolCalls.length === 0) continue;
      result.push({
        role: 'assistant',
        content: text,
        toolCalls: toolCalls.length > 0 ? toolCalls : m.toolCalls,
      });
    }
  }
  return result;
}

export function useChatMessages(activeSessionId: string | undefined) {
  const [rawMessages, setRawMessages] = useState<unknown[]>([]);
  const [streamingText, setStreamingText] = useState<string>('');
  const [streamingToolInputs, setStreamingToolInputs] = useState<Record<string, StreamingToolInput>>({});
  const [inflightTools, setInflightTools] = useState<Record<string, InflightTool>>({});

  const messages = useMemo(() => normalizeMessages(rawMessages as any[]), [rawMessages]);

  useEffect(() => {
    setRawMessages([]);
    setStreamingText('');
    setStreamingToolInputs({});
    setInflightTools({});
  }, [activeSessionId]);

  return {
    rawMessages,
    setRawMessages,
    messages,
    streamingText,
    setStreamingText,
    streamingToolInputs,
    setStreamingToolInputs,
    inflightTools,
    setInflightTools,
  };
}
