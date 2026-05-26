import { useCallback, useRef } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import type {
  ConfirmationChoice,
  ConfirmationPromptPayload,
} from '../api';
import type { InflightTool, StreamingToolInput, LoopSpan } from './useChatMessages';
import type { RuntimeStatus } from './useChatSession';
import type { RawMessage } from '../types/messages';
import {
  stripSystemReminderBlocks,
  stripRemindersFromMessageList,
} from '../utils/messageContent';

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

interface WsEvent {
  type: string;
  snapshotVersion?: number;
  [key: string]: unknown;
}

export interface WsEventHandlerDeps {
  setRuntimeStatus: Dispatch<SetStateAction<RuntimeStatus>>;
  setRuntimeStep: Dispatch<SetStateAction<string>>;
  setRuntimeError: Dispatch<SetStateAction<string>>;
  setPendingAsk: Dispatch<SetStateAction<PendingAsk | null>>;
  setPendingConfirm: Dispatch<SetStateAction<ConfirmationPromptPayload | null>>;
  setInflightTools: Dispatch<SetStateAction<Record<string, InflightTool>>>;
  setStreamingText: Dispatch<SetStateAction<string>>;
  setStreamingToolInputs: Dispatch<SetStateAction<Record<string, StreamingToolInput>>>;
  setCancelling: Dispatch<SetStateAction<boolean>>;
  setRawMessages: Dispatch<SetStateAction<RawMessage[]>>;
  setOtherInput: Dispatch<SetStateAction<string>>;
  setCollabRunStatus: Dispatch<SetStateAction<string | null>>;
  setSessions: Dispatch<SetStateAction<any[]>>;
  setCompactionNotice: Dispatch<SetStateAction<boolean>>;
  setLoopSpans: Dispatch<SetStateAction<LoopSpan[]>>;
  /** Short model display name for LLM_CALL span labels (e.g. "claude-sonnet-4"). */
  llmModelName?: string;
  /**
   * CHAT-REASONING-PANEL: accumulator for `reasoning_delta` SSE events.
   * Cleared on `message_appended` (handing off to the persisted
   * `ChatMessage.reasoningContent` for completed display) and on
   * `session_status` idle/error.
   */
  setStreamingReasoningText: Dispatch<SetStateAction<string>>;
  /**
   * CHAT-REASONING-PANEL: wall-clock duration of the reasoning phase
   * (first `reasoning_delta` → first `text_delta`) in milliseconds.
   * Set on first `text_delta`. Cleared on `message_appended` and on
   * `session_status` idle/error.
   */
  setReasoningDurationMs: Dispatch<SetStateAction<number | null>>;
}

export function useChatWsEventHandler(deps: WsEventHandlerDeps) {
  const {
    setRuntimeStatus,
    setRuntimeStep,
    setRuntimeError,
    setPendingAsk,
    setPendingConfirm,
    setInflightTools,
    setStreamingText,
    setStreamingToolInputs,
    setCancelling,
    setRawMessages,
    setOtherInput,
    setCollabRunStatus,
    setSessions,
    setCompactionNotice,
    setLoopSpans,
    llmModelName,
    setStreamingReasoningText,
    setReasoningDurationMs,
  } = deps;

  const lastSnapshotVersionRef = useRef<number>(-1);
  const activeLlmSpanIdRef = useRef<string | null>(null);
  /**
   * CHAT-REASONING-PANEL — wall-clock timer for reasoning phase. Set to
   * `Date.now()` on the first `reasoning_delta` of a turn. Cleared (set
   * to null) either when the first `text_delta` arrives (we compute
   * durationMs at that point) or when the session resets via
   * session_status idle/error (turn aborted before text). Ref, not state,
   * because purely a timestamp scratchpad — no UI render depends on it.
   */
  const reasoningStartTsRef = useRef<number | null>(null);

  return useCallback(
    (evtRaw: unknown) => {
      const evt = evtRaw as WsEvent | null;
      if (!evt || !evt.type) return;
      if (evt.type === 'session_status') {
        setRuntimeStatus(((evt.status as string) ?? 'idle') as RuntimeStatus);
        setRuntimeStep((evt.step as string) ?? '');
        const errorDetail = (evt.error as string) ?? '';
        setRuntimeError(errorDetail);
        if (evt.status !== 'waiting_user') {
          setPendingAsk(null);
          setPendingConfirm(null);
        }
        if (evt.status === 'error' && errorDetail) {
          // 保留 console.error：前端 UI 只显示简短文案（"Agent loop failed"），
          // 详细栈帧通过 DevTools 控制台暴露给用户/运维追溯根因。
          // 项目前端当前无统一 logger，直接用 console.error 符合现状。
          console.error(
            `[Session ${(evt.sessionId as string) ?? 'unknown'}] Agent loop failed:\n${errorDetail}`,
          );
        }
        if (evt.status === 'idle' || evt.status === 'error') {
          setInflightTools({});
          setStreamingText('');
          setStreamingToolInputs({});
          setCancelling(false);
          activeLlmSpanIdRef.current = null;
          // CHAT-REASONING-PANEL: session-level cleanup. Cancelled / errored
          // turns may abort mid-reasoning before any text_delta lands —
          // ensure we don't leak the streaming panel into the next turn.
          setStreamingReasoningText('');
          setReasoningDurationMs(null);
          reasoningStartTsRef.current = null;
        }
      } else if (evt.type === 'message_appended') {
        if (
          typeof evt.snapshotVersion === 'number' &&
          evt.snapshotVersion < lastSnapshotVersionRef.current
        ) {
          return;
        }
        const msg = evt.message as RawMessage | undefined;
        if (msg?.role === 'assistant') {
          setStreamingText('');
          // CHAT-REASONING-PANEL: hand off from streaming panel to the
          // persisted ChatMessage.reasoningContent. The final assistant
          // bubble renders its own ReasoningPanel(reasoningContent=...)
          // via normalizeMessages, so the streaming-panel state must
          // clear here to avoid double-rendering.
          setStreamingReasoningText('');
          setReasoningDurationMs(null);
          reasoningStartTsRef.current = null;
          // Close active LLM_CALL span
          if (activeLlmSpanIdRef.current) {
            const llmId = activeLlmSpanIdRef.current;
            activeLlmSpanIdRef.current = null;
            const endTs = Date.now();
            setLoopSpans((prev) =>
              prev.map((e) =>
                e.id === llmId ? { ...e, endTs, status: 'success', durationMs: endTs - e.startTs } : e,
              ),
            );
          }
        }
        if (msg) {
          // REMINDER-MVP: strip <system-reminder> blocks before storing so
          // dedupe/append logic and downstream renderers see clean content.
          // strip helper returns `unknown`（防御任何 input 形态），但 input 来自
          // RawMessage.content（string | ContentBlock[] | undefined），strip 后仍是
          // 这几种 shape 之一，安全 cast 回 RawMessage.content。
          const cleanedContent = stripSystemReminderBlocks(msg.content) as RawMessage['content'];
          // CHAT-REASONING-PANEL hotfix: BE Message Java class serializes
          // reasoningContent as snake_case "reasoning_content" (per
          // Message.java @JsonProperty("reasoning_content"), required for
          // upstream LLM API replay roundtrip). FE RawMessage type expects
          // camelCase reasoningContent (matches REST SessionMessageDto record
          // field). Bridge them here so message_appended hands off to the
          // historical ReasoningPanel without dropping the field. Envelope
          // also carries top-level `reasoningContent` (ChatWebSocketHandler
          // hotfix), prefer that when present; fall back to message-body
          // snake_case for older BE / parity.
          const evtReasoning = (evt as { reasoningContent?: string }).reasoningContent;
          const msgSnakeReasoning = (msg as { reasoning_content?: string }).reasoning_content;
          const resolvedReasoning =
            typeof evtReasoning === 'string' && evtReasoning.length > 0
              ? evtReasoning
              : typeof msgSnakeReasoning === 'string' && msgSnakeReasoning.length > 0
                ? msgSnakeReasoning
                : msg.reasoningContent;
          const cleanedMsg: RawMessage =
            cleanedContent === msg.content && resolvedReasoning === msg.reasoningContent
              ? msg
              : { ...msg, content: cleanedContent, reasoningContent: resolvedReasoning };
          setRawMessages((prev) => {
            if (prev.length > 0) {
              const last = prev[prev.length - 1] as { role?: string; content?: unknown } | null;
              if (
                last &&
                last.role === cleanedMsg.role &&
                typeof last.content === 'string' &&
                typeof cleanedMsg.content === 'string' &&
                last.content === cleanedMsg.content
              ) {
                return prev;
              }
            }
            return [...prev, cleanedMsg];
          });
        }
      } else if (evt.type === 'messages_snapshot') {
        if (Array.isArray(evt.messages)) {
          const prevVersion = lastSnapshotVersionRef.current;
          if (typeof evt.snapshotVersion === 'number') {
            lastSnapshotVersionRef.current = evt.snapshotVersion;
          }
          setRawMessages(stripRemindersFromMessageList(evt.messages));
          if (prevVersion >= 0) {
            setCompactionNotice(true);
          }
        }
      } else if (evt.type === 'ask_user') {
        const askId = evt.askId as string;
        const question = (evt.question as string) ?? '';
        const context = evt.context as string | undefined;
        const options = Array.isArray(evt.options) ? (evt.options as PendingAskOption[]) : [];
        const allowOther = evt.allowOther !== false;
        setPendingAsk(null);
        setRawMessages((prev) => {
          if (prev.some((m) => {
            const msg = m as { messageType?: string; controlId?: string } | null;
            return msg?.messageType === 'ask_user' && msg.controlId === askId;
          })) {
            return prev;
          }
          return [
            ...prev,
            {
              role: 'assistant',
              content: question,
              msgType: 'SYSTEM_EVENT',
              messageType: 'ask_user',
              controlId: askId,
              metadata: {
                controlId: askId,
                question,
                context,
                options,
                allowOther,
                state: 'pending',
              },
            },
          ];
        });
        setOtherInput('');
      } else if (evt.type === 'confirmation_required') {
        // Human confirmation — fields may be delivered flat on the event or
        // nested under payload, mirroring ask_user convention.
        const payload = (evt.payload as Partial<ConfirmationPromptPayload> | undefined) ?? {};
        const confirmationId = (evt.confirmationId as string | undefined) ?? payload.confirmationId;
        if (!confirmationId) return;
        const sessionId = (evt.sessionId as string | undefined) ?? payload.sessionId ?? '';
        const installTool = (evt.installTool as string | undefined) ?? payload.installTool ?? 'unknown';
        const installTarget = (evt.installTarget as string | undefined) ?? payload.installTarget ?? '*';
        const commandPreview = (evt.commandPreview as string | undefined) ?? payload.commandPreview ?? '';
        const title = (evt.title as string | undefined) ?? payload.title ?? 'Install confirmation required';
        const description = (evt.description as string | undefined) ?? payload.description ?? '';
        const rawChoices = (evt.choices ?? payload.choices) as ConfirmationChoice[] | undefined;
        const choices: ConfirmationChoice[] = Array.isArray(rawChoices) && rawChoices.length > 0
          ? rawChoices
          : [
              { id: 'deny', label: 'Deny', variant: 'deny' },
              { id: 'approve', label: 'Approve', variant: 'approve' },
            ];
        const expiresAt = (evt.expiresAt as string | undefined) ?? payload.expiresAt ?? '';
        const cardPayload = {
          confirmationId,
          sessionId,
          installTool,
          installTarget,
          commandPreview,
          title,
          description,
          choices,
          expiresAt,
        };
        setPendingConfirm(null);
        setRawMessages((prev) => {
          if (prev.some((m) => {
            const msg = m as { messageType?: string; controlId?: string } | null;
            return msg?.messageType === 'confirmation' && msg.controlId === confirmationId;
          })) {
            return prev;
          }
          return [
            ...prev,
            {
              role: 'assistant',
              content: title,
              msgType: 'SYSTEM_EVENT',
              messageType: 'confirmation',
              controlId: confirmationId,
              metadata: {
                ...cardPayload,
                controlId: confirmationId,
                question: title,
                state: 'pending',
              },
            },
          ];
        });
      } else if (evt.type === 'tool_started') {
        const toolUseId = evt.toolUseId as string;
        const name = evt.name as string;
        const startTs = Date.now();
        setInflightTools((prev) => ({
          ...prev,
          [toolUseId]: { name, input: evt.input, startTs },
        }));
        setLoopSpans((prev) => {
          if (prev.some((e) => e.id === toolUseId)) return prev;
          return [...prev, { id: toolUseId, type: 'TOOL_CALL', name, startTs }];
        });
      } else if (evt.type === 'tool_finished') {
        const toolUseId = evt.toolUseId as string;
        const endTs = Date.now();
        const status = (evt.status as 'success' | 'error') ?? 'success';
        setInflightTools((prev) => {
          const next = { ...prev };
          delete next[toolUseId];
          return next;
        });
        setLoopSpans((prev) =>
          prev.map((e) =>
            e.id === toolUseId
              ? { ...e, endTs, status, durationMs: endTs - e.startTs }
              : e,
          ),
        );
      } else if (evt.type === 'text_delta') {
        const chunk = (evt.delta as string) ?? '';
        if (chunk) {
          // CHAT-REASONING-PANEL: first text_delta of a turn marks the end
          // of the reasoning phase. Capture wall-clock duration from the
          // earlier reasoning_delta timestamp (if any). Subsequent
          // text_deltas leave the timer alone (already null).
          if (reasoningStartTsRef.current !== null) {
            const durationMs = Date.now() - reasoningStartTsRef.current;
            setReasoningDurationMs(durationMs >= 0 ? durationMs : 0);
            reasoningStartTsRef.current = null;
          }
          setStreamingText((prev) => prev + chunk);
          // Start LLM_CALL span on first text delta of this turn
          if (!activeLlmSpanIdRef.current) {
            const llmId = `llm_${Date.now()}`;
            activeLlmSpanIdRef.current = llmId;
            setLoopSpans((prev) => [
              ...prev,
              { id: llmId, type: 'LLM_CALL', name: llmModelName || 'LLM', startTs: Date.now() },
            ]);
          }
        }
      } else if (evt.type === 'reasoning_delta') {
        // CHAT-REASONING-PANEL: streaming reasoning content from
        // OpenAI-compatible providers (DeepSeek / Qwen / mimo) routed
        // through OpenAiProvider's `onReasoning` callback →
        // ChatEventBroadcaster.reasoningDelta → ChatWebSocketHandler.
        // Payload shape mirrors text_delta:
        //   { type: 'reasoning_delta', sessionId, delta }
        // (matched against BE ChatWebSocketHandler.reasoningDelta which
        // emits payload.put("delta", delta); see java.md footgun #6).
        // We accumulate into streamingReasoningText (each delta = one
        // setState; React batches, and ReasoningPanel → ThrottledMarkdown
        // commits DOM at most 5×/s per frontend.md footgun #3).
        const chunk = (evt.delta as string) ?? '';
        if (chunk) {
          if (reasoningStartTsRef.current === null) {
            reasoningStartTsRef.current = Date.now();
          }
          setStreamingReasoningText((prev) => prev + chunk);
        }
      } else if (evt.type === 'tool_use_delta') {
        const toolUseId = evt.toolUseId as string;
        setStreamingToolInputs((prev) => {
          const next = { ...prev };
          const existing = next[toolUseId];
          next[toolUseId] = {
            name: existing?.name || (evt.toolName as string) || 'tool',
            jsonBuffer: (existing?.jsonBuffer ?? '') + ((evt.jsonFragment as string) ?? ''),
            // Capture startTs once on first delta so useMemo stays pure (no Date.now() there)
            startTs: existing?.startTs ?? Date.now(),
          };
          return next;
        });
      } else if (evt.type === 'tool_use_complete') {
        const toolUseId = evt.toolUseId as string;
        setStreamingToolInputs((prev) => {
          const next = { ...prev };
          delete next[toolUseId];
          return next;
        });
      } else if (evt.type === 'assistant_stream_end') {
        // wait for message_appended to clear streaming text
      } else if (
        evt.type === 'collab_member_spawned' ||
        evt.type === 'collab_member_finished' ||
        evt.type === 'collab_run_status' ||
        evt.type === 'collab_message_routed'
      ) {
        if (evt.type === 'collab_run_status' && evt.status) {
          setCollabRunStatus(evt.status as string);
        }
        window.dispatchEvent(new CustomEvent('collab_ws_event', { detail: evt }));
      } else if (evt.type === 'session_title_updated') {
        const newTitle = evt.title as string | undefined;
        if (newTitle) {
          setSessions((prev) =>
            prev.map((s) => {
              const sid = String(s.id ?? s.sessionId);
              return sid === evt.sessionId ? { ...s, title: newTitle } : s;
            }),
          );
        }
      }
    },
    [
      setRuntimeStatus,
      setRuntimeStep,
      setRuntimeError,
      setPendingAsk,
      setPendingConfirm,
      setInflightTools,
      setStreamingText,
      setStreamingToolInputs,
      setCancelling,
      setRawMessages,
      setOtherInput,
      setCollabRunStatus,
      setSessions,
      setCompactionNotice,
      setLoopSpans,
      llmModelName,
      setStreamingReasoningText,
      setReasoningDurationMs,
    ],
  );
}
