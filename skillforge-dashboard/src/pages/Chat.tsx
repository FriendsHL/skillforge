import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Modal, message } from 'antd';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import ChatWindow from '../components/ChatWindow';
import SessionReplay from '../components/SessionReplay';
import CompactionHistoryModal from '../components/CompactionHistoryModal';
import CheckpointModal from '../components/CheckpointModal';
import RuntimeBanner from '../components/RuntimeBanner';
import PendingAskCard from '../components/PendingAskCard';
import InstallConfirmationCard from '../components/InstallConfirmationCard';
import ChatSidebar from '../components/ChatSidebar';
import RightRail, {
  type CollabMember,
  type PeerMessage,
} from '../components/chat/RightRail';
import { Chip, Seg } from '../components/chat/primitives';
import { IconChat, IconCompact, IconReplay } from '../components/chat/ChatIcons';
import { ChannelBadge } from '../components/channels/ChannelBadge';
import {
  getAgents,
  createSession,
  getSessions,
  getSessionMessages,
  sendMessage,
  uploadChatAttachment,
  cancelChat,
  answerAsk,
  setSessionMode,
  getSession,
  compactSession,
  getCompactions,
  getSessionCheckpoints,
  getSessionCheckpoint,
  branchFromCheckpoint,
  restoreFromCheckpoint,
  getCollabRunMembers,
  submitConfirmation,
  extractList,
  type ConfirmationDecision,
  type ConfirmationPromptPayload,
  type SessionCompactionCheckpoint,
} from '../api';
import { z } from 'zod';
import { AgentSchema, SessionSchema, safeParseList } from '../api/schemas';
import { useChatWebSocket } from '../hooks/useChatWebSocket';
import { useLlmModels } from '../hooks/useLlmModels';
import { useChatMessages, type InflightTool } from '../hooks/useChatMessages';
import { stripRemindersFromMessageList } from '../utils/messageContent';
import { useCollabState } from '../hooks/useCollabState';
import {
  useChatSession,
  type RuntimeStatus,
  type ExecutionMode,
} from '../hooks/useChatSession';
import { useChatWsEventHandler } from '../hooks/useChatWsEventHandler';
import { useAuth } from '../contexts/AuthContext';

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

const MAX_PEER_MESSAGES = 50;
const DEFAULT_CONTEXT_WINDOW_TOKENS = 200000;

const Chat: React.FC = () => {
  const { sessionId: urlSessionId } = useParams<{ sessionId?: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const { userId } = useAuth();
  const [agents, setAgents] = useState<z.infer<typeof AgentSchema>[]>([]);
  const [parentSessionId, setParentSessionId] = useState<string | null>(null);
  const [sessionDepth, setSessionDepth] = useState<number>(0);
  // Read selectedAgent from URL ?agent= param (per-tab independent)
  const selectedAgent = useMemo(() => {
    const agentParam = searchParams.get('agent');
    return agentParam ? Number(agentParam) : undefined;
  }, [searchParams]);
  const setSelectedAgent = useCallback((agentId: number | undefined) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (agentId != null) {
        next.set('agent', String(agentId));
      } else {
        next.delete('agent');
      }
      return next;
    }, { replace: true });
  }, [setSearchParams]);
  const [sessions, setSessions] = useState<z.infer<typeof SessionSchema>[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(false);
  const [activeSessionId, setActiveSessionId] = useState<string | undefined>(
    urlSessionId,
  );

  const {
    setRawMessages,
    messages,
    streamingText,
    setStreamingText,
    streamingToolInputs,
    setStreamingToolInputs,
    inflightTools,
    setInflightTools,
    loopSpans,
    setLoopSpans,
  } = useChatMessages(activeSessionId);

  const [runtimeStatus, setRuntimeStatus] = useState<RuntimeStatus>('idle');
  const [runtimeStep, setRuntimeStep] = useState<string>('');
  const [runtimeError, setRuntimeError] = useState<string>('');
  const [executionMode, setExecutionModeState] = useState<ExecutionMode>('ask');
  const [pendingAsk, setPendingAsk] = useState<PendingAsk | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<ConfirmationPromptPayload | null>(null);
  const [confirmSubmitting, setConfirmSubmitting] = useState<ConfirmationDecision | null>(null);
  const [otherInput, setOtherInput] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [lightCompactCount, setLightCompactCount] = useState(0);
  const [fullCompactCount, setFullCompactCount] = useState(0);
  const [totalTokensReclaimed, setTotalTokensReclaimed] = useState(0);
  const [viewMode, setViewMode] = useState<'chat' | 'replay'>('chat');
  const [compactModalOpen, setCompactModalOpen] = useState(false);
  const [compactEvents, setCompactEvents] = useState<unknown[]>([]);
  const [checkpointModalOpen, setCheckpointModalOpen] = useState(false);
  const [checkpointsLoading, setCheckpointsLoading] = useState(false);
  const [checkpoints, setCheckpoints] = useState<SessionCompactionCheckpoint[]>([]);
  const [selectedCheckpoint, setSelectedCheckpoint] = useState<SessionCompactionCheckpoint>();
  const [checkpointActionLoading, setCheckpointActionLoading] = useState<string | null>(null);
  const [compacting, setCompacting] = useState(false);
  const [compactionNotice, setCompactionNotice] = useState(false);
  const [collabMembers, setCollabMembers] = useState<CollabMember[]>([]);
  const [peerMessages, setPeerMessages] = useState<PeerMessage[]>([]);
  const activeSessionIdRef = useRef<string | undefined>(activeSessionId);
  const checkpointLoadSeqRef = useRef(0);
  const checkpointDetailSeqRef = useRef(0);

  useEffect(() => {
    activeSessionIdRef.current = activeSessionId;
  }, [activeSessionId]);

  useEffect(() => {
    if (!compactionNotice) return;
    const t = setTimeout(() => setCompactionNotice(false), 8000);
    return () => clearTimeout(t);
  }, [compactionNotice]);

  const {
    collabRunId,
    setCollabRunId,
    collabHandle,
    setCollabHandle,
    collabLeaderSessionId,
    setCollabLeaderSessionId,
    collabRunStatus,
    setCollabRunStatus,
  } = useCollabState(activeSessionId);

  useEffect(() => {
    if (urlSessionId && urlSessionId !== activeSessionId) {
      setActiveSessionId(urlSessionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlSessionId]);

  useEffect(() => {
    // SYSTEM-AGENT-TYPING Phase 2.2: BE /api/agents defaults to agentType='user'.
    // Chat needs BOTH user and system agents in `agents` so the Phase 2.3 send
    // gate (`activeAgent?.agentType === 'system' → disable + banner`) can fire
    // when a user opens an existing session URL whose `?agent=` points to a
    // system agent. Without `agentType='all'` here, `agents.find(...)` returns
    // undefined and the gate silently fails open.
    getAgents('all')
      .then((res) => {
        setAgents(safeParseList(AgentSchema, extractList(res)));
      })
      .catch(() => message.error('Failed to load agents'));
  }, []);

  useEffect(() => {
    if (selectedAgent == null) return;
    let cancelled = false;
    setSessionsLoading(true);
    getSessions(userId)
      .then((res) => {
        if (cancelled) return;
        const list = safeParseList(SessionSchema, extractList<unknown>(res)).filter(
          (s) => Number(s.agentId) === selectedAgent,
        );
        // Sort newest first
        list.sort((a, b) => {
          const ta = new Date(a.updatedAt || a.createdAt || 0).getTime();
          const tb = new Date(b.updatedAt || b.createdAt || 0).getTime();
          return tb - ta;
        });
        setSessions(list);
        // Auto-select most recent session if none is active
        if (list.length > 0) {
          setActiveSessionId((prev) => prev ?? String(list[0].id));
        }
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setSessionsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedAgent, userId]);

  useEffect(() => {
    setPendingAsk(null);
    setPendingConfirm(null);
    setConfirmSubmitting(null);
    setRuntimeError('');
    setCancelling(false);
    setViewMode('chat');
    setParentSessionId(null);
    setSessionDepth(0);
    setCompactionNotice(false);
    setPeerMessages([]);
    checkpointLoadSeqRef.current += 1;
    checkpointDetailSeqRef.current += 1;
    setCheckpoints([]);
    setSelectedCheckpoint(undefined);
    setCheckpointModalOpen(false);
    if (!activeSessionId) {
      setRuntimeStatus('idle');
      setRuntimeStep('');
    }
  }, [activeSessionId]);

  useChatSession(activeSessionId, {
    setRawMessages,
    setRuntimeStatus,
    setRuntimeStep,
    setRuntimeError,
    setExecutionMode: setExecutionModeState,
    setSelectedAgent: (id: number) => {
      if (selectedAgent !== id) setSelectedAgent(id);
    },
    setParentSessionId,
    setSessionDepth,
    setCollabRunId,
    setCollabHandle,
    setCollabLeaderSessionId,
    setCollabRunStatus,
    setLightCompactCount,
    setFullCompactCount,
    setTotalTokensReclaimed,
  });

  const handleWsEvent = useChatWsEventHandler({
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
  });

  useChatWebSocket(activeSessionId, handleWsEvent);

  // Fetch collab members when there's a collab run
  useEffect(() => {
    if (!collabRunId) {
      setCollabMembers([]);
      return;
    }
    let cancelled = false;
    const fetchMembers = async () => {
      try {
        const res = await getCollabRunMembers(collabRunId);
        if (cancelled) return;
        const members = (res.data?.members ?? []) as CollabMember[];
        setCollabMembers(members);
      } catch {
        // non-critical
      }
    };
    fetchMembers();
    const hasRunning =
      collabRunStatus === 'RUNNING' || collabRunStatus === 'running';
    const id = hasRunning ? setInterval(fetchMembers, 5000) : null;
    return () => {
      cancelled = true;
      if (id) clearInterval(id);
    };
  }, [collabRunId, collabRunStatus]);

  // Listen for peer messages
  useEffect(() => {
    if (!collabRunId) return;
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (
        detail?.type === 'collab_message_routed' &&
        detail.collabRunId === collabRunId
      ) {
        const isBroadcast = detail.toHandle === '*' || detail.broadcast === true;
        const pm: PeerMessage = {
          fromHandle: detail.fromHandle ?? '?',
          toHandle: detail.toHandle ?? '?',
          timestamp: new Date().toISOString(),
          isBroadcast,
        };
        setPeerMessages((prev) => {
          const next = [...prev, pm];
          return next.length > MAX_PEER_MESSAGES
            ? next.slice(next.length - MAX_PEER_MESSAGES)
            : next;
        });
      }
    };
    window.addEventListener('collab_ws_event', handler);
    return () => window.removeEventListener('collab_ws_event', handler);
  }, [collabRunId]);

  const handleNewSession = async () => {
    if (!selectedAgent) {
      message.warning('Please select an agent first');
      return;
    }
    try {
      const res = await createSession({ userId, agentId: selectedAgent });
      const newSession = res.data;
      const sid = newSession.id ?? newSession.sessionId;
      setActiveSessionId(String(sid));
      setSessions((prev) => [newSession, ...prev]);
      setRawMessages([]);
      message.success('Session created');
    } catch {
      message.error('Failed to create session');
    }
  };

  const handleSend = async (text: string, files: File[] = []) => {
    // SYSTEM-AGENT-TYPING Phase 2.3 — defense-in-depth gate. The textarea +
    // send button are already `inputDisabled` when activeAgent.agentType ===
    // 'system' (see `inputDisabled` derivation below). This top-of-handler
    // check covers slash-command / programmatic paths that might bypass the
    // disabled state. Surface a warning so the operator understands why the
    // send was dropped, then no-op.
    if (activeAgent?.agentType === 'system') {
      message.warning('System agents are read-only via Chat. Use admin tools to configure.');
      return;
    }
    if (!activeSessionId) {
      if (!selectedAgent) {
        message.warning('Please select an agent first');
        return;
      }
      try {
        const res = await createSession({ userId, agentId: selectedAgent });
        const newSession = res.data;
        const sid = String(newSession.id ?? newSession.sessionId);
        setActiveSessionId(sid);
        setSessions((prev) => [newSession, ...prev]);
        await doSend(sid, text, files);
      } catch {
        message.error('Failed to create session');
      }
      return;
    }
    await doSend(activeSessionId, text, files);
  };

  const doSend = async (sid: string, text: string, files: File[] = []) => {
    setLoopSpans([]);
    // Optimistic insert ONLY for text-only turns. When files are present, the
    // server-persisted message is a ContentBlock[] (image_ref / pdf_ref + text)
    // while a local optimistic preview can only be a string — the WS-dedup
    // path (useChatWsEventHandler) compares string content only, so a
    // string-vs-array mismatch falls through and renders TWO bubbles for the
    // same user turn. Skip the optimistic insert for multimodal turns and
    // let the BE → WS push populate the bubble (~100ms after upload).
    if (files.length === 0) {
      setRawMessages((prev) => [...prev, { role: 'user', content: text }]);
    }
    setRuntimeStatus('running');
    setRuntimeStep('Starting');
    // MULTIMODAL-MVP — upload phase is its own try/catch so we can surface
    // the precise per-file failure (409 MAIN_MODEL_NOT_VISION_CAPABLE,
    // 413 too large, 415 wrong type, 500) without conflating it with the
    // chat send error path. We bail before sending if any file fails.
    const uploaded: string[] = [];
    for (const file of files) {
      try {
        const res = await uploadChatAttachment(sid, userId, file);
        uploaded.push(res.data.id);
      } catch (e: unknown) {
        const resp = (e as { response?: { status?: number; data?: { code?: string; message?: string; error?: string } } })?.response;
        const status = resp?.status;
        const code = resp?.data?.code;
        const beMessage = resp?.data?.message ?? resp?.data?.error;
        if (status === 409 && code === 'MAIN_MODEL_NOT_VISION_CAPABLE') {
          // Redesign (2026-05-14): server rejects upload when agent.modelId is
          // not vision-capable. AntD message.error accepts a ReactNode; embed
          // an inline link to the agent config so the user can switch the main
          // model in one click. duration=6 (vs default 3) gives time to click
          // before auto-dismiss. Inline navigate (avoids forward-ref to
          // handleOpenAgentConfig declared further down the component).
          message.error(
            <span data-testid="multimodal-409-jump">
              请把 agent 的主模型切换为多模态模型 ·{' '}
              <a
                data-testid="multimodal-409-jump-link"
                onClick={() => {
                  if (selectedAgent == null) {
                    navigate('/agents');
                  } else {
                    navigate(`/agents?openAgentId=${selectedAgent}&tab=overview`);
                  }
                }}
                style={{ color: 'inherit', textDecoration: 'underline', cursor: 'pointer' }}
              >
                打开 agent 配置
              </a>
            </span>,
            6,
          );
        } else if (status === 413) {
          message.error(beMessage || `${file.name}: file too large`);
        } else if (status === 415) {
          message.error(beMessage || `${file.name}: unsupported file type`);
        } else {
          message.error(beMessage || `Failed to upload ${file.name}`);
        }
        setRuntimeStatus('idle');
        return;
      }
    }
    try {
      await sendMessage(sid, { message: text, userId, attachmentIds: uploaded });
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status;
      if (status === 429) {
        message.error('Server is busy, please try again later');
      } else {
        message.error('Failed to send message');
      }
      setRuntimeStatus('idle');
    }
  };

  const handleAnswerAsk = async (answer: string) => {
    if (!pendingAsk || !activeSessionId) return;
    try {
      await answerAsk(activeSessionId, pendingAsk.askId, answer, userId);
      setPendingAsk(null);
      setOtherInput('');
    } catch {
      message.error('Failed to submit answer');
    }
  };

  const handleAnswerAskMessage = async (askId: string, answer: string) => {
    if (!activeSessionId) return;
    try {
      await answerAsk(activeSessionId, askId, answer, userId);
    } catch {
      message.error('Failed to submit answer');
    }
  };

  const handleConfirmDecision = async (decision: ConfirmationDecision) => {
    if (!pendingConfirm || !activeSessionId || confirmSubmitting) return;
    setConfirmSubmitting(decision);
    try {
      await submitConfirmation(
        activeSessionId,
        pendingConfirm.confirmationId,
        decision,
        userId,
      );
      // Optimistically clear the card; backend will also emit session_status
      // transitioning out of waiting_user which redundantly clears it via
      // useChatWsEventHandler.
      setPendingConfirm(null);
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status;
      if (status === 409 || status === 404) {
        // Already handled (expired / processed) — drop the card silently.
        message.info('Confirmation already handled');
        setPendingConfirm(null);
      } else {
        message.error('Failed to submit decision');
      }
    } finally {
      setConfirmSubmitting(null);
    }
  };

  const handleConfirmDecisionMessage = async (
    confirmationId: string,
    decision: ConfirmationDecision,
  ) => {
    if (!activeSessionId) return;
    try {
      await submitConfirmation(activeSessionId, confirmationId, decision, userId);
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status;
      if (status === 409 || status === 404 || status === 410) {
        message.info('Confirmation already handled');
      } else {
        message.error('Failed to submit decision');
      }
    }
  };

  const handleModeChange = async (mode: ExecutionMode) => {
    if (!activeSessionId) {
      setExecutionModeState(mode);
      return;
    }
    try {
      await setSessionMode(activeSessionId, mode, userId);
      setExecutionModeState(mode);
      message.success(`Switched to ${mode} mode`);
    } catch {
      message.error('Failed to switch mode');
    }
  };

  // SYSTEM-AGENT-TYPING Phase 2.3 — gate Chat send when the active agent is a
  // system agent (cron-managed). Resolved from `agents` so the gate works for
  // both fresh sessions (selectedAgent only) and existing sessions (loaded via
  // useChatSession). Defined *before* `inputDisabled` so the latch derives
  // from it; full `activeAgent` lookup repeats below for downstream usage —
  // keeping a local memo here avoids a forward reference.
  const isSystemAgent =
    agents.find((a) => a.id === selectedAgent)?.agentType === 'system';

  // §8 定义：runtimeStatus === 'waiting_user' 且 pendingAsk/pendingConfirm 任一非空时 disable。
  // 两 latch 用 OR 连接是防御 —— 后端 B3 fix 已保证两者不并存，但事件顺序抖动时
  // 前端至少不会放行输入。Phase 2.3 加 isSystemAgent OR：system agent 永远只读发送。
  const inputDisabled =
    isSystemAgent ||
    (runtimeStatus === 'waiting_user' &&
      (pendingAsk != null || pendingConfirm != null));

  const combinedInflightTools = useMemo(() => {
    const merged: Record<string, InflightTool> = {};
    for (const [id, s] of Object.entries(streamingToolInputs)) {
      merged[id] = { name: s.name, input: s.jsonBuffer, startTs: s.startTs };
    }
    for (const [id, t] of Object.entries(inflightTools)) {
      merged[id] = t;
    }
    return merged;
  }, [inflightTools, streamingToolInputs]);

  // P10 r2 (W1): wrap in useCallback so the slashCommandConfig useMemo
  // below has a stable dependency. Setters from useState are stable, so the
  // only real deps are activeSessionId + userId.
  const refreshCompactStats = useCallback(async () => {
    if (!activeSessionId) return;
    try {
      const res = await getSession(activeSessionId, userId);
      const s = res.data;
      setLightCompactCount(s.lightCompactCount ?? 0);
      setFullCompactCount(s.fullCompactCount ?? 0);
      setTotalTokensReclaimed(s.totalTokensReclaimed ?? 0);
    } catch {
      // non-critical
    }
  }, [activeSessionId, userId]);

  const handleCompactClick = async () => {
    if (!activeSessionId || compacting || runtimeStatus === 'running') return;
    setCompacting(true);
    try {
      const res = await compactSession(
        activeSessionId,
        'full',
        userId,
        'user clicked compact button',
      );
      const reclaimed = res.data?.tokensReclaimed ?? 0;
      message.success(`Compacted: reclaimed ${reclaimed} tokens`);
      await refreshCompactStats();
      try {
        const mres = await getSessionMessages(activeSessionId, userId);
        setRawMessages(stripRemindersFromMessageList(extractList(mres)));
      } catch {
        message.warning('Could not refresh messages after compaction');
      }
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status;
      if (status === 409) {
        message.warning('Session is running, cannot compact');
      } else {
        message.error('Compaction failed');
      }
    } finally {
      setCompacting(false);
    }
  };

  const handleOpenCompactModal = async () => {
    setCompactModalOpen(true);
    if (!activeSessionId) return;
    try {
      const res = await getCompactions(activeSessionId, userId);
      setCompactEvents(extractList(res));
    } catch {
      setCompactEvents([]);
    }
  };

  const loadCheckpoints = async (withDetail = true) => {
    const sessionId = activeSessionIdRef.current;
    if (!sessionId) return;
    const loadSeq = ++checkpointLoadSeqRef.current;
    setCheckpointsLoading(true);
    try {
      const res = await getSessionCheckpoints(sessionId, userId, 30);
      if (loadSeq !== checkpointLoadSeqRef.current || sessionId !== activeSessionIdRef.current) return;
      const rows = Array.isArray(res.data) ? res.data : [];
      setCheckpoints(rows);
      if (!withDetail) return;
      const first = rows[0];
      if (!first) {
        setSelectedCheckpoint(undefined);
        return;
      }
      const detail = await getSessionCheckpoint(sessionId, first.id, userId);
      if (loadSeq !== checkpointLoadSeqRef.current || sessionId !== activeSessionIdRef.current) return;
      setSelectedCheckpoint(detail.data);
    } catch {
      if (loadSeq !== checkpointLoadSeqRef.current || sessionId !== activeSessionIdRef.current) return;
      setCheckpoints([]);
      setSelectedCheckpoint(undefined);
      message.error('Failed to load checkpoints');
    } finally {
      if (loadSeq === checkpointLoadSeqRef.current) {
        setCheckpointsLoading(false);
      }
    }
  };

  const handleOpenCheckpointModal = async () => {
    setCheckpointModalOpen(true);
    await loadCheckpoints(true);
  };

  const handleSelectCheckpoint = async (checkpointId: string) => {
    const sessionId = activeSessionIdRef.current;
    if (!sessionId) return;
    const detailSeq = ++checkpointDetailSeqRef.current;
    try {
      const detail = await getSessionCheckpoint(sessionId, checkpointId, userId);
      if (detailSeq !== checkpointDetailSeqRef.current || sessionId !== activeSessionIdRef.current) return;
      setSelectedCheckpoint(detail.data);
    } catch {
      if (detailSeq !== checkpointDetailSeqRef.current) return;
      message.error('Failed to load checkpoint detail');
    }
  };

  const refreshMessagesAfterMutation = async () => {
    const sessionId = activeSessionIdRef.current;
    if (!sessionId) return;
    await refreshCompactStats();
    try {
      const mres = await getSessionMessages(sessionId, userId);
      if (sessionId !== activeSessionIdRef.current) return;
      setRawMessages(stripRemindersFromMessageList(extractList(mres)));
    } catch {
      message.warning('Checkpoint applied, but failed to refresh messages');
    }
  };

  const handleBranchCheckpoint = async (checkpointId: string) => {
    if (!activeSessionId) return;
    setCheckpointActionLoading(`branch:${checkpointId}`);
    try {
      const res = await branchFromCheckpoint(activeSessionId, checkpointId, userId);
      const newSessionId = String(res.data?.id ?? '');
      if (!newSessionId) {
        throw new Error('Missing branched session id');
      }
      message.success('Checkpoint branch created');
      setCheckpointModalOpen(false);
      navigate(`/chat/${newSessionId}`);
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status;
      if (status === 409) {
        message.warning('Session is running or compacting, cannot branch now');
      } else {
        message.error('Failed to branch checkpoint');
      }
    } finally {
      setCheckpointActionLoading(null);
    }
  };

  const handleRestoreCheckpoint = async (checkpointId: string) => {
    if (!activeSessionId) return;
    const ok = await new Promise<boolean>((resolve) => {
      Modal.confirm({
        title: '确认恢复到该 checkpoint？',
        content: '恢复会覆盖当前会话消息历史，此操作不可撤销。',
        okText: '确认恢复',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
    if (!ok) return;

    setCheckpointActionLoading(`restore:${checkpointId}`);
    try {
      await restoreFromCheckpoint(activeSessionId, checkpointId, userId);
      await refreshMessagesAfterMutation();
      await loadCheckpoints(false);
      message.success('Session restored from checkpoint');
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status;
      if (status === 409) {
        message.warning('Session is running or compacting, cannot restore now');
      } else {
        message.error('Failed to restore checkpoint');
      }
    } finally {
      setCheckpointActionLoading(null);
    }
  };

  const handleCancel = async () => {
    if (!activeSessionId || cancelling) return;
    setCancelling(true);
    try {
      await cancelChat(activeSessionId, userId);
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status;
      if (status === 409) {
        message.info('No active loop running');
      } else {
        message.error('Cancel failed');
      }
      setCancelling(false);
    }
  };

  const activeSession = sessions.find(
    (s) => String(s.id) === String(activeSessionId),
  );
  const activeAgent = agents.find((a) => a.id === selectedAgent);
  const agentName = activeAgent?.name;
  // MULTIMODAL-MVP redesign (2026-05-14): upload button is enabled iff the
  // agent's main model is in the vision-capable allowlist (BE returns
  // `supportsVision` per model on /api/llm/models). FE picker shows a "多模态"
  // chip on these models so users pick one to enable uploads. Gate stays
  // defense-in-depth alongside the BE upload-endpoint check.
  const { options: modelOptions } = useLlmModels();
  const visionCapableSet = useMemo(() => {
    const ids = modelOptions.filter((o) => o.supportsVision).map((o) => o.id);
    return new Set(ids);
  }, [modelOptions]);
  const multimodalEnabled =
    typeof activeAgent?.modelId === 'string' &&
    activeAgent.modelId.length > 0 &&
    visionCapableSet.has(activeAgent.modelId);
  const handleOpenAgentConfig = useCallback(() => {
    if (selectedAgent == null) {
      navigate('/agents');
      return;
    }
    // PRD/tech-design says AgentList consumes `?openAgentId=` to auto-open
    // the drawer; we pair that with `tab=overview` so the picker is visible
    // immediately. The actual openAgentId effect lives in AgentList.tsx.
    navigate(`/agents?openAgentId=${selectedAgent}&tab=overview`);
  }, [selectedAgent, navigate]);
  const sessionTitle =
    (activeSession?.title && activeSession.title !== 'New Session'
      ? activeSession.title
      : undefined) ?? (activeSessionId ? `Session ${activeSessionId.slice(0, 8)}` : 'New conversation');

  const compactionSummary = {
    lightCount: lightCompactCount,
    fullCount: fullCompactCount,
    tokensReclaimed: totalTokensReclaimed,
  };

  const totalInputTokens = Number(
    (activeSession as { totalInputTokens?: number } | undefined)?.totalInputTokens ?? 0,
  );
  const totalOutputTokens = Number(
    (activeSession as { totalOutputTokens?: number } | undefined)?.totalOutputTokens ?? 0,
  );
  const tokenUsage = {
    used: totalInputTokens + totalOutputTokens,
    total: DEFAULT_CONTEXT_WINDOW_TOKENS,
    breakdown: [
      { label: 'input', value: totalInputTokens },
      { label: 'output', value: totalOutputTokens },
    ],
  };

  // P10 r2 (W1): memoise so streaming/WS-driven re-renders of Chat.tsx
  // don't invalidate ChatInput's React.memo. `navigate` is stable across
  // renders (React Router 7 invariant); `refreshCompactStats` is wrapped in
  // useCallback above, so this useMemo only re-fires when activeSessionId
  // or userId actually change.
  const slashCommandConfig = useMemo(
    () =>
      activeSessionId
        ? {
            userId,
            sessionId: activeSessionId,
            onRedirect: (newSessionId: string) => {
              // P10 — `/new` redirect: navigate to the new session URL; the
              // existing useEffect tracking urlSessionId will sync
              // activeSessionId.
              navigate(`/sessions/${newSessionId}`);
            },
            onModelChanged: () => {
              // P10 r2 (W2): no header chip yet. When a model chip is
              // added, extend refreshCompactStats (or add a separate
              // getSession call here) to also setState the
              // session.runtimeModelOverride. The current call only
              // refreshes compact counts.
              void refreshCompactStats();
            },
          }
        : undefined,
    [activeSessionId, userId, navigate, refreshCompactStats],
  );

  return (
    <div className="chat-redesign">
      <ChatSidebar
        agents={agents}
        sessions={sessions}
        selectedAgent={selectedAgent}
        activeSessionId={activeSessionId}
        loading={sessionsLoading}
        onSelectAgent={(id) => {
          setSelectedAgent(id);
          setActiveSessionId(undefined);
          setRawMessages([]);
        }}
        onNewChat={handleNewSession}
        onSelectSession={setActiveSessionId}
      />

      <main className="center">
        {activeSessionId || selectedAgent ? (
          <>
            <header className="session-header">
              <div style={{ minWidth: 0 }}>
                <h1 className="session-title-big">{sessionTitle}</h1>
                <div className="session-crumbs">
                  <span>session</span>
                  <span className="sep">/</span>
                  <span>
                    {activeSessionId ? activeSessionId.slice(0, 8) : 'new'}
                  </span>
                  {agentName && (
                    <>
                      <span className="sep">·</span>
                      <span style={{ color: 'var(--accent)' }}>{agentName}</span>
                    </>
                  )}
                  <span className="sep">·</span>
                  <ChannelBadge
                    platform={
                      (activeSession?.channelPlatform as string | null | undefined) ?? 'web'
                    }
                  />
                  {sessionDepth > 0 && (
                    <>
                      <span className="sep">·</span>
                      <span>depth {sessionDepth}</span>
                    </>
                  )}
                  {collabHandle && (
                    <>
                      <span className="sep">·</span>
                      <span>team {collabHandle}</span>
                    </>
                  )}
                  {parentSessionId && (
                    <>
                      <span className="sep">·</span>
                      <button
                        type="button"
                        onClick={() => navigate(`/chat/${parentSessionId}`)}
                        style={{
                          background: 'transparent',
                          border: 0,
                          color: 'var(--accent)',
                          cursor: 'pointer',
                          padding: 0,
                          font: 'inherit',
                        }}
                      >
                        ↑ parent
                      </button>
                    </>
                  )}
                </div>
              </div>
              <div className="session-actions">
                {(lightCompactCount > 0 ||
                  fullCompactCount > 0 ||
                  totalTokensReclaimed > 0) && (
                  <Chip
                    title="Compaction history"
                    onClick={handleOpenCompactModal}
                  >
                    <IconCompact s={11} /> {lightCompactCount} light ·{' '}
                    {fullCompactCount} full ·{' '}
                    {totalTokensReclaimed > 0
                      ? `–${(totalTokensReclaimed / 1000).toFixed(1)}K tok`
                      : '0 tok'}
                  </Chip>
                )}
                {activeSessionId && (
                  <Chip title="Checkpoint list and actions" onClick={handleOpenCheckpointModal}>
                    <IconReplay s={11} /> Checkpoints
                  </Chip>
                )}
                <Seg
                  value={viewMode}
                  options={[
                    { value: 'chat', label: 'Chat', icon: <IconChat s={11} /> },
                    {
                      value: 'replay',
                      label: 'Replay',
                      icon: <IconReplay s={11} />,
                    },
                  ]}
                  onChange={setViewMode}
                />
                <Seg
                  value={executionMode}
                  options={[
                    { value: 'ask', label: 'ask' },
                    { value: 'auto', label: 'auto' },
                  ]}
                  onChange={handleModeChange}
                />
              </div>
            </header>

            {isSystemAgent && (
              <Alert
                type="info"
                showIcon
                title="System agents are read-only via Chat. Use admin tools to configure."
                data-testid="system-agent-chat-banner"
                style={{ margin: '8px 16px' }}
              />
            )}

            {activeSessionId && (
              <RuntimeBanner
                runtimeStatus={runtimeStatus}
                runtimeStep={runtimeStep}
                runtimeError={runtimeError}
                cancelling={cancelling}
                onCancel={handleCancel}
              />
            )}

            {viewMode === 'chat' ? (
              <>
                {pendingAsk && (
                  <PendingAskCard
                    pendingAsk={pendingAsk}
                    otherInput={otherInput}
                    onOtherInputChange={setOtherInput}
                    onAnswer={handleAnswerAsk}
                  />
                )}
                {pendingConfirm && (
                  <InstallConfirmationCard
                    payload={pendingConfirm}
                    submitting={confirmSubmitting != null}
                    submittingDecision={confirmSubmitting}
                    onDecision={handleConfirmDecision}
                  />
                )}
                <ChatWindow
                  messages={messages}
                  loading={runtimeStatus === 'running'}
                  onSend={handleSend}
                  inputDisabled={inputDisabled}
                  inflightTools={combinedInflightTools}
                  streamingText={streamingText}
                  compactionNotice={compactionNotice}
                  onCompactionDismiss={() => setCompactionNotice(false)}
                  runtimeStatus={runtimeStatus}
                  agentName={agentName}
                  onAnswerAsk={handleAnswerAskMessage}
                  onConfirmDecision={handleConfirmDecisionMessage}
                  slashCommandConfig={slashCommandConfig}
                  multimodalEnabled={multimodalEnabled}
                  onOpenAgentConfig={handleOpenAgentConfig}
                  sessionResetKey={activeSessionId ?? ''}
                />
              </>
            ) : (
              <SessionReplay sessionId={activeSessionId} />
            )}
          </>
        ) : (
          <div
            style={{
              flex: 1,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--fg-3)',
              fontFamily: 'var(--font-serif)',
              fontSize: 20,
            }}
          >
            Select an agent to start chatting.
          </div>
        )}
      </main>

      <RightRail
        collabMembers={collabMembers}
        collabRunId={collabRunId}
        collabHandle={collabHandle}
        collabLeaderSessionId={collabLeaderSessionId}
        peerMessages={peerMessages}
        inflightTools={combinedInflightTools}
        runtimeStatus={runtimeStatus}
        tokenUsage={tokenUsage}
        compaction={compactionSummary}
        currentSessionId={activeSessionId ?? null}
        sessionId={activeSessionId ?? null}
        userId={userId}
        onCompactClick={handleCompactClick}
        compacting={compacting}
        loopSpans={loopSpans}
      />

      <CompactionHistoryModal
        open={compactModalOpen}
        onClose={() => setCompactModalOpen(false)}
        events={compactEvents}
      />
      <CheckpointModal
        open={checkpointModalOpen}
        loading={checkpointsLoading}
        checkpoints={checkpoints}
        selectedCheckpoint={selectedCheckpoint}
        actionLoadingId={checkpointActionLoading}
        onClose={() => setCheckpointModalOpen(false)}
        onSelect={handleSelectCheckpoint}
        onRefresh={() => {
          void loadCheckpoints(true);
        }}
        onBranch={(checkpointId) => {
          void handleBranchCheckpoint(checkpointId);
        }}
        onRestore={(checkpointId) => {
          void handleRestoreCheckpoint(checkpointId);
        }}
      />
    </div>
  );
};

export default Chat;
