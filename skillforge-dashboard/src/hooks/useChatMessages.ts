import { useEffect, useMemo, useState } from 'react';
import type { ChatAttachmentRef, ChatMessage } from '../components/ChatWindow';
import type { RawMessage, ContentBlock } from '../types/messages';

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

export interface LoopSpan {
  id: string;
  type: 'LLM_CALL' | 'TOOL_CALL';
  name: string;
  startTs: number;
  endTs?: number;
  status?: 'success' | 'error';
  durationMs?: number;
}

/**
 * 归一化后端返回的消息列表:
 * Agent Loop 产生的历史消息里 tool_result 以 role=user 发回,会变成空文本的用户气泡。
 * 这里把这类消息过滤掉,并把 tool_result 按 tool_use_id 合并到上一条 assistant 的 toolCalls。
 *
 * **类型契约**：input `RawMessage[]`（pre-normalize，content 可为 string | ContentBlock[]）
 * → output `ChatMessage[]`（post-normalize, content 已 collapse 成 string）。修 drift 后
 * `as any[]` cast 全删，以前类型隐式不诚实。详见 `src/types/messages.ts` 注释。
 */
export function normalizeMessages(list: RawMessage[]): ChatMessage[] {
  const result: ChatMessage[] = [];
  const extractBlocks = (content: RawMessage['content']) => {
    let text = '';
    const toolUseBlocks: ContentBlock[] = [];
    const toolResultBlocks: ContentBlock[] = [];
    const attachmentRefs: ChatAttachmentRef[] = [];
    if (typeof content === 'string') {
      text = content;
    } else if (Array.isArray(content)) {
      for (const b of content) {
        if (!b || typeof b !== 'object') continue;
        if (b.type === 'text' && typeof (b as { text?: unknown }).text === 'string') {
          const t = (b as { text: string }).text;
          if (t) text += (text ? '\n' : '') + t;
        } else if (b.type === 'tool_use') {
          toolUseBlocks.push(b);
        } else if (b.type === 'tool_result') {
          toolResultBlocks.push(b);
        } else if (
          b.type === 'image_ref' ||
          b.type === 'pdf_ref' ||
          b.type === 'word_ref' ||
          b.type === 'excel_ref' ||
          b.type === 'csv_ref'
        ) {
          // MULTIMODAL-MVP Phase 2 / Wave 3: collect refs so ChatWindow can
          // render inline thumbnails (image) / chips (pdf / word / excel /
          // csv) via AttachmentThumbnail.
          // The earlier Phase 1 emoji+filename string injection here was a
          // placeholder until Phase 2 thumbnails landed — keeping it now would
          // double-render alongside the chip.
          const ref = b as unknown as {
            filename?: unknown;
            attachment_id?: unknown;
            page_count?: unknown;
            pageCount?: unknown;
            sheet_count?: unknown;
            sheetCount?: unknown;
            caption?: unknown;
          };
          const attachmentId =
            typeof ref.attachment_id === 'string' ? ref.attachment_id : '';
          if (!attachmentId) continue;
          const filename =
            typeof ref.filename === 'string' && ref.filename.length > 0
              ? ref.filename
              : attachmentId;
          // BE Jackson emits snake_case (`page_count` / `sheet_count`). Some
          // legacy / hand-rolled call sites use camelCase — read both per
          // existing pattern.
          const rawPages = ref.page_count ?? ref.pageCount;
          const pageCount =
            typeof rawPages === 'number' && Number.isFinite(rawPages) ? rawPages : undefined;
          const rawSheets = ref.sheet_count ?? ref.sheetCount;
          const sheetCount =
            typeof rawSheets === 'number' && Number.isFinite(rawSheets) ? rawSheets : undefined;
          let kind: ChatAttachmentRef['kind'];
          switch (b.type) {
            case 'image_ref': kind = 'image'; break;
            case 'pdf_ref':   kind = 'pdf'; break;
            case 'word_ref':  kind = 'word'; break;
            case 'excel_ref': kind = 'excel'; break;
            case 'csv_ref':   kind = 'csv'; break;
            default:          continue;
          }
          attachmentRefs.push({
            kind,
            attachmentId,
            filename,
            pageCount,
            sheetCount,
            caption: typeof ref.caption === 'string' && ref.caption.trim().length > 0
              ? ref.caption
              : undefined,
          });
        }
      }
    }
    return { text, toolUseBlocks, toolResultBlocks, attachmentRefs };
  };

  for (const m of list) {
    const msgType = typeof m.msgType === 'string' ? m.msgType.toUpperCase() : '';
    const messageType = typeof m.messageType === 'string' ? m.messageType : 'normal';
    const { text, toolUseBlocks, toolResultBlocks, attachmentRefs } = extractBlocks(m.content);

    // Server-side createdAt (ISO string). Surfaced on hover via msg-time. May
    // be absent on legacy rows / pre-feature sessions — `ChatMessage.timestamp`
    // is also optional, so passing undefined through is intentional + safe.
    // Declared up-front so all push sites below (ask_user / summary / user /
    // assistant) can pass the same value without recomputing the typeof guard.
    const timestamp = typeof m.createdAt === 'string' ? m.createdAt : undefined;
    const messageId = typeof m.seqNo === 'number' && Number.isFinite(m.seqNo)
      ? String(m.seqNo)
      : undefined;

    if (msgType === 'COMPACT_BOUNDARY') {
      // Boundary is a structural marker for context slicing, not a user-visible card.
      continue;
    }

    if (msgType === 'RECOVERY_PAYLOAD') {
      // REMINDER-MVP D6: post-compact recovery payload is wrapped as a single
      // <system-reminder>…</system-reminder> String — system framework noise,
      // not for end-user display. Skip the row entirely (parallels how
      // COMPACT_BOUNDARY is filtered above). The string-shape branch in
      // stripSystemReminderBlocks is a second-layer defense for any future
      // call site that bypasses this filter.
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
        timestamp,
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
        result.push({
          role: 'summary',
          content: `${header}${summaryText}`.trim(),
          timestamp,
        });
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
      // Phase 2: a pure-attachment user message (image + no caption) has an
      // empty `text` but non-empty `attachmentRefs` — keep the row so the
      // thumbnails render. Drop only when BOTH text and attachments are empty.
      if (!text.trim() && attachmentRefs.length === 0) continue;

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
            result.push({ role: 'summary', content: displayContent, timestamp });
          }
          continue;
        }
        // 合并消息，取分隔符之后的原始用户文本
        displayText = text.slice(sepIdx + sep.length).trim();
        if (!displayText) continue;
      }

      result.push({
        role: 'user',
        content: displayText,
        attachments: attachmentRefs.length > 0 ? attachmentRefs : undefined,
        timestamp,
        id: messageId,
      });
    } else if (m.role === 'assistant') {
      const toolCalls = toolUseBlocks.map((b: any) => ({
        id: b.id,
        name: b.name,
        input: b.input,
      }));
      // Keep the row when it has only reasoning or attachments. In particular,
      // generated artifacts may intentionally produce an attachment-only
      // terminal assistant message.
      // CHAT-REASONING-PANEL: keep the row even when it has *only*
      // reasoningContent (no visible text + no toolCalls). The
      // ReasoningPanel above the (empty) text bubble is the user-visible
      // surface for "the model thought but produced no spoken reply" —
      // dropping the row here would silently swallow that signal.
      const hasReasoning =
        typeof m.reasoningContent === 'string' && m.reasoningContent.trim().length > 0;
      if (!text.trim() && toolCalls.length === 0 && !hasReasoning && attachmentRefs.length === 0) continue;
      result.push({
        role: 'assistant',
        content: text,
        attachments: attachmentRefs.length > 0 ? attachmentRefs : undefined,
        id: messageId,
        // m.toolCalls runtime 是 BE 推过来的 fallback 数组（不走 content blocks 路径时），
        // RawMessage.toolCalls 类型 unknown[]，此处 narrow 成 ChatMessage.toolCalls 期望的形态。
        // 不在 RawMessage 严格类型里的原因：fallback 路径形态不固定，类型贸然窄化反而 ripple 大。
        toolCalls: toolCalls.length > 0 ? toolCalls : (m.toolCalls as ChatMessage['toolCalls']),
        timestamp,
        // CHAT-REASONING-PANEL: pass through reasoning text from BE
        // persisted `t_session_message.reasoning_content` so the
        // assistant bubble's ReasoningPanel can render `Thought ▾`
        // for historical messages.
        reasoningContent: typeof m.reasoningContent === 'string' ? m.reasoningContent : undefined,
      });
    }
  }
  return result;
}

export function useChatMessages(activeSessionId: string | undefined) {
  const [rawMessages, setRawMessages] = useState<RawMessage[]>([]);
  const [messageSessionId, setMessageSessionId] = useState(activeSessionId);
  const [streamingText, setStreamingText] = useState<string>('');
  const [streamingToolInputs, setStreamingToolInputs] = useState<Record<string, StreamingToolInput>>({});
  const [inflightTools, setInflightTools] = useState<Record<string, InflightTool>>({});
  const [loopSpans, setLoopSpans] = useState<LoopSpan[]>([]);
  const ownsActiveSession = messageSessionId === activeSessionId;

  const messages = useMemo(
    () => ownsActiveSession ? normalizeMessages(rawMessages) : [],
    [ownsActiveSession, rawMessages],
  );

  useEffect(() => {
    setRawMessages([]);
    setMessageSessionId(activeSessionId);
    setStreamingText('');
    setStreamingToolInputs({});
    setInflightTools({});
    setLoopSpans([]);
  }, [activeSessionId]);

  return {
    rawMessages,
    setRawMessages,
    messages,
    streamingText: ownsActiveSession ? streamingText : '',
    setStreamingText,
    streamingToolInputs: ownsActiveSession ? streamingToolInputs : {},
    setStreamingToolInputs,
    inflightTools: ownsActiveSession ? inflightTools : {},
    setInflightTools,
    loopSpans: ownsActiveSession ? loopSpans : [],
    setLoopSpans,
  };
}
