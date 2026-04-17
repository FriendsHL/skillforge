import { useCallback, useRef } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import type { InflightTool, StreamingToolInput } from './useChatMessages';
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
  setInflightTools: Dispatch<SetStateAction<Record<string, InflightTool>>>;
  setStreamingText: Dispatch<SetStateAction<string>>;
  setStreamingToolInputs: Dispatch<SetStateAction<Record<string, StreamingToolInput>>>;
  setCancelling: Dispatch<SetStateAction<boolean>>;
  setRawMessages: Dispatch<SetStateAction<unknown[]>>;
  setOtherInput: Dispatch<SetStateAction<string>>;
  setCollabRunStatus: Dispatch<SetStateAction<string | null>>;
  setSessions: Dispatch<SetStateAction<any[]>>;
  setCompactionNotice: Dispatch<SetStateAction<boolean>>;
}

export function useChatWsEventHandler(deps: WsEventHandlerDeps) {
  const {
    setRuntimeStatus,
    setRuntimeStep,
    setRuntimeError,
    setPendingAsk,
    setInflightTools,
    setStreamingText,
    setStreamingToolInputs,
    setCancelling,
    setRawMessages,
    setOtherInput,
    setCollabRunStatus,
    setSessions,
    setCompactionNotice,
  } = deps;

  const lastSnapshotVersionRef = useRef<number>(-1);

  return useCallback(
    (evtRaw: unknown) => {
      const evt = evtRaw as WsEvent | null;
      if (!evt || !evt.type) return;
      if (evt.type === 'session_status') {
        setRuntimeStatus(((evt.status as string) ?? 'idle') as RuntimeStatus);
        setRuntimeStep((evt.step as string) ?? '');
        setRuntimeError((evt.error as string) ?? '');
        if (evt.status !== 'waiting_user') {
          setPendingAsk(null);
        }
        if (evt.status === 'idle' || evt.status === 'error') {
          setInflightTools({});
          setStreamingText('');
          setStreamingToolInputs({});
          setCancelling(false);
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
        setPendingAsk({
          askId: evt.askId as string,
          question: evt.question as string,
          context: evt.context as string | undefined,
          options: (evt.options as PendingAskOption[]) ?? [],
          allowOther: evt.allowOther !== false,
        });
        setOtherInput('');
      } else if (evt.type === 'tool_started') {
        const toolUseId = evt.toolUseId as string;
        const name = evt.name as string;
        setInflightTools((prev) => ({
          ...prev,
          [toolUseId]: { name, input: evt.input, startTs: Date.now() },
        }));
      } else if (evt.type === 'tool_finished') {
        const toolUseId = evt.toolUseId as string;
        setInflightTools((prev) => {
          const next = { ...prev };
          delete next[toolUseId];
          return next;
        });
      } else if (evt.type === 'text_delta') {
        const chunk = (evt.delta as string) ?? '';
        if (chunk) {
          setStreamingText((prev) => prev + chunk);
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
      setInflightTools,
      setStreamingText,
      setStreamingToolInputs,
      setCancelling,
      setRawMessages,
      setOtherInput,
      setCollabRunStatus,
      setSessions,
      setCompactionNotice,
    ],
  );
}
