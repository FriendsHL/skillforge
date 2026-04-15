import { useEffect, useRef } from 'react';
import { message } from 'antd';
import { getSession, getSessionMessages, extractList } from '../api';

export type RuntimeStatus = 'idle' | 'running' | 'waiting_user' | 'error';
export type ExecutionMode = 'ask' | 'auto';

export interface ChatSessionSetters {
  setRawMessages: (list: unknown[]) => void;
  setRuntimeStatus: (s: RuntimeStatus) => void;
  setRuntimeStep: (s: string) => void;
  setRuntimeError: (s: string) => void;
  setExecutionMode: (m: ExecutionMode) => void;
  setSelectedAgent: (id: number) => void;
  setParentSessionId: (s: string | null) => void;
  setSessionDepth: (n: number) => void;
  setCollabRunId: (s: string | null) => void;
  setCollabHandle: (s: string | null) => void;
  setCollabLeaderSessionId: (s: string | null) => void;
  setCollabRunStatus: (s: string | null) => void;
  setLightCompactCount: (n: number) => void;
  setFullCompactCount: (n: number) => void;
  setTotalTokensReclaimed: (n: number) => void;
}

export function useChatSession(
  activeSessionId: string | undefined,
  setters: ChatSessionSetters,
) {
  const settersRef = useRef(setters);
  useEffect(() => {
    settersRef.current = setters;
  });

  useEffect(() => {
    if (!activeSessionId) return;
    const s = settersRef.current;

    getSessionMessages(activeSessionId, 1)
      .then((res) => {
        s.setRawMessages(extractList(res));
      })
      .catch(() => message.error('Failed to load messages'));

    getSession(activeSessionId, 1)
      .then((res) => {
        const d = res.data;
        s.setRuntimeStatus((d.runtimeStatus ?? 'idle') as RuntimeStatus);
        s.setRuntimeStep(d.runtimeStep ?? '');
        s.setRuntimeError(d.runtimeError ?? '');
        s.setExecutionMode((d.executionMode ?? 'ask') as ExecutionMode);
        if (d.agentId != null) {
          s.setSelectedAgent(d.agentId);
        }
        s.setParentSessionId(d.parentSessionId ?? null);
        s.setSessionDepth(typeof d.depth === 'number' ? d.depth : 0);
        s.setCollabRunId(d.collabRunId ?? null);
        s.setCollabHandle(d.collabHandle ?? null);
        s.setCollabLeaderSessionId(d.collabLeaderSessionId ?? null);
        s.setCollabRunStatus(d.collabRunStatus ?? null);
        s.setLightCompactCount(d.lightCompactCount ?? 0);
        s.setFullCompactCount(d.fullCompactCount ?? 0);
        s.setTotalTokensReclaimed(d.totalTokensReclaimed ?? 0);
      })
      .catch((e) => console.error('Failed to load session details', e));
  }, [activeSessionId]);
}
