import React, { useEffect, useMemo, useState } from 'react';
import { message, Typography, Empty, Alert, Button, Tag, Collapse } from 'antd';
import { ArrowUpOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import ChatWindow from '../components/ChatWindow';
import SessionReplay from '../components/SessionReplay';
import SubAgentRunsPanel from '../components/SubAgentRunsPanel';
import ChildSessionsPanel from '../components/ChildSessionsPanel';
import CollabRunPanel from '../components/CollabRunPanel';
import CollabRunSummary from '../components/CollabRunSummary';
import CollabRunTimeline from '../components/CollabRunTimeline';
import PeerMessageFeed from '../components/PeerMessageFeed';
import CompactionHistoryModal from '../components/CompactionHistoryModal';
import RuntimeBanner from '../components/RuntimeBanner';
import PendingAskCard from '../components/PendingAskCard';
import SessionToolbar from '../components/SessionToolbar';
import ChatSidebar from '../components/ChatSidebar';
import {
  getAgents,
  createSession,
  getSessions,
  getSessionMessages,
  sendMessage,
  cancelChat,
  answerAsk,
  setSessionMode,
  getSession,
  compactSession,
  getCompactions,
  extractList,
} from '../api';
import { AgentSchema, SessionSchema, safeParseList } from '../api/schemas';
import { useChatWebSocket } from '../hooks/useChatWebSocket';
import { useChatMessages, type InflightTool } from '../hooks/useChatMessages';
import { useCollabState } from '../hooks/useCollabState';
import { useChatSession, type RuntimeStatus, type ExecutionMode } from '../hooks/useChatSession';
import { useChatWsEventHandler } from '../hooks/useChatWsEventHandler';
import { useAuth } from '../contexts/AuthContext';

const { Text } = Typography;

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

const Chat: React.FC = () => {
  const { sessionId: urlSessionId } = useParams<{ sessionId?: string }>();
  const navigate = useNavigate();
  const { userId } = useAuth();
  const [agents, setAgents] = useState<any[]>([]);
  const [parentSessionId, setParentSessionId] = useState<string | null>(null);
  const [sessionDepth, setSessionDepth] = useState<number>(0);
  const [selectedAgent, setSelectedAgent] = useState<number | undefined>();
  const [sessions, setSessions] = useState<any[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(false);
  const [activeSessionId, setActiveSessionId] = useState<string | undefined>(urlSessionId);

  const {
    setRawMessages,
    messages,
    streamingText,
    setStreamingText,
    streamingToolInputs,
    setStreamingToolInputs,
    inflightTools,
    setInflightTools,
  } = useChatMessages(activeSessionId);

  const [runtimeStatus, setRuntimeStatus] = useState<RuntimeStatus>('idle');
  const [runtimeStep, setRuntimeStep] = useState<string>('');
  const [runtimeError, setRuntimeError] = useState<string>('');
  const [executionMode, setExecutionModeState] = useState<ExecutionMode>('ask');
  const [pendingAsk, setPendingAsk] = useState<PendingAsk | null>(null);
  const [otherInput, setOtherInput] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [lightCompactCount, setLightCompactCount] = useState(0);
  const [fullCompactCount, setFullCompactCount] = useState(0);
  const [totalTokensReclaimed, setTotalTokensReclaimed] = useState(0);
  const [viewMode, setViewMode] = useState<'chat' | 'replay'>('chat');
  const [compactModalOpen, setCompactModalOpen] = useState(false);
  const [compactEvents, setCompactEvents] = useState<any[]>([]);
  const [compacting, setCompacting] = useState(false);
  const [compactionNotice, setCompactionNotice] = useState(false);

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

  // URL → activeSessionId 同步(navigate('/chat/:id') 时生效)
  useEffect(() => {
    if (urlSessionId && urlSessionId !== activeSessionId) {
      setActiveSessionId(urlSessionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlSessionId]);

  // Load agents
  useEffect(() => {
    getAgents()
      .then((res) => {
        setAgents(safeParseList(AgentSchema, extractList(res)));
      })
      .catch(() => message.error('Failed to load agents'));
  }, []);

  // Load sessions when agent selected
  useEffect(() => {
    if (selectedAgent == null) return;
    let cancelled = false;
    setSessionsLoading(true);
    getSessions(userId)
      .then((res) => {
        if (cancelled) return;
        const list = safeParseList(SessionSchema, extractList<any>(res)).filter(
          (s: any) => s.agentId === selectedAgent,
        );
        setSessions(list);
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setSessionsLoading(false);
      });
    return () => { cancelled = true; };
  }, [selectedAgent, userId]);

  // Reset Chat-local state when session changes
  useEffect(() => {
    setPendingAsk(null);
    setRuntimeError('');
    setCancelling(false);
    setViewMode('chat');
    setParentSessionId(null);
    setSessionDepth(0);
    setCompactionNotice(false);
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
    setInflightTools,
    setStreamingText,
    setStreamingToolInputs,
    setCancelling,
    setRawMessages,
    setOtherInput,
    setCollabRunStatus,
    setSessions,
    setCompactionNotice,
  });

  useChatWebSocket(activeSessionId, handleWsEvent);

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

  const handleSend = async (text: string) => {
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
        await doSend(sid, text);
      } catch {
        message.error('Failed to create session');
      }
      return;
    }
    await doSend(activeSessionId, text);
  };

  const doSend = async (sid: string, text: string) => {
    setRawMessages((prev) => [...prev, { role: 'user', content: text }]);
    setRuntimeStatus('running');
    setRuntimeStep('Starting');
    try {
      await sendMessage(sid, { message: text, userId });
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

  const inputDisabled = runtimeStatus === 'waiting_user';

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

  const refreshCompactStats = async () => {
    if (!activeSessionId) return;
    try {
      const res = await getSession(activeSessionId, userId);
      const s = res.data;
      setLightCompactCount(s.lightCompactCount ?? 0);
      setFullCompactCount(s.fullCompactCount ?? 0);
      setTotalTokensReclaimed(s.totalTokensReclaimed ?? 0);
    } catch {
      // Non-critical background refresh — silently ignore; compact op itself
      // already showed success/error feedback to the user.
    }
  };

  const handleCompactClick = async () => {
    if (!activeSessionId || compacting || runtimeStatus === 'running') return;
    setCompacting(true);
    try {
      const res = await compactSession(activeSessionId, 'full', userId, 'user clicked compact button');
      const reclaimed = res.data?.tokensReclaimed ?? 0;
      message.success(`Compacted: reclaimed ${reclaimed} tokens`);
      await refreshCompactStats();
      try {
        const mres = await getSessionMessages(activeSessionId, userId);
        setRawMessages(extractList(mres));
      } catch {}
    } catch (e: unknown) {
      const status = (e as { response?: { status?: number } })?.response?.status;
      if (status === 409) {
        message.warning('Session is running, cannot compact');
      } else {
        message.error('Compaction failed');
      }
    }
    setCompacting(false);
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

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
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

      {/* Right panel - chat */}
      <div
        style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', background: 'var(--bg-primary)' }}
      >
        {activeSessionId || selectedAgent ? (
          <>
            {activeSessionId && (
              <SessionToolbar
                lightCompactCount={lightCompactCount}
                fullCompactCount={fullCompactCount}
                totalTokensReclaimed={totalTokensReclaimed}
                viewMode={viewMode}
                onViewModeChange={setViewMode}
                compacting={compacting}
                runtimeRunning={runtimeStatus === 'running'}
                executionMode={executionMode}
                onExecutionModeChange={handleModeChange}
                onCompactClick={handleCompactClick}
                onOpenCompactModal={handleOpenCompactModal}
              />
            )}
            {activeSessionId && parentSessionId && (
              <div
                style={{
                  padding: '6px 12px',
                  borderBottom: '1px solid var(--border-subtle)',
                  background: 'var(--bg-assistant-structured)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                <Button
                  type="link"
                  size="small"
                  icon={<ArrowUpOutlined />}
                  onClick={() => navigate(`/chat/${parentSessionId}`)}
                  style={{ padding: 0 }}
                >
                  Back to parent
                </Button>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  (SubAgent child · depth {sessionDepth})
                </Text>
              </div>
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
            <SubAgentRunsPanel
              sessionId={activeSessionId}
              parentRunning={runtimeStatus === 'running'}
            />
            <ChildSessionsPanel
              sessionId={activeSessionId}
              parentRunning={runtimeStatus === 'running'}
              agents={agents}
            />
            {collabRunId && collabHandle && (
              <Alert
                type="info"
                banner
                message={
                  <span style={{ fontSize: 12 }}>
                    <Tag color="blue" style={{ marginRight: 6 }}>Team: {collabHandle}</Tag>
                    Part of collaboration run {collabRunId.length > 8 ? collabRunId.slice(0, 8) + '...' : collabRunId}
                    {collabLeaderSessionId && (
                      <> | Leader: {collabLeaderSessionId.slice(0, 8)}...</>
                    )}
                    {collabRunStatus && (
                      <> | Status: {collabRunStatus}</>
                    )}
                  </span>
                }
                style={{ margin: '8px 12px 0' }}
              />
            )}
            <CollabRunPanel
              collabRunId={collabRunId}
              sessionId={activeSessionId}
            />
            <CollabRunSummary collabRunId={collabRunId} />
            {collabRunId && (
              <Collapse
                size="small"
                style={{ margin: '8px 12px 0' }}
                items={[
                  {
                    key: 'timeline',
                    label: 'Collaboration Timeline',
                    children: <CollabRunTimeline collabRunId={collabRunId} />,
                  },
                ]}
              />
            )}
            <PeerMessageFeed
              collabRunId={collabRunId}
            />
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
                <ChatWindow
                  messages={messages}
                  loading={runtimeStatus === 'running'}
                  onSend={handleSend}
                  inputDisabled={inputDisabled}
                  inflightTools={combinedInflightTools}
                  streamingText={streamingText}
                  compactionNotice={compactionNotice}
                  onCompactionDismiss={() => setCompactionNotice(false)}
                />
              </>
            ) : (
              <SessionReplay sessionId={activeSessionId} />
            )}
          </>
        ) : (
          <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Empty description="Select an Agent to start chatting" />
          </div>
        )}
      </div>
      <CompactionHistoryModal
        open={compactModalOpen}
        onClose={() => setCompactModalOpen(false)}
        events={compactEvents}
      />
    </div>
  );
};

export default Chat;
