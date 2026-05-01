import { useCallback, useRef } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import type {
  ConfirmationChoice,
  ConfirmationPromptPayload,
} from '../api';
import type { InflightTool, StreamingToolInput, LoopSpan } from './useChatMessages';
import type { RuntimeStatus } from './useChatSession';

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
  setRawMessages: Dispatch<SetStateAction<unknown[]>>;
  setOtherInput: Dispatch<SetStateAction<string>>;
  setCollabRunStatus: Dispatch<SetStateAction<string | null>>;
  setSessions: Dispatch<SetStateAction<any[]>>;
  setCompactionNotice: Dispatch<SetStateAction<boolean>>;
  setLoopSpans: Dispatch<SetStateAction<LoopSpan[]>>;
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
  } = deps;

  const lastSnapshotVersionRef = useRef<number>(-1);
  const activeLlmSpanIdRef = useRef<string | null>(null);

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
        }
      } else if (evt.type === 'message_appended') {
        if (
          typeof evt.snapshotVersion === 'number' &&
          evt.snapshotVersion < lastSnapshotVersionRef.current
        ) {
          return;
        }
        const msg = evt.message as { role?: string; content?: unknown } | undefined;
        if (msg?.role === 'assistant') {
          setStreamingText('');
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
          setRawMessages((prev) => {
            if (prev.length > 0) {
              const last = prev[prev.length - 1] as { role?: string; content?: unknown } | null;
              if (
                last &&
                last.role === msg.role &&
                typeof last.content === 'string' &&
                typeof msg.content === 'string' &&
                last.content === msg.content
              ) {
                return prev;
              }
            }
            return [...prev, msg];
          });
        }
      } else if (evt.type === 'messages_snapshot') {
        if (Array.isArray(evt.messages)) {
          const prevVersion = lastSnapshotVersionRef.current;
          if (typeof evt.snapshotVersion === 'number') {
            lastSnapshotVersionRef.current = evt.snapshotVersion;
          }
          setRawMessages(evt.messages);
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
          setStreamingText((prev) => prev + chunk);
          // Start LLM_CALL span on first text delta of this turn
          if (!activeLlmSpanIdRef.current) {
            const llmId = `llm_${Date.now()}`;
            activeLlmSpanIdRef.current = llmId;
            setLoopSpans((prev) => [
              ...prev,
              { id: llmId, type: 'LLM_CALL', name: 'Generating', startTs: Date.now() },
            ]);
          }
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
    ],
  );
}
