import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getSessionTasks,
  parseSessionTaskSnapshot,
  type SessionTaskDto,
} from '../api/sessionTasks';

interface SessionTaskState {
  sessionId: string | undefined;
  tasks: SessionTaskDto[];
  generatedAt: string | null;
}

export interface SessionTasksState {
  tasks: SessionTaskDto[];
  loading: boolean;
  error: string | null;
  retry: () => Promise<void>;
  handleWsEvent: (event: unknown) => void;
}

function sortTasks(tasks: SessionTaskDto[]): SessionTaskDto[] {
  return [...tasks].sort((left, right) => {
    const byCreatedAt = left.createdAt.localeCompare(right.createdAt);
    return byCreatedAt !== 0 ? byCreatedAt : left.taskId.localeCompare(right.taskId);
  });
}

export function upsertSessionTasks(
  existing: SessionTaskDto[],
  incoming: SessionTaskDto[],
): SessionTaskDto[] {
  if (incoming.length === 0) return existing;
  const byId = new Map(existing.map((task) => [task.taskId, task]));
  let changed = false;
  for (const task of incoming) {
    const current = byId.get(task.taskId);
    if (!current
      || task.version > current.version
      || (task.version === current.version && task.updatedAt > current.updatedAt)) {
      byId.set(task.taskId, task);
      changed = true;
    }
  }
  return changed ? sortTasks([...byId.values()]) : existing;
}

export function useSessionTasks(
  activeSessionId: string | undefined,
  userId: number,
): SessionTasksState {
  const [state, setState] = useState<SessionTaskState>({
    sessionId: activeSessionId,
    tasks: [],
    generatedAt: null,
  });
  const [loading, setLoading] = useState(Boolean(activeSessionId));
  const [error, setError] = useState<string | null>(null);
  const activeSessionRef = useRef(activeSessionId);
  const requestGenerationRef = useRef(0);
  const pendingTasksRef = useRef<Map<string, SessionTaskDto>>(new Map());
  const pendingGeneratedAtRef = useRef<string | null>(null);
  const frameRef = useRef<number | null>(null);
  activeSessionRef.current = activeSessionId;

  const cancelPendingFrame = useCallback((): void => {
    if (frameRef.current !== null) {
      cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
    }
    pendingTasksRef.current.clear();
    pendingGeneratedAtRef.current = null;
  }, []);

  const load = useCallback(async (): Promise<void> => {
    const sessionId = activeSessionRef.current;
    if (!sessionId) return;
    const requestGeneration = ++requestGenerationRef.current;
    setLoading(true);
    setError(null);
    try {
      const response = await getSessionTasks(sessionId, userId);
      const snapshot = parseSessionTaskSnapshot(response.data);
      if (
        requestGeneration !== requestGenerationRef.current ||
        activeSessionRef.current !== sessionId
      ) {
        return;
      }
      if (!snapshot || snapshot.sessionId !== sessionId) {
        throw new Error('Invalid session task snapshot');
      }
      setState((previous) => {
        if (previous.sessionId !== sessionId) return previous;
        const pending = [...pendingTasksRef.current.values()];
        return {
          sessionId,
          tasks: upsertSessionTasks(
            upsertSessionTasks(previous.tasks, snapshot.tasks),
            pending,
          ),
          generatedAt: pendingGeneratedAtRef.current ?? snapshot.generatedAt,
        };
      });
    } catch {
      if (
        requestGeneration === requestGenerationRef.current &&
        activeSessionRef.current === sessionId
      ) {
        setError('Task progress unavailable');
      }
    } finally {
      if (
        requestGeneration === requestGenerationRef.current &&
        activeSessionRef.current === sessionId
      ) {
        setLoading(false);
      }
    }
  }, [userId]);

  useEffect(() => {
    requestGenerationRef.current += 1;
    cancelPendingFrame();
    setState({ sessionId: activeSessionId, tasks: [], generatedAt: null });
    setError(null);
    setLoading(Boolean(activeSessionId));
    if (activeSessionId) void load();
    return () => {
      requestGenerationRef.current += 1;
      cancelPendingFrame();
    };
  }, [activeSessionId, cancelPendingFrame, load]);

  const handleWsEvent = useCallback((event: unknown): void => {
    if (!event || typeof event !== 'object') return;
    const envelope = event as Record<string, unknown>;
    if (envelope.type !== 'session_tasks_snapshot') return;
    const snapshot = parseSessionTaskSnapshot(envelope);
    const sessionId = activeSessionRef.current;
    if (!snapshot || !sessionId || snapshot.sessionId !== sessionId) return;

    for (const task of snapshot.tasks) {
      const pending = pendingTasksRef.current.get(task.taskId);
      if (!pending
        || task.version > pending.version
        || (task.version === pending.version && task.updatedAt > pending.updatedAt)) {
        pendingTasksRef.current.set(task.taskId, task);
      }
    }
    pendingGeneratedAtRef.current = snapshot.generatedAt;
    setError(null);

    if (frameRef.current !== null) return;
    frameRef.current = requestAnimationFrame(() => {
      frameRef.current = null;
      const pendingTasks = [...pendingTasksRef.current.values()];
      const generatedAt = pendingGeneratedAtRef.current;
      pendingTasksRef.current.clear();
      pendingGeneratedAtRef.current = null;
      const currentSessionId = activeSessionRef.current;
      if (!currentSessionId) return;
      setState((previous) => {
        if (previous.sessionId !== currentSessionId) return previous;
        return {
          sessionId: currentSessionId,
          tasks: upsertSessionTasks(previous.tasks, pendingTasks),
          generatedAt: generatedAt ?? previous.generatedAt,
        };
      });
    });
  }, []);

  const ownsActiveSession = state.sessionId === activeSessionId;
  return {
    tasks: ownsActiveSession ? state.tasks : [],
    loading: ownsActiveSession ? loading : Boolean(activeSessionId),
    error: ownsActiveSession ? error : null,
    retry: load,
    handleWsEvent,
  };
}
